import { Link } from 'react-router-dom';
import { apiKeys, customApis, releases, resources } from '../../api/endpoints';
import { ENVIRONMENTS } from '../../api/types';
import { Card, ErrorBox, PageHeader, Spinner } from '../../components/ui';
import { fmtDate } from '../../components/format';
import { useAsync } from '../../hooks';
import { useProject } from './context';

export function Overview() {
  const { project, to } = useProject();
  const stats = useAsync(async () => {
    const [res, keys, apis, active] = await Promise.all([
      resources.list(project.id), apiKeys.list(project.id), customApis.list(project.id), releases.active(project.id),
    ]);
    return { res, keys, apis, active };
  }, [project.id]);

  return (
    <>
      <PageHeader title={project.name} subtitle={<>Project ID <code>{project.id}</code>{project.description ? ` · ${project.description}` : ''}</>} />
      {stats.loading ? <Spinner /> : stats.error ? <ErrorBox error={stats.error} onRetry={stats.reload} /> : stats.data && (
        <>
          <div className="grid grid-3">
            <Link to={to('resources')} className="card stat"><span className="muted">Resources</span><strong>{stats.data.res.length}</strong></Link>
            <Link to={to('api-keys')} className="card stat"><span className="muted">Active API keys</span>
              <strong>{stats.data.keys.filter((k) => !k.revokedAt).length}</strong>
              <span className="muted small">{stats.data.keys.length} total</span></Link>
            <Link to={to('custom-apis')} className="card stat"><span className="muted">Custom APIs</span><strong>{stats.data.apis.length}</strong></Link>
          </div>
          <Card title="Active deployments" actions={<Link to={to('releases')} className="btn btn-sm">Manage releases</Link>}>
            <table className="table">
              <thead><tr><th>Environment</th><th>Release</th><th>Deployed</th></tr></thead>
              <tbody>
                {ENVIRONMENTS.map((env) => {
                  const a = stats.data!.active.find((x) => x.environment === env);
                  return (
                    <tr key={env}>
                      <td><span className={`badge env-${env}`}>{env}</span></td>
                      <td>{a?.releaseVersion != null ? `v${a.releaseVersion}` : <span className="muted">none</span>}</td>
                      <td>{fmtDate(a?.deployedAt)}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </Card>
        </>
      )}
    </>
  );
}
