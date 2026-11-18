import { useCallback, useState } from 'react';
import { NavLink, Outlet, useParams, useSearchParams } from 'react-router-dom';
import { projects } from '../../api/endpoints';
import { ENVIRONMENTS, type EnvSlug } from '../../api/types';
import { ErrorBox, Spinner } from '../../components/ui';
import { useAsync } from '../../hooks';
import { ProjectContext } from './context';

const NAV: [string, string][] = [
  ['', 'Overview'],
  ['resources', 'Resources'],
  ['records', 'Records'],
  ['api-keys', 'API keys'],
  ['custom-apis', 'Custom APIs'],
  ['modules', 'Modules'],
  ['releases', 'Releases'],
  ['usage', 'Usage'],
  ['settings', 'Settings'],
];

export function ProjectLayout() {
  const { projectId = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const [open, setOpen] = useState(false);
  const proj = useAsync(() => projects.get(projectId), [projectId]);
  const raw = params.get('env');
  const env: EnvSlug = (ENVIRONMENTS as string[]).includes(raw ?? '') ? (raw as EnvSlug) : 'sandbox';

  const setEnv = useCallback((next: EnvSlug) => {
    setParams((prev) => { const n = new URLSearchParams(prev); n.set('env', next); return n; });
  }, [setParams]);
  const to = useCallback((path: string) => `/projects/${projectId}${path ? `/${path}` : ''}?env=${env}`, [projectId, env]);

  if (proj.loading && !proj.data) return <Spinner />;
  if (proj.error || !proj.data) return <main className="container"><ErrorBox error={proj.error ?? 'Project not found'} onRetry={proj.reload} /></main>;

  return (
    <ProjectContext.Provider value={{ project: proj.data, env, setEnv, reloadProject: proj.reload, to }}>
      <div className="project-shell">
        <aside className={`sidebar ${open ? 'open' : ''}`}>
          <div className="sidebar-head">
            <div className="sidebar-project" title={proj.data.name}>{proj.data.name}</div>
            <button className="btn btn-sm sidebar-toggle" aria-expanded={open} aria-label="Toggle navigation" onClick={() => setOpen((o) => !o)}>
              {open ? 'Close' : 'Menu'}
            </button>
          </div>
          <label className="env-switch">
            <span className="muted small">Environment</span>
            <select aria-label="Environment" value={env} onChange={(e) => setEnv(e.target.value as EnvSlug)}>
              {ENVIRONMENTS.map((e) => <option key={e} value={e}>{e}</option>)}
            </select>
          </label>
          <nav className="sidebar-nav">
            {NAV.map(([path, label]) => (
              <NavLink key={label} end={path === ''} to={to(path)} onClick={() => setOpen(false)}
                className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}>{label}</NavLink>
            ))}
          </nav>
        </aside>
        <main className="project-main">
          <div className={`env-banner env-${env}`}>Environment: <strong>{env}</strong></div>
          <Outlet />
        </main>
      </div>
    </ProjectContext.Provider>
  );
}
