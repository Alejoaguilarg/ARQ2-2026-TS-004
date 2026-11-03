import type { ApiError } from './client';

// What the customer reads for each error (the "Customer messages" board of the design): plain words and a next
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
      return { title: 'Wrong email or password', text: 'Check your details. After 5 failed attempts, sign-in pauses for 15 minutes.' };
    case 'ACCOUNT_LOCKED':
      return { title: 'Sign-in paused for your security', text: 'There were several failed attempts. Try again in 15 minutes.' };
    case 'RATE_LIMIT_EXCEEDED':
      return {
        title: 'You are going too fast',
        text: error.retryAfterSeconds
          ? `Wait ${error.retryAfterSeconds} seconds and try again.`
          : 'Wait a moment and try again.',
      };
    case 'SECURITY_STORE_UNAVAILABLE':
    case 'IDEMPOTENCY_STORE_UNAVAILABLE':
      return { title: 'We are doing maintenance', text: 'Try again in a few minutes. Your money is safe.' };
    case 'UPSTREAM_UNAVAILABLE':
    case 'NETWORK_ERROR':
      return { title: 'We could not connect', text: 'Check your internet connection and try again.' };
    case 'VALIDATION_ERROR':
      return { title: 'Check the details', text: 'Some detail is not in the expected format.' };
    case 'STEP_UP_FAILED':
      return { title: 'Wrong PIN', text: 'Check it and try again. Your transfer is still saved.' };
    case 'STEP_UP_LOCKED':
      return { title: 'Confirmation paused', text: 'For your security, wait 15 minutes before trying again.' };
    case 'FINANCIAL_SESSION_REQUIRED':
    case 'FINANCIAL_SESSION_INVALID':
    case 'FINANCIAL_SESSION_EXPIRED':
    case 'FINANCIAL_SESSION_USED':
      return { title: 'Confirm again with your PIN', text: 'A while has passed since your last confirmation. It only takes a second.' };
    case 'SELF_TRANSFER_NOT_ALLOWED':
      return { title: 'You cannot send money to yourself', text: 'Choose someone else to continue.' };
    case 'IDEMPOTENCY_IN_PROGRESS':
      return { title: 'Your transfer is already on its way', text: 'No need to tap again. Wait a moment.' };
    case 'IDEMPOTENCY_KEY_REUSED':
      return { title: 'You changed the transfer details', text: 'To send it like this, start a new transfer.' };
    case 'USER_NOT_FOUND':
      return { title: 'We could not find that account', text: 'There is no Finova account with that email. Check that it is spelled right.' };
    case 'WALLET_NOT_FOUND':
      return { title: 'Your account is not ready yet', text: 'We are finishing setting it up. We will let you know when you can send.' };
    case 'TRANSFER_NOT_FOUND':
      return { title: 'We could not find that transfer', text: 'It may no longer be available. Check your list of transfers.' };
    case 'FORBIDDEN_ROLE':
      return { title: 'This section is for authorized staff only', text: 'If you think you should have access, talk to your administrator.' };
    default: {
      const reference = supportReference(error);
      return {
        title: 'Something went wrong on our side',
        text: reference
          ? `If it happens again, write to us and mention the reference ${reference}.`
          : 'If it happens again, write to us.',
      };
    }
  }
}
