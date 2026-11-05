import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithProviders } from '@/test/render';
import { LoginPage, safeNext } from './LoginPage';

const config = {
  passwordLogin: true,
  multiTenant: false,
  sso: [{ id: 'keycloak', name: 'Keycloak' }],
  passkeys: false,
  rememberDeviceDays: 30,
  passwordMinLength: 12,
  authenticated: false,
  pendingStep: null,
};

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function renderLogin(path = '/login?next=/agent') {
  const router = createMemoryRouter(
    [
      { path: '/login', element: <LoginPage /> },
      { path: '/agent', element: <div>agent-home</div> },
      { path: '/', element: <div>root</div> },
    ],
    { initialEntries: [path] },
  );
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  renderWithProviders(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return router;
}

describe('LoginPage', () => {
  const fetchMock = vi.fn<typeof fetch>();

  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock);
    document.cookie = 'XSRF-TOKEN=csrf-123';
  });

  afterEach(() => {
    fetchMock.mockReset();
    vi.unstubAllGlobals();
  });

  it('входит по паролю и коду из приложения и возвращает на исходную страницу', async () => {
    const calls: { path: string; csrf: string | null; body: unknown }[] = [];
    fetchMock.mockImplementation(async (input, init) => {
      const url = new URL(String(input));
      const headers = new Headers(init?.headers);
      calls.push({ path: url.pathname, csrf: headers.get('X-XSRF-TOKEN'), body: init?.body ? JSON.parse(String(init.body)) : undefined });
      switch (url.pathname) {
        case '/api/v1/auth/config':
          return json(config);
        case '/api/v1/auth/login':
          return json({ status: 'mfa_required', methods: ['totp', 'recovery'], recoveryCodes: null });
        case '/api/v1/auth/mfa':
          return json({ status: 'ok', methods: [], recoveryCodes: null });
        default:
          return json({ status: 401 }, 401);
      }
    });
    const router = renderLogin();

    fireEvent.change(await screen.findByLabelText('Логин или e-mail', { exact: false }), { target: { value: 'agent' } });
    fireEvent.change(screen.getByLabelText('Пароль', { exact: false }), { target: { value: 'Demo-ITSM-2026!' } });
    fireEvent.click(screen.getByRole('button', { name: 'Войти' }));

    expect(await screen.findByText('Подтверждение входа')).toBeInTheDocument();
    const login = calls.find((c) => c.path === '/api/v1/auth/login');
    expect(login?.csrf).toBe('csrf-123');
    expect(login?.body).toEqual({ login: 'agent', password: 'Demo-ITSM-2026!' }); // gitleaks:allow

    const inputs = screen.getAllByRole('textbox');
    '123456'.split('').forEach((digit, i) => fireEvent.change(inputs[i]!, { target: { value: digit } }));
    fireEvent.click(screen.getByRole('button', { name: 'Подтвердить' }));

    await waitFor(() => expect(router.state.location.pathname).toBe('/agent'));
    expect(calls.find((c) => c.path === '/api/v1/auth/mfa')?.body).toMatchObject({ code: '123456' });
  });

  it('показывает понятную ошибку при неверном пароле', async () => {
    fetchMock.mockImplementation(async (input) => {
      const path = new URL(String(input)).pathname;
      if (path === '/api/v1/auth/config') return json(config);
      return json({ status: 401, code: 'invalid_credentials', detail: 'Unauthorized' }, 401);
    });
    renderLogin();
    fireEvent.change(await screen.findByLabelText('Логин или e-mail', { exact: false }), { target: { value: 'agent' } });
    fireEvent.change(screen.getByLabelText('Пароль', { exact: false }), { target: { value: 'wrong-password' } });
    fireEvent.click(screen.getByRole('button', { name: 'Войти' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Неверный логин или пароль');
  });

  it('предлагает вход через SSO с правильной ссылкой', async () => {
    fetchMock.mockImplementation(async () => json(config));
    renderLogin();
    const link = await screen.findByRole('link', { name: 'Войти через Keycloak' });
    expect(link).toHaveAttribute('href', '/api/v1/auth/oidc/start/keycloak');
  });
});

describe('safeNext', () => {
  it('не пускает на чужие сайты после входа', () => {
    expect(safeNext('/agent?tab=1')).toBe('/agent?tab=1');
    expect(safeNext('//evil.example')).toBe('/');
    expect(safeNext('/\\evil.example')).toBe('/');
    expect(safeNext('https://evil.example')).toBe('/');
    expect(safeNext(null)).toBe('/');
  });
});
