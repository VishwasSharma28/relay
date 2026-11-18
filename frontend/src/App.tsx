import type { ReactNode } from 'react';
import { Link, Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from './auth';
import { Spinner } from './components/ui';
import { Landing } from './pages/Landing';
import { Login } from './pages/Login';
import { Register } from './pages/Register';
import { Dashboard } from './pages/Dashboard';
import { ProjectLayout } from './pages/project/ProjectLayout';
import { Overview } from './pages/project/Overview';
import { Resources } from './pages/project/Resources';
import { ResourceDetail } from './pages/project/ResourceDetail';
import { Records } from './pages/project/Records';
import { ApiKeys } from './pages/project/ApiKeys';
import { CustomApis } from './pages/project/CustomApis';
import { CustomApiDetail } from './pages/project/CustomApiDetail';
import { Modules } from './pages/project/Modules';
import { Releases } from './pages/project/Releases';
import { Settings } from './pages/project/Settings';
import { UsagePage } from './pages/project/Usage';

function RequireAuth({ children }: { children: ReactNode }) {
  const { user, loading } = useAuth();
  const loc = useLocation();
  if (loading) return <Spinner />;
  if (!user) return <Navigate to="/login" replace state={{ from: loc.pathname + loc.search }} />;
  return <>{children}</>;
}

function TopBar() {
  const { user, logout } = useAuth();
  const nav = useNavigate();
  return (
    <header className="topbar">
      <Link to={user ? '/dashboard' : '/'} className="brand"><span className="brand-mark" />Relay</Link>
      <nav className="row">
        {user ? (
          <>
            <Link to="/dashboard" className="topbar-link">Projects</Link>
            <span className="muted hide-sm">{user.email}</span>
            <button className="btn btn-sm" onClick={async () => { await logout(); nav('/'); }}>Log out</button>
          </>
        ) : (
          <>
            <Link to="/login" className="topbar-link">Log in</Link>
            <Link to="/register" className="btn btn-sm btn-primary">Sign up</Link>
          </>
        )}
      </nav>
    </header>
  );
}

export function App() {
  return (
    <div className="app">
      <TopBar />
      <Routes>
        <Route path="/" element={<Landing />} />
        <Route path="/login" element={<Login />} />
        <Route path="/register" element={<Register />} />
        <Route path="/dashboard" element={<RequireAuth><Dashboard /></RequireAuth>} />
        <Route path="/projects/:projectId" element={<RequireAuth><ProjectLayout /></RequireAuth>}>
          <Route index element={<Overview />} />
          <Route path="resources" element={<Resources />} />
          <Route path="resources/:slug" element={<ResourceDetail />} />
          <Route path="records" element={<Records />} />
          <Route path="records/:slug" element={<Records />} />
          <Route path="api-keys" element={<ApiKeys />} />
          <Route path="custom-apis" element={<CustomApis />} />
          <Route path="custom-apis/:slug" element={<CustomApiDetail />} />
          <Route path="modules" element={<Modules />} />
          <Route path="releases" element={<Releases />} />
          <Route path="usage" element={<UsagePage />} />
          <Route path="settings" element={<Settings />} />
        </Route>
        <Route path="*" element={<main className="container"><h1>Not found</h1><Link to="/">Go home</Link></main>} />
      </Routes>
    </div>
  );
}
