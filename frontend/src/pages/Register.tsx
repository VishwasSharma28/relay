import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { auth } from '../api/endpoints';
import { useAuth } from '../auth';
import { ErrorBox } from '../components/ui';
import { useAction } from '../hooks';

export function Register() {
  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const { setUser } = useAuth();
  const nav = useNavigate();
  const { busy, error, run } = useAction();

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const user = await run(async () => {
      await auth.register({ email, password, fullName });
      return auth.login({ email, password });
    });
    if (user) { setUser(user); nav('/dashboard', { replace: true }); }
  };

  return (
    <main className="container narrow">
      <form className="card form" onSubmit={submit} aria-label="Create account">
        <h1>Create your account</h1>
        <label>Full name<input required maxLength={150} value={fullName} onChange={(e) => setFullName(e.target.value)} /></label>
        <label>Email<input type="email" required autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} /></label>
        <label>Password<input type="password" required minLength={12} maxLength={72} autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} />
          <span className="hint">12 to 72 characters.</span></label>
        <ErrorBox error={error} />
        <button className="btn btn-primary" type="submit" disabled={busy}>{busy ? 'Creating…' : 'Create account'}</button>
        <p className="muted">Already have an account? <Link to="/login">Log in</Link></p>
      </form>
    </main>
  );
}
