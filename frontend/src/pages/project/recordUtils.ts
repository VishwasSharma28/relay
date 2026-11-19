/** Values that parse as JSON are sent as-is; anything else is sent as a JSON string. */
export function toJsonLiteral(v: string): string {
  try { JSON.parse(v); return v.trim(); } catch { return JSON.stringify(v); }
}

export function parseObject(text: string): Record<string, unknown> {
  let v: unknown;
  try { v = JSON.parse(text); } catch (e) { throw new Error(`Invalid JSON: ${(e as Error).message}`, { cause: e }); }
  if (!v || typeof v !== 'object' || Array.isArray(v)) throw new Error('Body must be a JSON object');
  return v as Record<string, unknown>;
}
