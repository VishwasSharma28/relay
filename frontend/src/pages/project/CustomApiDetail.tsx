import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { customApis } from '../../api/endpoints';
import type { HttpMethod, RunResult } from '../../api/types';
import { Card, ConfirmDialog, CopyButton, ErrorBox, PageHeader, Spinner } from '../../components/ui';
import { fmtDate } from '../../components/format';
import { useAction, useAsync } from '../../hooks';
import { useProject } from './context';
import { EXAMPLE_TEXT, curlSnippet, parseJson } from './customApiTemplate';

export function CustomApiDetail() {
  const { slug = '' } = useParams();
  const { project, env, to } = useProject();
  const nav = useNavigate();
  const data = useAsync(async () => {
    const [api, versions] = await Promise.all([customApis.get(project.id, slug), customApis.versions(project.id, slug)]);
    return { api, versions: [...versions].sort((a, b) => b.version - a.version) };
  }, [project.id, slug]);
  const [method, setMethod] = useState<HttpMethod>('POST');
  const [definition, setDefinition] = useState('');
  const [input, setInput] = useState('{\n  "title": "Hello"\n}');
  const [result, setResult] = useState<RunResult | null>(null);
  const [viewVersion, setViewVersion] = useState<number | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const publish = useAction();
  const run = useAction();
  const del = useAction();

  const api = data.data?.api;
  useEffect(() => {
    if (api) { setMethod(api.method); setDefinition(JSON.stringify(api.definition, null, 2)); }
  }, [api]);

  if (data.loading && !data.data) return <Spinner />;
  if (data.error || !data.data || !api) return <ErrorBox error={data.error} onRetry={data.reload} />;
  const viewing = data.data.versions.find((v) => v.version === viewVersion);
  const curl = curlSnippet(window.location.origin, project.id, api.slug, api.method, input);

  return (
    <>
      <PageHeader title={api.name} subtitle={<><code>{api.slug}</code> · {api.method} · current v{api.currentVersion}{api.description ? ` · ${api.description}` : ''}</>}
        actions={<button className="btn btn-danger" onClick={() => setConfirmDelete(true)}>Delete</button>} />
      <div className="grid grid-2">
        <Card title="Definition" actions={<button className="btn btn-sm btn-ghost" onClick={() => setDefinition(EXAMPLE_TEXT)}>Example template</button>}>
          <label className="inline">Method<select value={method} onChange={(e) => setMethod(e.target.value as HttpMethod)}><option>POST</option><option>GET</option></select></label>
          <textarea className="mono" rows={20} aria-label="Definition JSON" value={definition} onChange={(e) => setDefinition(e.target.value)} />
          <ErrorBox error={publish.error} />
          {notice && <div className="alert alert-success">{notice}</div>}
          <button className="btn btn-primary" disabled={publish.busy} onClick={async () => {
            setNotice(null);
            const v = await publish.run(() => customApis.publish(project.id, slug, { method, definition: parseJson(definition, 'Definition') }));
            if (v) { setNotice(`Published v${v.version}`); data.reload(); }
          }}>{publish.busy ? 'Publishing…' : 'Publish new version'}</button>
        </Card>
        <div className="stack">
          <Card title={<>Test run in <span className={`badge env-${env}`}>{env}</span></>}>
            <p className="muted small">Uses the deployed version in this environment; sandbox falls back to the latest version.</p>
            <textarea className="mono" rows={6} aria-label="Run input JSON" value={input} onChange={(e) => setInput(e.target.value)} />
            <ErrorBox error={run.error} />
            <button className="btn btn-primary" disabled={run.busy} onClick={async () => {
              setResult(null);
              const r = await run.run(() => customApis.run(project.id, env, slug, parseJson(input, 'Input')));
              if (r) setResult(r);
            }}>{run.busy ? 'Running…' : 'Run'}</button>
            {result && (
              <div className="run-result">
                <div>Status <span className={`badge ${result.status < 400 ? 'status-active' : 'status-revoked'}`}>{result.status}</span></div>
                <ul className="steps">
                  {result.steps.map((s) => <li key={s.id}><span className={`badge ${s.ok ? 'status-active' : 'status-revoked'}`}>{s.ok ? 'ok' : 'failed'}</span> {s.id} <span className="muted">({s.type})</span></li>)}
                </ul>
                <pre className="code">{JSON.stringify(result.body, null, 2)}</pre>
              </div>
            )}
          </Card>
          <Card title="Call it publicly" actions={<CopyButton text={curl} />}>
            <pre className="code">{curl}</pre>
            <p className="muted small">Use an API key for the target environment. GET functions take input from query parameters.</p>
          </Card>
        </div>
      </div>
      <Card title="Versions">
        <div className="table-wrap">
          <table className="table">
            <thead><tr><th>Version</th><th>Method</th><th>Published</th><th /></tr></thead>
            <tbody>
              {data.data.versions.map((v) => (
                <tr key={v.id}>
                  <td>v{v.version}</td><td>{v.method}</td><td>{fmtDate(v.createdAt)}</td>
                  <td className="right">
                    <button className="btn btn-sm" onClick={() => setViewVersion(viewVersion === v.version ? null : v.version)}>{viewVersion === v.version ? 'Hide' : 'View'}</button>
                    <button className="btn btn-sm btn-ghost" onClick={() => { setMethod(v.method); setDefinition(JSON.stringify(v.definition, null, 2)); }}>Load into editor</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {viewing && <pre className="code">{JSON.stringify(viewing.definition, null, 2)}</pre>}
      </Card>
      <ConfirmDialog open={confirmDelete} danger title="Delete custom API" confirmLabel="Delete"
        message={<>Delete <strong>{api.name}</strong> and all its versions? This cannot be undone.</>}
        busy={del.busy} error={del.error} onCancel={() => setConfirmDelete(false)}
        onConfirm={async () => {
          const ok = await del.run(async () => { await customApis.remove(project.id, slug); return true; });
          if (ok) nav(to('custom-apis'));
        }} />
    </>
  );
}
