import { useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { auth } from '../api/endpoints';
import { useAuth } from '../auth';
import { ErrorBox } from '../components/ui';
import { useAction } from '../hooks';

export function Login() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const { setUser } = useAuth();
  const nav = useNavigate();
  const loc = useLocation();
  const { busy, error, run } = useAction();
  const from = (loc.state as { from?: string } | null)?.from ?? '/dashboard';

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const user = await run(() => auth.login({ email, password }));
    if (user) { setUser(user); nav(from, { replace: true }); }
  };

  return (
    <main className="container narrow">
      <form className="card form" onSubmit={submit} aria-label="Log in">
        <h1>Log in</h1>
        <label>Email<input type="email" required autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} /></label>
        <label>Password<input type="password" required autoComplete="current-password" maxLength={72} value={password} onChange={(e) => setPassword(e.target.value)} /></label>
        <ErrorBox error={error} />
        <button className="btn btn-primary" type="submit" disabled={busy}>{busy ? 'Logging in…' : 'Log in'}</button>
        <p className="muted">No account? <Link to="/register">Sign up</Link></p>
      </form>
    </main>
  );
}
