import { startAuthentication, startRegistration, browserSupportsWebAuthn } from '@simplewebauthn/browser';
import type { WebAuthnOptions } from './api';

export const webauthnSupported = () => browserSupportsWebAuthn();

/** Подпись вызова ключом: второй фактор, passkey или повторная аутентификация. */
export async function signWithKey(options: WebAuthnOptions) {
  return startAuthentication({ optionsJSON: options.publicKey });
}

/** Регистрация нового ключа. */
export async function createKey(options: WebAuthnOptions) {
  return startRegistration({ optionsJSON: options.publicKey });
}
