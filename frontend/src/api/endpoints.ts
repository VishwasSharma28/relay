import { api, ensureCsrf, request, requestWithHeaders, resetCsrf } from './client';
import type {
  ActiveDeployment, ApiKey, ApiKeyCreated, CustomApi, CustomApiVersion, Deployment, EnvSlug, Environment,
  HttpMethod, Member, MemberRole, ModuleDef, ModuleInstallation, Project, Quota, RecordItem, RecordPage,
  Release, Resource, RunResult, SchemaDefinition, SchemaVersion, Usage, User,
} from './types';

const e = encodeURIComponent;
const p = (projectId: string) => `/api/projects/${e(projectId)}`;

export const auth = {
  me: () => api.get<User>('/api/auth/me'),
  register: (body: { email: string; password: string; fullName: string }) => api.post<User>('/api/auth/register', body),
  async login(body: { email: string; password: string }) {
    const user = await api.post<User>('/api/auth/login', body);
    resetCsrf();
    await ensureCsrf(true).catch(() => undefined);
    return user;
  },
  async logout() {
    try {
      await api.post<{ loggedOut: boolean }>('/api/auth/logout');
    } finally {
      resetCsrf();
      await ensureCsrf(true).catch(() => undefined);
    }
  },
};

export const projects = {
  list: () => api.get<Project[]>('/api/projects'),
  create: (body: { name: string; slug: string; description?: string }) => api.post<Project>('/api/projects', body),
  get: (id: string) => api.get<Project>(p(id)),
  update: (id: string, body: Partial<Pick<Project, 'name' | 'slug' | 'description'>>) => api.patch<Project>(p(id), body),
  remove: (id: string) => api.del<void>(p(id)),
  environments: (id: string) => api.get<Environment[]>(`${p(id)}/environments`),
  members: (id: string) => api.get<Member[]>(`${p(id)}/members`),
  addMember: (id: string, body: { email: string; role: MemberRole }) => api.post<Member>(`${p(id)}/members`, body),
  updateMember: (id: string, userId: string, role: MemberRole) => api.patch<Member>(`${p(id)}/members/${e(userId)}`, { role }),
  removeMember: (id: string, userId: string) => api.del<void>(`${p(id)}/members/${e(userId)}`),
};

export const resources = {
  list: (pid: string) => api.get<Resource[]>(`${p(pid)}/resources`),
  create: (pid: string, body: { name: string; slug: string; description?: string; schemaDefinition: SchemaDefinition }) =>
    api.post<Resource>(`${p(pid)}/resources`, body),
  get: (pid: string, slug: string) => api.get<Resource>(`${p(pid)}/resources/${e(slug)}`),
  remove: (pid: string, slug: string) => api.del<void>(`${p(pid)}/resources/${e(slug)}`),
  versions: (pid: string, slug: string) => api.get<SchemaVersion[]>(`${p(pid)}/resources/${e(slug)}/schema-versions`),
  publish: (pid: string, slug: string, schemaDefinition: SchemaDefinition) =>
    api.post<SchemaVersion>(`${p(pid)}/resources/${e(slug)}/schema-versions`, { schemaDefinition }),
};

export interface RecordQuery {
  limit?: number; sort?: string; order?: 'asc' | 'desc'; cursor?: string | null;
  /** field -> raw JSON literal, sent as filter[field]=<json> */
  filters?: Record<string, string>;
}

const recordsBase = (pid: string, env: EnvSlug, slug: string) =>
  `${p(pid)}/environments/${e(env)}/resources/${e(slug)}/records`;

export const records = {
  list: (pid: string, env: EnvSlug, slug: string, q: RecordQuery = {}) => {
    const query: Record<string, string | number | undefined | null> = {
      limit: q.limit, sort: q.sort, order: q.order, cursor: q.cursor,
    };
    for (const [field, value] of Object.entries(q.filters ?? {})) {
      if (field && value !== '') query[`filter[${field}]`] = value;
    }
    return api.get<RecordPage>(recordsBase(pid, env, slug), query);
  },
  create: (pid: string, env: EnvSlug, slug: string, data: Record<string, unknown>) =>
    api.post<RecordItem>(recordsBase(pid, env, slug), data),
  patch: (pid: string, env: EnvSlug, slug: string, id: string, etag: string, patch: Record<string, unknown>) =>
    request<RecordItem>(`${recordsBase(pid, env, slug)}/${e(id)}`, {
      method: 'PATCH', body: patch, contentType: 'application/merge-patch+json', headers: { 'If-Match': etag },
    }),
  remove: (pid: string, env: EnvSlug, slug: string, id: string, etag: string) =>
    api.del<void>(`${recordsBase(pid, env, slug)}/${e(id)}`, { 'If-Match': etag }),
};

export const apiKeys = {
  list: (pid: string) => api.get<ApiKey[]>(`${p(pid)}/api-keys`),
  create: (pid: string, body: { name: string; environmentSlug: EnvSlug; expiresAt?: string }) =>
    api.post<ApiKeyCreated>(`${p(pid)}/api-keys`, body),
  revoke: (pid: string, id: string) => api.del<void>(`${p(pid)}/api-keys/${e(id)}`),
};

export const customApis = {
  list: (pid: string) => api.get<CustomApi[]>(`${p(pid)}/custom-apis`),
  create: (pid: string, body: { name: string; slug: string; description?: string; method: HttpMethod; definition: unknown }) =>
    api.post<CustomApi>(`${p(pid)}/custom-apis`, body),
  get: (pid: string, slug: string) => api.get<CustomApi>(`${p(pid)}/custom-apis/${e(slug)}`),
  remove: (pid: string, slug: string) => api.del<void>(`${p(pid)}/custom-apis/${e(slug)}`),
  versions: (pid: string, slug: string) => api.get<CustomApiVersion[]>(`${p(pid)}/custom-apis/${e(slug)}/versions`),
  publish: (pid: string, slug: string, body: { method: HttpMethod; definition: unknown }) =>
    api.post<CustomApiVersion>(`${p(pid)}/custom-apis/${e(slug)}/versions`, body),
  run: (pid: string, env: EnvSlug, slug: string, input: unknown) =>
    api.post<RunResult>(`${p(pid)}/environments/${e(env)}/custom-apis/${e(slug)}/run`, { input }),
};

export const modules = {
  catalog: () => api.get<ModuleDef[]>('/api/modules'),
  installed: (pid: string) => api.get<ModuleInstallation[]>(`${p(pid)}/modules`),
  install: (pid: string, key: string) => api.post<unknown>(`${p(pid)}/modules/${e(key)}/install`),
};

export const releases = {
  list: (pid: string) => api.get<Release[]>(`${p(pid)}/releases`),
  create: (pid: string, notes: string) => api.post<Release>(`${p(pid)}/releases`, { notes }),
  get: (pid: string, version: number) => api.get<Release>(`${p(pid)}/releases/${version}`),
  deployments: (pid: string) => api.get<Deployment[]>(`${p(pid)}/deployments`),
  active: (pid: string) => api.get<ActiveDeployment[]>(`${p(pid)}/deployments/active`),
  deploy: (pid: string, env: EnvSlug, releaseVersion: number) =>
    api.post<Deployment>(`${p(pid)}/environments/${e(env)}/deployments`, { releaseVersion }),
  rollback: (pid: string, env: EnvSlug) => api.post<Deployment>(`${p(pid)}/environments/${e(env)}/rollback`),
};

export const usage = {
  get: (pid: string, days = 30) => api.get<Usage>(`${p(pid)}/usage`, { days }),
  setQuota: (pid: string, q: Quota) => api.put<Quota>(`${p(pid)}/quota`, q),
  /** Export is returned in the envelope; we download the unwrapped data as a JSON file. */
  export: async (pid: string, includeRecords: boolean) =>
    (await requestWithHeaders<unknown>(`${p(pid)}/export`, { query: { includeRecords: includeRecords || undefined } })).data,
};
