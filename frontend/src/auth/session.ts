import { ApiFailure, request, type ApiResponse, type RequestOptions } from '../api/client';

// The signed-in user and the tokens behind every call (spec.md §3.3 to §3.5).
// - Access token: memory only, so a script injected in the page cannot read it from storage.
// - Refresh token: sessionStorage, so a reload keeps the session but closing the tab ends it.
// - deviceId: localStorage, stable per browser; the refresh token is bound to it.

export interface User {
  userId: string;
  email: string;
  roles: string[];
}

interface TokenPair {
  accessToken: string;
  refreshToken: string;
}

const REFRESH_KEY = 'finova.refreshToken';
const EMAIL_KEY = 'finova.email';
const DEVICE_KEY = 'finova.deviceId';

export class Session {
  private accessToken: string | null = null;
  private refreshing: Promise<boolean> | null = null;
  private current: User | null = null;
  private readonly listeners = new Set<(user: User | null) => void>();

  constructor(
    private readonly tabStore: Storage = sessionStorage,
    private readonly browserStore: Storage = localStorage,
  ) {}

  get user(): User | null {
    return this.current;
  }

  deviceId(): string {
    let id = this.browserStore.getItem(DEVICE_KEY);
    if (!id) {
      id = 'web-' + crypto.randomUUID();
      this.browserStore.setItem(DEVICE_KEY, id);
    }
    return id;
  }

  // Signs in without telling the listeners yet, so the sign-in screen can celebrate first; then call publish().
  async login(email: string, password: string): Promise<User> {
    const response = await request<TokenPair>('/auth/login', {
      method: 'POST',
      body: { email: email.trim(), password, deviceId: this.deviceId() },
    });
    this.tabStore.setItem(EMAIL_KEY, email.trim().toLowerCase());
    return this.store(response.body);
  }

  publish(): void {
    this.listeners.forEach((listener) => listener(this.current));
  }

  // After a reload only the refresh token is left: trade it for a new pair.
  async restore(): Promise<User | null> {
    if (!this.tabStore.getItem(REFRESH_KEY)) {
      return null;
    }
    return (await this.refresh()) ? this.current : null;
  }

  // One refresh at a time: two calls failing together must not both spend the refresh token, because Identity
  // treats a reused one as stolen and revokes the whole session (REFRESH_TOKEN_REUSED).
  refresh(): Promise<boolean> {
    if (!this.refreshing) {
      this.refreshing = this.doRefresh().finally(() => {
        this.refreshing = null;
      });
    }
    return this.refreshing;
  }

  private async doRefresh(): Promise<boolean> {
    const refreshToken = this.tabStore.getItem(REFRESH_KEY);
    if (!refreshToken) {
      return false;
    }
    try {
      const response = await request<TokenPair>('/auth/refresh', {
        method: 'POST',
        body: { refreshToken, deviceId: this.deviceId() },
      });
      this.store(response.body);
      return true;
    } catch (e) {
      // A network blip keeps the session for the next try; an answer from Identity ends it.
      if (!(e instanceof ApiFailure) || e.error.status === 0 || e.error.status >= 500) {
        return false;
      }
      this.clear();
      return false;
    }
  }

  // An API call as the signed-in user. An expired access token is refreshed once and the call repeated.
  async call<T>(path: string, options: RequestOptions = {}): Promise<ApiResponse<T>> {
    try {
      return await request<T>(path, this.withToken(options));
    } catch (e) {
      if (!(e instanceof ApiFailure) || e.error.status !== 401 || e.error.code !== 'UNAUTHORIZED') {
        if (e instanceof ApiFailure && e.error.code === 'TOKEN_REVOKED') {
          this.clear();
        }
        throw e;
      }
      if (!(await this.refresh())) {
        this.clear();
        throw e;
      }
      return request<T>(path, this.withToken(options));
    }
  }

  async logout(): Promise<void> {
    if (this.accessToken) {
      try {
        await request('/auth/logout', { method: 'POST', headers: { Authorization: `Bearer ${this.accessToken}` } });
      } catch {
        // Signed out here anyway; the tokens expire on their own.
      }
    }
    this.clear();
  }

  subscribe(listener: (user: User | null) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  private withToken(options: RequestOptions): RequestOptions {
    return { ...options, headers: { ...options.headers, Authorization: `Bearer ${this.accessToken ?? ''}` } };
  }

  private store(pair: TokenPair): User {
    this.accessToken = pair.accessToken;
    this.tabStore.setItem(REFRESH_KEY, pair.refreshToken);
    const claims = decode(pair.accessToken);
    this.current = {
      userId: String(claims.sub ?? ''),
      email: this.tabStore.getItem(EMAIL_KEY) ?? '',
      roles: Array.isArray(claims.roles) ? claims.roles.map(String) : [],
    };
    return this.current;
  }

  private clear(): void {
    const wasSignedIn = this.current !== null;
    this.accessToken = null;
    this.current = null;
    this.tabStore.removeItem(REFRESH_KEY);
    this.tabStore.removeItem(EMAIL_KEY);
    if (wasSignedIn) {
      this.publish();
    }
  }
}

// Reads the claims only; the Gateway is the one that verifies the signature.
function decode(token: string): Record<string, unknown> {
  try {
    const payload = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    return JSON.parse(atob(payload.padEnd(Math.ceil(payload.length / 4) * 4, '=')));
  } catch {
    return {};
  }
}
