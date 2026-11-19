import { Link } from 'react-router-dom';
import { useAuth } from '../auth';

const FEATURES = [
  ['Schema-first resources', 'Define typed fields and publish immutable schema versions. Relay generates CRUD endpoints instantly.'],
  ['Three environments', 'Every project ships with sandbox, development and production, each with isolated data and keys.'],
  ['Custom APIs', 'Compose declarative workflows from list, get, create, update and delete steps. No user code runs.'],
  ['Releases and rollbacks', 'Snapshot schemas and custom APIs into releases, deploy per environment, and roll back safely.'],
  ['Module catalog', 'Install ready-made resources and APIs into a project in one atomic step.'],
  ['Quotas and usage', 'Per-project rate limits, daily quotas and a full JSON export for handoff.'],
];

export function Landing() {
  const { user } = useAuth();
  return (
    <main className="container landing">
      <section className="hero">
        <h1>Backend APIs, without the backend.</h1>
        <p className="lead muted">
          Relay turns a schema into a secure, versioned REST API with environments, API keys, releases and usage limits.
        </p>
        <div className="row">
          {user ? (
            <Link to="/dashboard" className="btn btn-primary">Go to dashboard</Link>
          ) : (
            <>
              <Link to="/register" className="btn btn-primary">Create an account</Link>
              <Link to="/login" className="btn">Log in</Link>
            </>
          )}
        </div>
        <pre className="code hero-code">{`curl -H "Authorization: Bearer relay_..." \\
  /api/v1/projects/{projectId}/resources/todos`}</pre>
      </section>
      <section className="grid grid-3">
        {FEATURES.map(([t, d]) => (
          <div className="card feature" key={t}><h3>{t}</h3><p className="muted">{d}</p></div>
        ))}
      </section>
    </main>
  );
}
