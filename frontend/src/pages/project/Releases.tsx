import { useState, type FormEvent } from 'react';
import { releases } from '../../api/endpoints';
import { ENVIRONMENTS, type EnvSlug } from '../../api/types';
import { Card, ConfirmDialog, Empty, ErrorBox, PageHeader, Spinner } from '../../components/ui';
import { fmtDate } from '../../components/format';
import { useAction, useAsync } from '../../hooks';
import { useProject } from './context';

type Pending = { kind: 'deploy'; env: EnvSlug; version: number } | { kind: 'rollback'; env: EnvSlug };

export function Releases() {
  const { project } = useProject();
  const data = useAsync(async () => {
    const [list, history, active] = await Promise.all([releases.list(project.id), releases.deployments(project.id), releases.active(project.id)]);
    return { list: [...list].sort((a, b) => b.version - a.version), history, active };
  }, [project.id]);
  const [notes, setNotes] = useState('');
  const [deployEnv, setDeployEnv] = useState<Record<number, EnvSlug>>({});
  const [pending, setPending] = useState<Pending | null>(null);
  const [snapshot, setSnapshot] = useState<number | null>(null);
  const create = useAction();
  const act = useAction();

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const r = await create.run(() => releases.create(project.id, notes));
    if (r) { setNotes(''); data.reload(); }
  };

  if (data.loading && !data.data) return <Spinner />;
  if (data.error || !data.data) return <ErrorBox error={data.error} onRetry={data.reload} />;
  const { list, history, active } = data.data;
  const shown = list.find((r) => r.version === snapshot);

  return (
    <>
      <PageHeader title="Releases & deployments" subtitle="A release snapshots the current schema and custom-API versions." />
      <Card title="Active deployments">
        <div className="grid grid-3">
          {ENVIRONMENTS.map((env) => {
            const a = active.find((x) => x.environment === env);
            return (
              <div key={env} className="env-tile">
                <span className={`badge env-${env}`}>{env}</span>
                <strong>{a?.releaseVersion != null ? `Release v${a.releaseVersion}` : 'Nothing deployed'}</strong>
                <span className="muted small">{fmtDate(a?.deployedAt)}</span>
                <button className="btn btn-sm" onClick={() => { act.setError(null); setPending({ kind: 'rollback', env }); }}>Roll back</button>
              </div>
            );
          })}
        </div>
      </Card>
      <Card title="Create release">
        <form className="row wrap" onSubmit={submit}>
          <input className="grow" aria-label="Release notes" placeholder="Release notes" value={notes} onChange={(e) => setNotes(e.target.value)} />
          <button className="btn btn-primary" disabled={create.busy}>{create.busy ? 'Creating…' : 'Create release'}</button>
        </form>
        <ErrorBox error={create.error} />
      </Card>
      <Card title="Releases">
        {!list.length ? <Empty title="No releases yet" /> : (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Version</th><th>Notes</th><th>Created</th><th>Deploy</th><th /></tr></thead>
              <tbody>
                {list.map((r) => (
                  <tr key={r.id}>
                    <td>v{r.version}</td>
                    <td>{r.notes || <span className="muted">—</span>}</td>
                    <td>{fmtDate(r.createdAt)}</td>
                    <td className="nowrap">
                      <select aria-label={`Deploy v${r.version} to`} value={deployEnv[r.version] ?? 'sandbox'}
                        onChange={(e) => setDeployEnv({ ...deployEnv, [r.version]: e.target.value as EnvSlug })}>
                        {ENVIRONMENTS.map((e) => <option key={e}>{e}</option>)}
                      </select>
                      <button className="btn btn-sm btn-primary" onClick={() => { act.setError(null); setPending({ kind: 'deploy', version: r.version, env: deployEnv[r.version] ?? 'sandbox' }); }}>Deploy</button>
                    </td>
                    <td className="right"><button className="btn btn-sm btn-ghost" onClick={() => setSnapshot(snapshot === r.version ? null : r.version)}>{snapshot === r.version ? 'Hide' : 'Snapshot'}</button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {shown && <pre className="code">{JSON.stringify(shown.snapshot, null, 2)}</pre>}
      </Card>
      <Card title="Deployment history">
        {!history.length ? <Empty title="No deployments yet" /> : (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>When</th><th>Environment</th><th>Action</th><th>Release</th></tr></thead>
              <tbody>
                {history.map((d) => (
                  <tr key={d.id}>
                    <td>{fmtDate(d.createdAt)}</td>
                    <td><span className={`badge env-${d.environment}`}>{d.environment}</span></td>
                    <td>{d.action}</td>
                    <td>v{d.releaseVersion}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
      <ConfirmDialog open={!!pending} danger={pending?.env === 'production'}
        title={pending?.kind === 'deploy' ? `Deploy v${pending.version} to ${pending.env}` : `Roll back ${pending?.env}`}
        confirmLabel={pending?.kind === 'deploy' ? 'Deploy' : 'Roll back'}
        message={pending?.kind === 'deploy'
          ? <>Custom APIs in <strong>{pending.env}</strong> will use the versions pinned by release v{pending.version}.</>
          : <>Re-deploy the release that was active in <strong>{pending?.env}</strong> before the current one.</>}
        busy={act.busy} error={act.error} onCancel={() => setPending(null)}
        onConfirm={async () => {
          if (!pending) return;
          const p = pending;
          const ok = await act.run(async () => {
            if (p.kind === 'deploy') await releases.deploy(project.id, p.env, p.version);
            else await releases.rollback(project.id, p.env);
            return true;
          });
          if (ok) { setPending(null); data.reload(); }
        }} />
    </>
  );
}
