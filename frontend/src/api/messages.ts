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
    case 'STEP_UP_FAILED':
      return { title: 'PIN incorrecto', text: 'Revísalo e inténtalo de nuevo. Tu envío sigue guardado.' };
    case 'STEP_UP_LOCKED':
      return { title: 'Confirmación en pausa', text: 'Por tu seguridad, espera 15 minutos antes de volver a intentarlo.' };
    case 'FINANCIAL_SESSION_REQUIRED':
    case 'FINANCIAL_SESSION_INVALID':
    case 'FINANCIAL_SESSION_EXPIRED':
    case 'FINANCIAL_SESSION_USED':
      return { title: 'Confirma de nuevo con tu PIN', text: 'Pasó un rato desde tu última confirmación. Solo te tomará un segundo.' };
    case 'SELF_TRANSFER_NOT_ALLOWED':
      return { title: 'No puedes enviarte dinero a ti mismo', text: 'Elige a otra persona para continuar.' };
    case 'IDEMPOTENCY_IN_PROGRESS':
      return { title: 'Tu envío ya va en camino', text: 'No hace falta tocar de nuevo. Espera un momento.' };
    case 'IDEMPOTENCY_KEY_REUSED':
      return { title: 'Cambiaste los datos del envío', text: 'Para enviarlo así, empieza un envío nuevo.' };
    case 'USER_NOT_FOUND':
      return { title: 'No encontramos esa cuenta', text: 'No hay una cuenta Finova con ese correo. Revisa que esté bien escrito.' };
    case 'WALLET_NOT_FOUND':
      return { title: 'Tu cuenta aún no está lista', text: 'Estamos terminando de configurarla. Te avisaremos cuando puedas enviar.' };
    case 'TRANSFER_NOT_FOUND':
      return { title: 'No encontramos ese envío', text: 'Puede que ya no esté disponible. Revisa tu lista de envíos.' };
    case 'FORBIDDEN_ROLE':
      return { title: 'Esta sección es solo para personal autorizado', text: 'Si crees que deberías tener acceso, habla con tu administrador.' };
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
