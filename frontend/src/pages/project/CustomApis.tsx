import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { customApis } from '../../api/endpoints';
import type { HttpMethod } from '../../api/types';
import { Card, Empty, ErrorBox, PageHeader, Spinner } from '../../components/ui';
import { fmtDate, slugify } from '../../components/format';
import { useAction, useAsync } from '../../hooks';
import { useProject } from './context';
import { EXAMPLE_TEXT, parseJson } from './customApiTemplate';

export function CustomApis() {
  const { project, to } = useProject();
  const list = useAsync(() => customApis.list(project.id), [project.id]);
  const [show, setShow] = useState(false);
  const [name, setName] = useState('');
  const [slug, setSlug] = useState('');
  const [slugTouched, setSlugTouched] = useState(false);
  const [description, setDescription] = useState('');
  const [method, setMethod] = useState<HttpMethod>('POST');
  const [definition, setDefinition] = useState(EXAMPLE_TEXT);
  const create = useAction();
  const nav = useNavigate();

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const api = await create.run(() => customApis.create(project.id, {
      name, slug, description: description || undefined, method, definition: parseJson(definition, 'Definition'),
    }));
    if (api) nav(to(`custom-apis/${api.slug}`));
  };

  return (
    <>
      <PageHeader title="Custom APIs" subtitle="Declarative workflows exposed at /api/v1/projects/{id}/functions/{slug}."
        actions={<button className="btn btn-primary" onClick={() => setShow((s) => !s)}>{show ? 'Cancel' : 'New custom API'}</button>} />
      {show && (
        <Card title="Create custom API">
          <form className="form" onSubmit={submit}>
            <div className="grid grid-3">
              <label>Name<input required value={name} onChange={(e) => { setName(e.target.value); if (!slugTouched) setSlug(slugify(e.target.value)); }} /></label>
              <label>Slug<input required pattern="[a-z0-9]+(-[a-z0-9]+)*" value={slug} onChange={(e) => { setSlugTouched(true); setSlug(e.target.value); }} /></label>
              <label>Method<select value={method} onChange={(e) => setMethod(e.target.value as HttpMethod)}><option>POST</option><option>GET</option></select></label>
            </div>
            <label>Description<input value={description} onChange={(e) => setDescription(e.target.value)} /></label>
            <label>Definition (JSON)<textarea className="mono" rows={16} value={definition} onChange={(e) => setDefinition(e.target.value)} /></label>
            <div className="row"><button type="button" className="btn btn-sm btn-ghost" onClick={() => setDefinition(EXAMPLE_TEXT)}>Insert example template</button></div>
            <ErrorBox error={create.error} />
            <div><button className="btn btn-primary" disabled={create.busy}>{create.busy ? 'Creating…' : 'Create'}</button></div>
          </form>
        </Card>
      )}
      <Card>
        {list.loading ? <Spinner /> : list.error ? <ErrorBox error={list.error} onRetry={list.reload} /> :
          !list.data?.length ? <Empty title="No custom APIs yet" /> : (
            <div className="table-wrap">
              <table className="table">
                <thead><tr><th>Name</th><th>Slug</th><th>Method</th><th>Version</th><th>Updated</th></tr></thead>
                <tbody>
                  {list.data.map((a) => (
                    <tr key={a.id}>
                      <td><Link to={to(`custom-apis/${a.slug}`)}>{a.name}</Link></td>
                      <td><code>{a.slug}</code></td>
                      <td>{a.method}</td>
                      <td>v{a.currentVersion}</td>
                      <td>{fmtDate(a.updatedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
      </Card>
    </>
  );
}
