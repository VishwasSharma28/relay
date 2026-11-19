import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { projects } from '../api/endpoints';
import { Card, Empty, ErrorBox, PageHeader, Spinner } from '../components/ui';
import { fmtDate, slugify } from '../components/format';
import { useAction, useAsync } from '../hooks';

export function Dashboard() {
  const list = useAsync(() => projects.list(), []);
  const [name, setName] = useState('');
  const [slug, setSlug] = useState('');
  const [slugTouched, setSlugTouched] = useState(false);
  const [description, setDescription] = useState('');
  const create = useAction();
  const nav = useNavigate();

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const p = await create.run(() => projects.create({ name, slug, description: description || undefined }));
    if (p) nav(`/projects/${p.id}`);
  };

  return (
    <main className="container">
      <PageHeader title="Projects" subtitle="Each project gets sandbox, development and production environments." />
      <div className="grid grid-2-1">
        <Card title="Your projects">
          {list.loading ? <Spinner /> : list.error ? <ErrorBox error={list.error} onRetry={list.reload} /> :
            !list.data?.length ? <Empty title="No projects yet">Create your first project to get started.</Empty> : (
              <ul className="list">
                {list.data.map((p) => (
                  <li key={p.id}>
                    <Link to={`/projects/${p.id}`} className="list-link">
                      <strong>{p.name}</strong> <code className="muted">{p.slug}</code>
                      {p.description && <div className="muted small">{p.description}</div>}
                      <div className="muted small">Created {fmtDate(p.createdAt)}</div>
                    </Link>
                  </li>
                ))}
              </ul>
            )}
        </Card>
        <Card title="New project">
          <form className="form" onSubmit={submit}>
            <label>Name<input required maxLength={150} value={name} onChange={(e) => { setName(e.target.value); if (!slugTouched) setSlug(slugify(e.target.value)); }} /></label>
            <label>Slug<input required maxLength={100} pattern="[a-z0-9]+(-[a-z0-9]+)*" value={slug} onChange={(e) => { setSlugTouched(true); setSlug(e.target.value); }} /></label>
            <label>Description<textarea rows={3} value={description} onChange={(e) => setDescription(e.target.value)} /></label>
            <ErrorBox error={create.error} />
            <button className="btn btn-primary" disabled={create.busy}>{create.busy ? 'Creating…' : 'Create project'}</button>
          </form>
        </Card>
      </div>
    </main>
  );
}
