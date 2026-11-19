import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { records, resources, type RecordQuery } from '../../api/endpoints';
import type { RecordItem } from '../../api/types';
import { Card, ConfirmDialog, Empty, ErrorBox, PageHeader, Spinner } from '../../components/ui';
import { fmtDate } from '../../components/format';
import { useAction, useAsync } from '../../hooks';
import { useProject } from './context';
import { parseObject, toJsonLiteral } from './recordUtils';

interface FilterRow { field: string; value: string }

export function Records() {
  const { slug } = useParams();
  const { project, env, to } = useProject();
  const nav = useNavigate();
  const resList = useAsync(() => resources.list(project.id), [project.id]);

  useEffect(() => {
    if (!slug && resList.data?.length) nav(to(`records/${resList.data[0].slug}`), { replace: true });
  }, [slug, resList.data, nav, to]);

  return (
    <>
      <PageHeader title="Records" subtitle={`Browsing ${env} data`}
        actions={resList.data?.length ? (
          <select aria-label="Resource" value={slug ?? ''} onChange={(e) => nav(to(`records/${e.target.value}`))}>
            {resList.data.map((r) => <option key={r.id} value={r.slug}>{r.name}</option>)}
          </select>
        ) : null} />
      {resList.loading ? <Spinner /> : resList.error ? <ErrorBox error={resList.error} onRetry={resList.reload} /> :
        !resList.data?.length ? <Empty title="No resources">Create a resource before adding records.</Empty> :
          slug ? <RecordsExplorer key={`${slug}-${env}`} projectId={project.id} slug={slug} /> : <Spinner />}
    </>
  );
}

export function RecordsExplorer({ projectId, slug }: { projectId: string; slug: string }) {
  const { env } = useProject();
  const [limit, setLimit] = useState(25);
  const [sort, setSort] = useState('createdAt');
  const [order, setOrder] = useState<'asc' | 'desc'>('desc');
  const [filterRows, setFilterRows] = useState<FilterRow[]>([]);
  const [applied, setApplied] = useState<Record<string, string>>({});
  const [cursors, setCursors] = useState<(string | null)[]>([null]);
  const cursor = cursors[cursors.length - 1];

  const query: RecordQuery = { limit, sort, order, cursor, filters: applied };
  const page = useAsync(() => records.list(projectId, env, slug, query),
    [projectId, env, slug, limit, sort, order, cursor, JSON.stringify(applied)]);

  const [createText, setCreateText] = useState('{\n  \n}');
  const [showCreate, setShowCreate] = useState(false);
  const [editing, setEditing] = useState<RecordItem | null>(null);
  const [editText, setEditText] = useState('');
  const [deleting, setDeleting] = useState<RecordItem | null>(null);
  const create = useAction();
  const edit = useAction();
  const del = useAction();

  const resetPaging = () => setCursors([null]);
  const applyFilters = () => {
    const f: Record<string, string> = {};
    for (const r of filterRows) if (r.field.trim() && r.value.trim() !== '') f[r.field.trim()] = toJsonLiteral(r.value);
    setApplied(f);
    resetPaging();
  };

  return (
    <>
      <Card title="Query" actions={<button className="btn btn-sm btn-primary" onClick={() => setShowCreate((s) => !s)}>{showCreate ? 'Cancel' : 'New record'}</button>}>
        <div className="row wrap">
          <label className="inline">Sort<input value={sort} onChange={(e) => { setSort(e.target.value); resetPaging(); }} /></label>
          <label className="inline">Order
            <select value={order} onChange={(e) => { setOrder(e.target.value as 'asc' | 'desc'); resetPaging(); }}>
              <option value="desc">desc</option><option value="asc">asc</option>
            </select>
          </label>
          <label className="inline">Limit
            <select value={limit} onChange={(e) => { setLimit(Number(e.target.value)); resetPaging(); }}>
              {[10, 25, 50, 100].map((n) => <option key={n} value={n}>{n}</option>)}
            </select>
          </label>
        </div>
        <div className="filters">
          {filterRows.map((r, i) => (
            <div className="row" key={i}>
              <input aria-label={`Filter ${i + 1} field`} placeholder="field" value={r.field}
                onChange={(e) => setFilterRows(filterRows.map((x, j) => j === i ? { ...x, field: e.target.value } : x))} />
              <span className="muted">=</span>
              <input aria-label={`Filter ${i + 1} value`} placeholder='JSON value, e.g. true or "abc"' value={r.value}
                onChange={(e) => setFilterRows(filterRows.map((x, j) => j === i ? { ...x, value: e.target.value } : x))} />
              <button className="btn btn-sm btn-ghost" onClick={() => setFilterRows(filterRows.filter((_, j) => j !== i))}>Remove</button>
            </div>
          ))}
          <div className="row">
            <button className="btn btn-sm" onClick={() => setFilterRows([...filterRows, { field: '', value: '' }])}>+ Filter</button>
            <button className="btn btn-sm btn-primary" onClick={applyFilters}>Apply</button>
          </div>
        </div>
      </Card>

      {showCreate && (
        <Card title="Create record">
          <textarea className="mono" rows={8} aria-label="New record JSON" value={createText} onChange={(e) => setCreateText(e.target.value)} />
          <ErrorBox error={create.error} />
          <button className="btn btn-primary" disabled={create.busy} onClick={async () => {
            const r = await create.run(() => records.create(projectId, env, slug, parseObject(createText)));
            if (r) { setShowCreate(false); setCreateText('{\n  \n}'); resetPaging(); page.reload(); }
          }}>{create.busy ? 'Saving…' : 'Create'}</button>
        </Card>
      )}

      {editing && (
        <Card title={<>Edit record <code>{editing.id}</code></>}>
          <p className="muted small">Sent as a JSON merge patch with <code>If-Match: {editing.etag}</code>. Set a field to <code>null</code> to remove it.</p>
          <textarea className="mono" rows={10} aria-label="Edit record JSON" value={editText} onChange={(e) => setEditText(e.target.value)} />
          <ErrorBox error={edit.error} />
          <div className="row">
            <button className="btn btn-primary" disabled={edit.busy} onClick={async () => {
              const r = await edit.run(() => records.patch(projectId, env, slug, editing.id, editing.etag, parseObject(editText)));
              if (r) { setEditing(null); page.reload(); }
            }}>{edit.busy ? 'Saving…' : 'Save'}</button>
            <button className="btn" onClick={() => setEditing(null)}>Cancel</button>
          </div>
        </Card>
      )}

      <Card>
        {page.loading ? <Spinner /> : page.error ? <ErrorBox error={page.error} onRetry={page.reload} /> :
          !page.data?.data.length ? <Empty title="No records">Nothing matches in {env}.</Empty> : (
            <div className="table-wrap">
              <table className="table" aria-label="Records">
                <thead><tr><th>ID</th><th>Data</th><th>Schema</th><th>Updated</th><th /></tr></thead>
                <tbody>
                  {page.data.data.map((r) => (
                    <tr key={r.id}>
                      <td><code className="small">{r.id}</code></td>
                      <td><pre className="code inline-code">{JSON.stringify(r.data)}</pre></td>
                      <td>v{r.schemaVersion}</td>
                      <td>{fmtDate(r.updatedAt)}</td>
                      <td className="right nowrap">
                        <button className="btn btn-sm" onClick={() => { edit.setError(null); setEditing(r); setEditText(JSON.stringify(r.data, null, 2)); }}>Edit</button>
                        <button className="btn btn-sm btn-danger" onClick={() => { del.setError(null); setDeleting(r); }}>Delete</button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        <div className="row pager">
          <button className="btn btn-sm" disabled={cursors.length <= 1} onClick={() => setCursors(cursors.slice(0, -1))}>Previous</button>
          <span className="muted small">Page {cursors.length}</span>
          <button className="btn btn-sm" disabled={!page.data?.pagination.nextCursor}
            onClick={() => page.data?.pagination.nextCursor && setCursors([...cursors, page.data.pagination.nextCursor])}>Next</button>
        </div>
      </Card>

      <ConfirmDialog open={!!deleting} danger title="Delete record" confirmLabel="Delete"
        message={<>Delete record <code>{deleting?.id}</code> from {env}? This cannot be undone.</>}
        busy={del.busy} error={del.error} onCancel={() => setDeleting(null)}
        onConfirm={async () => {
          if (!deleting) return;
          const ok = await del.run(async () => { await records.remove(projectId, env, slug, deleting.id, deleting.etag); return true; });
          if (ok) { setDeleting(null); page.reload(); }
        }} />
    </>
  );
}
