import { createContext, useContext } from 'react';
import type { EnvSlug, Project } from '../../api/types';

export interface ProjectCtx {
  project: Project;
  env: EnvSlug;
  setEnv: (env: EnvSlug) => void;
  reloadProject: () => void;
  /** Build a project-relative link that keeps the current env in the URL. */
  to: (path: string) => string;
}

export const ProjectContext = createContext<ProjectCtx | null>(null);

export function useProject(): ProjectCtx {
  const c = useContext(ProjectContext);
  if (!c) throw new Error('useProject outside ProjectLayout');
  return c;
}
