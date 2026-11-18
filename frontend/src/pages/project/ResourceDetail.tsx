import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { resources } from '../../api/endpoints';
import { FieldEditor } from '../../components/FieldEditor';
import { fieldsToSchema, schemaToFields, type FieldRow } from '../../components/schema';
import { Card, ConfirmDialog, ErrorBox, PageHeader, Spinner } from '../../components/ui';
import { fmtDate } from '../../components/format';
import { useAction, useAsync } from '../../hooks';
import { useProject } from './context';

export function ResourceDetail() {
  const { slug = '' } = useParams();
  const { project, to } = useProject();
  const nav = useNavigate();
  const data = useAsync(async () => {
    const [resource, versions] = await Promise.all([resources.get(project.id, slug), resources.versions(project.id, slug)]);
    return { resource, versions: [...versions].sort((a, b) => b.version - a.version) };
  }, [project.id, slug]);
  const [fields, setFields] = useState<FieldRow[]>([]);
  const [selected, setSelected] = useState<number | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const publish = useAction();
  const del = useAction();
  const [notice, setNotice] = useState<string | null>(null);

  const latest = data.data?.versions[0];
  useEffect(() => { setFields(schemaToFields(latest?.schemaDefinition)); }, [latest]);

  if (data.loading && !data.data) return <Spinner />;
  if (data.error || !data.data) return <ErrorBox error={data.error} onRetry={data.reload} />;
  const { resource, versions } = data.data;
  const viewing = versions.find((v) => v.version === selected);

  const onPublish = async () => {
    setNotice(null);
    const v = await publish.run(() => resources.publish(project.id, slug, fieldsToSchema(fields)));
    if (v) { setNotice(`Published schema v${v.version}`); data.reload(); }
  };

  return (
    <>
      <PageHeader title={resource.name}
        subtitle={<><code>{resource.slug}</code> · current schema v{resource.currentSchemaVersion ?? '—'}{resource.description ? ` · ${resource.description}` : ''}</>}
        actions={<>
          <Link className="btn" to={to(`records/${resource.slug}`)}>Records</Link>
          <button className="btn btn-danger" onClick={() => setConfirmDelete(true)}>Delete</button>
        </>} />
      <Card title="Schema editor" actions={<span className="muted small">Publishing creates a new immutable version</span>}>
        <FieldEditor fields={fields} onChange={setFields} />
        <ErrorBox error={publish.error} />
        {notice && <div className="alert alert-success">{notice}</div>}
        <div className="row">
          <button className="btn btn-primary" onClick={onPublish} disabled={publish.busy}>{publish.busy ? 'Publishing…' : 'Publish new version'}</button>
          <button className="btn btn-ghost" onClick={() => setFields(schemaToFields(latest?.schemaDefinition))}>Reset to latest</button>
        </div>
      </Card>
      <Card title="Version history">
        <div className="table-wrap">
          <table className="table">
            <thead><tr><th>Version</th><th>Fields</th><th>Published</th><th /></tr></thead>
            <tbody>
              {versions.map((v) => (
                <tr key={v.id}>
                  <td>v{v.version}</td>
                  <td>{Object.keys(v.schemaDefinition?.properties ?? {}).join(', ') || '—'}</td>
                  <td>{fmtDate(v.createdAt)}</td>
                  <td className="right">
                    <button className="btn btn-sm" onClick={() => setSelected(selected === v.version ? null : v.version)}>
                      {selected === v.version ? 'Hide' : 'View'}
                    </button>
                    <button className="btn btn-sm btn-ghost" onClick={() => setFields(schemaToFields(v.schemaDefinition))}>Load into editor</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {viewing && <pre className="code">{JSON.stringify(viewing.schemaDefinition, null, 2)}</pre>}
      </Card>
      <ConfirmDialog open={confirmDelete} danger title="Delete resource"
        message={<>Delete <strong>{resource.name}</strong> and all of its records in every environment? This cannot be undone.</>}
        confirmLabel="Delete resource" busy={del.busy} error={del.error}
        onCancel={() => setConfirmDelete(false)}
        onConfirm={async () => {
          const ok = await del.run(async () => { await resources.remove(project.id, slug); return true; });
          if (ok) nav(to('resources'));
        }} />
    </>
  );
}
