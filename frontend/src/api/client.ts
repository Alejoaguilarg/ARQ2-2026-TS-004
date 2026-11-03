// Every call goes to /api on the same origin: nginx (docker-compose) or Vite (npm run dev) forwards it to the
// Gateway, so the browser never needs CORS. Errors come back as the spec's ErrorResponse (spec.md §3.2).

export interface ApiError {
  status: number;
  code: string;
  message: string;
  correlationId?: string;
  retryAfterSeconds?: number;
}

export class ApiFailure extends Error {
  constructor(readonly error: ApiError) {
    super(`${error.status} ${error.code}`);
    this.name = 'ApiFailure';
  }
}

export interface RequestOptions {
  method?: 'GET' | 'POST';
  body?: unknown;
  headers?: Record<string, string>;
}

export interface ApiResponse<T> {
  status: number;
  body: T;
  headers: Headers;
}

const BASE = '/api';

export async function request<T>(path: string, options: RequestOptions = {}): Promise<ApiResponse<T>> {
  const headers: Record<string, string> = { Accept: 'application/json', ...options.headers };
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  let response: Response;
  try {
    response = await fetch(BASE + path, {
      method: options.method ?? 'GET',
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
    });
  } catch {
    throw new ApiFailure({ status: 0, code: 'NETWORK_ERROR', message: 'The server could not be reached.' });
  }
  const text = await response.text();
  const json = parse(text);
  if (!response.ok) {
    throw new ApiFailure(toError(response, json));
  }
  return { status: response.status, body: json as T, headers: response.headers };
}

function parse(text: string): unknown {
  if (!text) {
    return undefined;
  }
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

function toError(response: Response, json: unknown): ApiError {
  const body = (json ?? {}) as Partial<ApiError>;
  const retryAfter = Number(response.headers.get('Retry-After'));
  return {
    status: response.status,
    code: typeof body.code === 'string' ? body.code : fallbackCode(response.status),
    message: typeof body.message === 'string' ? body.message : response.statusText,
    correlationId: typeof body.correlationId === 'string' ? body.correlationId
      : response.headers.get('X-Correlation-Id') ?? undefined,
    retryAfterSeconds: Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter : undefined,
  };
}

// nginx answers 502/504 itself when the Gateway is down, without the spec's body.
function fallbackCode(status: number): string {
  return status === 502 || status === 504 ? 'UPSTREAM_UNAVAILABLE' : 'INTERNAL_ERROR';
}

// Anything a call can throw, as an ApiError: a failure that is not an answer reads as a lost connection.
export function asApiError(e: unknown): ApiError {
  return e instanceof ApiFailure ? e.error : { status: 0, code: 'NETWORK_ERROR', message: String(e) };
}
