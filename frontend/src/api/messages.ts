import type { ApiError } from './client';

// What the customer reads for each error (the "Mensajes al cliente" board of the design): plain words and a next
// step, never a code. The support reference is the start of the correlationId, enough to find the request in Loki.
export interface FriendlyMessage {
  title: string;
  text: string;
}

export function supportReference(error: Pick<ApiError, 'correlationId'>): string | undefined {
  return error.correlationId ? 'FN-' + error.correlationId.slice(0, 8).toUpperCase() : undefined;
}

export function friendly(error: ApiError): FriendlyMessage {
  switch (error.code) {
    case 'INVALID_CREDENTIALS':
      return { title: 'Correo o contraseña incorrectos', text: 'Revisa tus datos. Tras 5 intentos fallidos el ingreso se pausa 15 minutos.' };
    case 'ACCOUNT_LOCKED':
      return { title: 'Ingreso en pausa por tu seguridad', text: 'Hubo varios intentos fallidos. Vuelve a intentarlo en 15 minutos.' };
    case 'RATE_LIMIT_EXCEEDED':
      return {
        title: 'Vas muy rápido',
        text: error.retryAfterSeconds
          ? `Espera ${error.retryAfterSeconds} segundos y vuelve a intentarlo.`
          : 'Espera un momento y vuelve a intentarlo.',
      };
    case 'SECURITY_STORE_UNAVAILABLE':
    case 'IDEMPOTENCY_STORE_UNAVAILABLE':
      return { title: 'Estamos haciendo mantenimiento', text: 'Vuelve a intentarlo en unos minutos. Tu dinero está seguro.' };
    case 'UPSTREAM_UNAVAILABLE':
    case 'NETWORK_ERROR':
      return { title: 'No pudimos conectarnos', text: 'Revisa tu conexión a internet e inténtalo de nuevo.' };
    case 'VALIDATION_ERROR':
      return { title: 'Revisa los datos', text: 'Algún dato no tiene el formato esperado.' };
    default: {
      const reference = supportReference(error);
      return {
        title: 'Algo salió mal de nuestro lado',
        text: reference
          ? `Si vuelve a pasar, escríbenos y menciona la referencia ${reference}.`
          : 'Si vuelve a pasar, escríbenos.',
      };
    }
  }
}
