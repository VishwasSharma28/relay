import { useState } from 'react';
import { modules } from '../../api/endpoints';
import type { ModuleDef } from '../../api/types';
import { Card, ConfirmDialog, Empty, ErrorBox, PageHeader, Spinner } from '../../components/ui';
import { fmtDate } from '../../components/format';
import { useAction, useAsync } from '../../hooks';
import { useProject } from './context';

export function Modules() {
  const { project } = useProject();
  const data = useAsync(async () => {
    const [catalog, installed] = await Promise.all([modules.catalog(), modules.installed(project.id)]);
    return { catalog, installed };
  }, [project.id]);
  const [pending, setPending] = useState<ModuleDef | null>(null);
  const install = useAction();

  return (
    <>
      <PageHeader title="Module catalog" subtitle="Install ready-made resources and custom APIs. Installation is atomic." />
      {data.loading ? <Spinner /> : data.error ? <ErrorBox error={data.error} onRetry={data.reload} /> :
        !data.data?.catalog.length ? <Empty title="No modules available" /> : (
          <div className="grid grid-2">
            {data.data.catalog.map((m) => {
              const inst = data.data!.installed.find((i) => i.moduleKey === m.key);
              return (
                <Card key={m.key} title={<>{m.name} <span className="muted small">v{m.version}</span></>}
                  actions={inst ? <span className="badge status-active">installed</span> :
                    <button className="btn btn-sm btn-primary" onClick={() => { install.setError(null); setPending(m); }}>Install</button>}>
                  <p className="muted">{m.description}</p>
                  <div className="small"><strong>Resources:</strong> {m.resources.map((r) => r.slug).join(', ') || '—'}</div>
                  <div className="small"><strong>Custom APIs:</strong> {m.customApis.map((a) => `${a.method} ${a.slug}`).join(', ') || '—'}</div>
                  {inst && <div className="muted small">Installed v{inst.moduleVersion} on {fmtDate(inst.installedAt)}</div>}
                </Card>
              );
            })}
          </div>
        )}
      <ConfirmDialog open={!!pending} title={`Install ${pending?.name ?? ''}`} confirmLabel="Install"
        message={<>This creates resources ({pending?.resources.map((r) => r.slug).join(', ') || 'none'}) and custom APIs ({pending?.customApis.map((a) => a.slug).join(', ') || 'none'}) in this project.</>}
        busy={install.busy} error={install.error} onCancel={() => setPending(null)}
        onConfirm={async () => {
          if (!pending) return;
          const ok = await install.run(async () => { await modules.install(project.id, pending.key); return true; });
          if (ok) { setPending(null); data.reload(); }
        }} />
    </>
  );
}
