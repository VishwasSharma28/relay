export type EnvSlug = 'sandbox' | 'development' | 'production';
export const ENVIRONMENTS: EnvSlug[] = ['sandbox', 'development', 'production'];
export type MemberRole = 'OWNER' | 'ADMIN' | 'MEMBER';
export type FieldType = 'STRING' | 'INTEGER' | 'DECIMAL' | 'BOOLEAN' | 'DATE' | 'DATETIME' | 'JSON';
export const FIELD_TYPES: FieldType[] = ['STRING', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'DATE', 'DATETIME', 'JSON'];

export interface User { id: string; email: string; fullName: string; role: string; createdAt?: string }
export interface Project {
  id: string; name: string; slug: string; description: string | null;
  createdBy: string; createdAt: string; updatedAt: string;
}
export interface Member { userId: string; email: string; fullName: string; role: MemberRole; joinedAt: string }
export interface Environment { id: string; projectId: string; name: string; slug: EnvSlug; createdAt: string }

export interface FieldSchema { type: FieldType; required?: boolean; unique?: boolean; default?: unknown }
export interface SchemaDefinition { type: 'object'; properties: Record<string, FieldSchema> }
export interface Resource {
  id: string; projectId: string; name: string; slug: string; description: string | null;
  currentSchemaVersionId: string | null; currentSchemaVersion: number | null; createdAt: string; updatedAt: string;
}
export interface SchemaVersion { id: string; resourceId: string; version: number; schemaDefinition: SchemaDefinition; createdAt: string }

export interface RecordItem {
  id: string; createdAt: string; updatedAt: string; schemaVersion: number; etag: string;
  data: Record<string, unknown>;
}
export interface RecordPage { data: RecordItem[]; pagination: { limit: number; nextCursor: string | null } }

export interface ApiKey {
  id: string; projectId: string; environmentId: string; environmentSlug: EnvSlug; name: string; keyPrefix: string;
  createdBy: string; createdAt: string; lastUsedAt: string | null; expiresAt: string | null; revokedAt: string | null;
}
export interface ApiKeyCreated { apiKey: ApiKey; secret: string }

export type HttpMethod = 'GET' | 'POST';
export interface CustomApi {
  id: string; projectId: string; name: string; slug: string; description: string | null;
  currentVersion: number; method: HttpMethod; definition: unknown; createdAt: string; updatedAt: string;
}
export interface CustomApiVersion { id: string; version: number; method: HttpMethod; definition: unknown; createdAt: string }
export interface RunResult { status: number; body: unknown; steps: { id: string; type: string; ok: boolean }[] }

export interface ModuleDef {
  key: string; name: string; description: string; version: string;
  resources: { name: string; slug: string; schemaDefinition: SchemaDefinition }[];
  customApis: { name: string; slug: string; method: string; definition: unknown }[];
}
export interface ModuleInstallation { moduleKey: string; moduleVersion: string; installedAt: string; installedBy: string }

export interface Release { id: string; version: number; notes: string | null; snapshot: unknown; createdBy: string; createdAt: string }
export interface Deployment { id: string; releaseVersion: number; environment: EnvSlug; action: string; createdBy: string; createdAt: string }
export interface ActiveDeployment { environment: EnvSlug; releaseVersion: number | null; deployedAt: string | null }

export interface Quota { dailyRequestLimit: number; requestsPerMinute: number }
export interface Usage {
  quota: Quota;
  today: Record<EnvSlug, number>;
  daily: { date: string; environment: EnvSlug; requests: number }[];
}
