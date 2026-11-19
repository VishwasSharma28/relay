import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { ApiError } from './api/client';
import { auth } from './api/endpoints';
import type { User } from './api/types';

interface AuthCtx {
  user: User | null;
  loading: boolean;
  setUser: (u: User | null) => void;
  logout: () => Promise<void>;
}

const Ctx = createContext<AuthCtx | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  useEffect(() => {
    auth.me().then(setUser, (e) => {
      if (!(e instanceof ApiError) || (e.status !== 401 && e.status !== 403)) console.warn(e);
      setUser(null);
    }).finally(() => setLoading(false));
  }, []);
  const logout = useCallback(async () => {
    try { await auth.logout(); } finally { setUser(null); }
  }, []);
  return <Ctx.Provider value={{ user, loading, setUser, logout }}>{children}</Ctx.Provider>;
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth(): AuthCtx {
  const c = useContext(Ctx);
  if (!c) throw new Error('useAuth outside AuthProvider');
  return c;
}
