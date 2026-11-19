import { useEffect, useMemo, useState, type FormEvent } from 'react';
import { usage } from '../../api/endpoints';
import { ENVIRONMENTS, type EnvSlug } from '../../api/types';
import { Card, Empty, ErrorBox, PageHeader, Spinner } from '../../components/ui';
import { useAction, useAsync } from '../../hooks';
import { useProject } from './context';

export function UsagePage() {
  const { project } = useProject();
  const [days, setDays] = useState(30);
  const data = useAsync(() => usage.get(project.id, days), [project.id, days]);
  const [daily, setDaily] = useState('');
  const [rpm, setRpm] = useState('');
  const quota = useAction();
  const [saved, setSaved] = useState(false);
  const exp = useAction();
  const [includeRecords, setIncludeRecords] = useState(false);

  useEffect(() => {
    if (data.data) { setDaily(String(data.data.quota.dailyRequestLimit)); setRpm(String(data.data.quota.requestsPerMinute)); }
  }, [data.data]);

  const rows = useMemo(() => {
    const byDate = new Map<string, Record<EnvSlug, number>>();
    for (const d of data.data?.daily ?? []) {
      const r = byDate.get(d.date) ?? { sandbox: 0, development: 0, production: 0 };
      r[d.environment] = (r[d.environment] ?? 0) + d.requests;
      byDate.set(d.date, r);
    }
    return [...byDate.entries()].sort((a, b) => b[0].localeCompare(a[0]));
  }, [data.data]);
  const max = Math.max(1, ...rows.map(([, r]) => r.sandbox + r.development + r.production));

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setSaved(false);
    const q = await quota.run(() => usage.setQuota(project.id, { dailyRequestLimit: Number(daily), requestsPerMinute: Number(rpm) }));
    if (q) { setSaved(true); data.reload(); }
  };

  const download = async () => {
    const bundle = await exp.run(() => usage.export(project.id, includeRecords));
    if (bundle === undefined) return;
    const blob = new Blob([JSON.stringify(bundle, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${project.slug}-export.json`;
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <>
      <PageHeader title="Usage" subtitle="Requests to /api/v1 are counted per project and environment."
        actions={<select aria-label="Range" value={days} onChange={(e) => setDays(Number(e.target.value))}>
          {[7, 30, 90].map((d) => <option key={d} value={d}>Last {d} days</option>)}
        </select>} />
      {data.loading && !data.data ? <Spinner /> : data.error ? <ErrorBox error={data.error} onRetry={data.reload} /> : data.data && (
        <>
          <div className="grid grid-3">
            {ENVIRONMENTS.map((env) => (
              <div className="card stat" key={env}>
                <span className={`badge env-${env}`}>{env}</span>
                <strong>{data.data!.today[env] ?? 0}</strong>
                <span className="muted small">requests today of {data.data!.quota.dailyRequestLimit}</span>
              </div>
            ))}
          </div>
          <Card title="Daily requests">
            {!rows.length ? <Empty title="No requests in this period" /> : (
              <div className="table-wrap">
                <table className="table">
                  <thead><tr><th>Date</th>{ENVIRONMENTS.map((e) => <th key={e}>{e}</th>)}<th>Total</th><th className="bar-col" /></tr></thead>
                  <tbody>
                    {rows.map(([date, r]) => {
                      const total = r.sandbox + r.development + r.production;
                      return (
                        <tr key={date}>
                          <td className="nowrap">{date}</td>
                          {ENVIRONMENTS.map((e) => <td key={e}>{r[e]}</td>)}
                          <td>{total}</td>
                          <td className="bar-col">
                            <div className="bar" aria-hidden>
                              {ENVIRONMENTS.map((e) => <span key={e} className={`bar-seg seg-${e}`} style={{ width: `${(r[e] / max) * 100}%` }} />)}
                            </div>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </Card>
          <div className="grid grid-2">
            <Card title="Quota">
              <form className="form" onSubmit={submit}>
                <label>Daily request limit<input type="number" min={0} required value={daily} onChange={(e) => setDaily(e.target.value)} /></label>
                <label>Requests per minute<input type="number" min={0} required value={rpm} onChange={(e) => setRpm(e.target.value)} /></label>
                <ErrorBox error={quota.error} />
                {saved && <div className="alert alert-success">Quota updated.</div>}
                <div><button className="btn btn-primary" disabled={quota.busy}>Save quota</button></div>
              </form>
            </Card>
            <Card title="Export">
              <p className="muted">Download a JSON bundle with the project, schemas, custom APIs, releases, deployments and an OpenAPI document.</p>
              <label className="check"><input type="checkbox" checked={includeRecords} onChange={(e) => setIncludeRecords(e.target.checked)} /> Include live records</label>
              <ErrorBox error={exp.error} />
              <button className="btn" disabled={exp.busy} onClick={download}>{exp.busy ? 'Preparing…' : 'Download export'}</button>
            </Card>
          </div>
        </>
      )}
    </>
  );
}
