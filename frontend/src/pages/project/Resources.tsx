import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { resources } from '../../api/endpoints';
import { FieldEditor } from '../../components/FieldEditor';
import { fieldsToSchema, newField, type FieldRow } from '../../components/schema';
import { Card, Empty, ErrorBox, PageHeader, Spinner } from '../../components/ui';
import { fmtDate, slugify } from '../../components/format';
import { useAction, useAsync } from '../../hooks';
import { useProject } from './context';

export function Resources() {
  const { project, to } = useProject();
  const list = useAsync(() => resources.list(project.id), [project.id]);
  const [showForm, setShowForm] = useState(false);
  const [name, setName] = useState('');
  const [slug, setSlug] = useState('');
  const [slugTouched, setSlugTouched] = useState(false);
  const [description, setDescription] = useState('');
  const [fields, setFields] = useState<FieldRow[]>([newField({ name: 'title', required: true })]);
  const create = useAction();
  const nav = useNavigate();

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const r = await create.run(async () => {
      const schemaDefinition = fieldsToSchema(fields);
      return resources.create(project.id, { name, slug, description: description || undefined, schemaDefinition });
    });
    if (r) nav(to(`resources/${r.slug}`));
  };

  return (
    <>
      <PageHeader title="Resources" subtitle="Typed collections that back your generated REST API."
        actions={<button className="btn btn-primary" onClick={() => setShowForm((s) => !s)}>{showForm ? 'Cancel' : 'New resource'}</button>} />
      {showForm && (
        <Card title="Create resource">
          <form className="form" onSubmit={submit}>
            <div className="grid grid-2">
              <label>Name<input required maxLength={150} value={name} onChange={(e) => { setName(e.target.value); if (!slugTouched) setSlug(slugify(e.target.value)); }} /></label>
              <label>Slug<input required maxLength={100} pattern="[a-z0-9]+(-[a-z0-9]+)*" value={slug} onChange={(e) => { setSlugTouched(true); setSlug(e.target.value); }} /></label>
            </div>
            <label>Description<input value={description} onChange={(e) => setDescription(e.target.value)} /></label>
            <h3>Fields</h3>
            <FieldEditor fields={fields} onChange={setFields} />
            <ErrorBox error={create.error} />
            <div><button className="btn btn-primary" disabled={create.busy}>{create.busy ? 'Creating…' : 'Create resource'}</button></div>
          </form>
        </Card>
      )}
      <Card>
        {list.loading ? <Spinner /> : list.error ? <ErrorBox error={list.error} onRetry={list.reload} /> :
          !list.data?.length ? <Empty title="No resources yet">Create one or install a module.</Empty> : (
            <div className="table-wrap">
              <table className="table">
                <thead><tr><th>Name</th><th>Slug</th><th>Schema</th><th>Updated</th><th /></tr></thead>
                <tbody>
                  {list.data.map((r) => (
                    <tr key={r.id}>
                      <td><Link to={to(`resources/${r.slug}`)}>{r.name}</Link></td>
                      <td><code>{r.slug}</code></td>
                      <td>{r.currentSchemaVersion != null ? `v${r.currentSchemaVersion}` : '—'}</td>
                      <td>{fmtDate(r.updatedAt)}</td>
                      <td className="right"><Link className="btn btn-sm" to={to(`records/${r.slug}`)}>Records</Link></td>
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
