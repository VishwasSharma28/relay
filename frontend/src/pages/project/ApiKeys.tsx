import { useState, type FormEvent } from 'react';
import { apiKeys } from '../../api/endpoints';
import { ENVIRONMENTS, type ApiKey, type ApiKeyCreated, type EnvSlug } from '../../api/types';
import { Card, ConfirmDialog, CopyButton, Empty, ErrorBox, PageHeader, Spinner } from '../../components/ui';
import { fmtDate } from '../../components/format';
import { useAction, useAsync } from '../../hooks';
import { useProject } from './context';

function keyStatus(k: ApiKey): string {
  if (k.revokedAt) return 'revoked';
  if (k.expiresAt && new Date(k.expiresAt).getTime() < Date.now()) return 'expired';
  return 'active';
}

export function ApiKeys() {
  const { project, env } = useProject();
  const list = useAsync(() => apiKeys.list(project.id), [project.id]);
  const [name, setName] = useState('');
  const [keyEnv, setKeyEnv] = useState<EnvSlug>(env);
  const [expires, setExpires] = useState('');
  const [created, setCreated] = useState<ApiKeyCreated | null>(null);
  const [revoking, setRevoking] = useState<ApiKey | null>(null);
  const create = useAction();
  const revoke = useAction();

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const res = await create.run(() => apiKeys.create(project.id, {
      name, environmentSlug: keyEnv, expiresAt: expires ? new Date(expires).toISOString() : undefined,
    }));
    if (res) { setCreated(res); setName(''); setExpires(''); list.reload(); }
  };

  return (
    <>
      <PageHeader title="API keys" subtitle="Keys authenticate calls to the public /api/v1 endpoints. A key's environment decides its data scope." />
      {created && (
        <div className="alert alert-success secret-box" role="status">
          <div><strong>Key “{created.apiKey.name}” created for {created.apiKey.environmentSlug}.</strong> Copy the secret now; it will not be shown again.</div>
          <div className="row"><code className="secret">{created.secret}</code><CopyButton text={created.secret} /></div>
          <button className="btn btn-sm" onClick={() => setCreated(null)}>I have saved it</button>
        </div>
      )}
      <Card title="Create key">
        <form className="row wrap" onSubmit={submit}>
          <label className="inline">Name<input required maxLength={100} value={name} onChange={(e) => setName(e.target.value)} /></label>
          <label className="inline">Environment
            <select value={keyEnv} onChange={(e) => setKeyEnv(e.target.value as EnvSlug)}>
              {ENVIRONMENTS.map((x) => <option key={x}>{x}</option>)}
            </select>
          </label>
          <label className="inline">Expires (optional)<input type="datetime-local" value={expires} onChange={(e) => setExpires(e.target.value)} /></label>
          <button className="btn btn-primary" disabled={create.busy}>{create.busy ? 'Creating…' : 'Create key'}</button>
        </form>
        <ErrorBox error={create.error} />
      </Card>
      <Card title="Keys">
        {list.loading ? <Spinner /> : list.error ? <ErrorBox error={list.error} onRetry={list.reload} /> :
          !list.data?.length ? <Empty title="No API keys yet" /> : (
            <div className="table-wrap">
              <table className="table">
                <thead><tr><th>Name</th><th>Prefix</th><th>Env</th><th>Status</th><th>Created</th><th>Last used</th><th>Expires</th><th /></tr></thead>
                <tbody>
                  {list.data.map((k) => {
                    const s = keyStatus(k);
                    return (
                      <tr key={k.id}>
                        <td>{k.name}</td>
                        <td><code>{k.keyPrefix}…</code></td>
                        <td><span className={`badge env-${k.environmentSlug}`}>{k.environmentSlug}</span></td>
                        <td><span className={`badge status-${s}`}>{s}</span></td>
                        <td>{fmtDate(k.createdAt)}</td>
                        <td>{fmtDate(k.lastUsedAt)}</td>
                        <td>{fmtDate(k.expiresAt)}</td>
                        <td className="right">{!k.revokedAt && <button className="btn btn-sm btn-danger" onClick={() => { revoke.setError(null); setRevoking(k); }}>Revoke</button>}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
      </Card>
      <ConfirmDialog open={!!revoking} danger title="Revoke API key" confirmLabel="Revoke"
        message={<>Revoke <strong>{revoking?.name}</strong>? Requests using it will fail immediately.</>}
        busy={revoke.busy} error={revoke.error} onCancel={() => setRevoking(null)}
        onConfirm={async () => {
          if (!revoking) return;
          const ok = await revoke.run(async () => { await apiKeys.revoke(project.id, revoking.id); return true; });
          if (ok) { setRevoking(null); list.reload(); }
        }} />
    </>
  );
}
