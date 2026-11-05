import { useQuery } from '@tanstack/react-query';
import { api, ApiError } from '@/shared/api/client';

/** Типы и запросы модуля identity (/api/v1/auth, /api/v1/me). */

export interface AuthConfig {
  passwordLogin: boolean;
  multiTenant: boolean;
  sso: { id: string; name: string }[];
  passkeys: boolean;
  rememberDeviceDays: number;
  passwordMinLength: number;
  authenticated: boolean;
  /** mfa — ждём второй фактор, mfa_setup — нужно подключить MFA */
  pendingStep: 'mfa' | 'mfa_setup' | null;
}

export type LoginStatus = 'ok' | 'mfa_required' | 'mfa_setup_required';

export interface LoginResult {
  status: LoginStatus;
  methods: string[];
  recoveryCodes: string[] | null;
}

export interface Me {
  id: string;
  tenantId: string;
  login: string;
  displayName: string;
  email: string | null;
  locale: string;
  timezone: string;
  source: 'local' | 'ldap' | 'oidc';
  roles: string[];
  security: { passwordSet: boolean; totp: boolean; keys: number; recoveryCodesLeft: number; mfaRequired: boolean };
  session: { id: string; authMethod: string; createdAt: string; idleTimeoutSeconds: number } | null;
}

export interface WebAuthnOptions {
  ceremonyId: string;
  // Формат WebAuthn Level 3 JSON, его принимает @simplewebauthn/browser.
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  publicKey: any;
}

export const meKey = ['me'] as const;

export function useAuthConfig() {
  return useQuery({
    queryKey: ['auth', 'config'],
    queryFn: ({ signal }) => api.get<AuthConfig>('/auth/config', { signal }),
    staleTime: 0,
  });
}

/** Текущий пользователь. null — не вошёл. */
export function useMe() {
  return useQuery({
    queryKey: meKey,
    queryFn: async ({ signal }) => {
      try {
        return await api.get<Me>('/me', { signal });
      } catch (e) {
        if (e instanceof ApiError && e.status === 401) return null;
        throw e;
      }
    },
    staleTime: 60_000,
  });
}

export function hasRole(me: Me | null | undefined, ...roles: string[]) {
  return !!me && roles.some((r) => me.roles.includes(r));
}

/** Сохраняем выбранную организацию для облачного режима. */
const TENANT_KEY = 'easyitsm.tenant';

export function savedTenant(): string {
  try {
    return localStorage.getItem(TENANT_KEY) ?? '';
  } catch {
    return '';
  }
}

export function saveTenant(tenant: string) {
  try {
    localStorage.setItem(TENANT_KEY, tenant);
  } catch {
    // не критично
  }
}
