import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { AuthProvider } from '../auth';
import { resetCsrf } from '../api/client';
import { Login } from './Login';
import { clearCookies, jsonResponse, mockFetch, setCookie } from '../test/utils';

function renderLogin() {
  return render(
    <MemoryRouter initialEntries={['/login']}>
      <AuthProvider>
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route path="/dashboard" element={<div>Dashboard page</div>} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  );
}

describe('Login form', () => {
  beforeEach(() => { resetCsrf(); clearCookies(); });
  afterEach(() => vi.unstubAllGlobals());

  it('posts credentials with CSRF, refreshes the token and navigates', async () => {
    let csrfCalls = 0;
    const f = mockFetch((url, init) => {
      if (url === '/api/auth/me') return jsonResponse({ success: false, error: { code: 'UNAUTHORIZED', message: 'no' } }, 401);
      if (url === '/api/auth/csrf') {
        csrfCalls++;
        setCookie(`XSRF-TOKEN=t${csrfCalls}; path=/`);
        return jsonResponse({ success: true, data: { token: `t${csrfCalls}` } });
      }
      if (url === '/api/auth/login' && init.method === 'POST') {
        return jsonResponse({ success: true, data: { id: 'u1', email: 'a@b.co', fullName: 'A', role: 'USER' } });
      }
      return undefined;
    });
    renderLogin();
    await userEvent.type(screen.getByLabelText('Email'), 'a@b.co');
    await userEvent.type(screen.getByLabelText('Password'), 'correct horse battery');
    await userEvent.click(screen.getByRole('button', { name: 'Log in' }));

    expect(await screen.findByText('Dashboard page')).toBeInTheDocument();
    const loginCall = f.mock.calls.find(([u]) => u === '/api/auth/login')!;
    expect(JSON.parse(loginCall[1]!.body as string)).toEqual({ email: 'a@b.co', password: 'correct horse battery' });
    expect((loginCall[1]!.headers as Record<string, string>)['X-CSRF-TOKEN']).toBe('t1');
    await waitFor(() => expect(csrfCalls).toBe(2));
  });

  it('shows the error code and message on failure', async () => {
    setCookie('XSRF-TOKEN=t; path=/');
    mockFetch((url) => {
      if (url === '/api/auth/me') return jsonResponse({ success: false, error: { code: 'UNAUTHORIZED', message: 'no' } }, 401);
      if (url === '/api/auth/login') return jsonResponse({ success: false, error: { code: 'INVALID_CREDENTIALS', message: 'Email or password is incorrect' } }, 401);
      return undefined;
    });
    renderLogin();
    await userEvent.type(screen.getByLabelText('Email'), 'a@b.co');
    await userEvent.type(screen.getByLabelText('Password'), 'wrong');
    await userEvent.click(screen.getByRole('button', { name: 'Log in' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('INVALID_CREDENTIALS: Email or password is incorrect');
  });
});
