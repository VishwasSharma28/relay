export const EXAMPLE_DEFINITION = {
  steps: [
    { id: 'find', type: 'list', resource: 'todos', filter: { done: false }, limit: 10 },
    { id: 'make', type: 'create', resource: 'todos', data: { title: '{{input.title}}', done: false } },
  ],
  response: { status: 200, body: { created: '{{steps.make}}', open: '{{steps.find.data}}' } },
};

export const EXAMPLE_TEXT = JSON.stringify(EXAMPLE_DEFINITION, null, 2);

export function parseJson(text: string, what: string): unknown {
  try { return JSON.parse(text); } catch (e) { throw new Error(`${what} is not valid JSON: ${(e as Error).message}`, { cause: e }); }
}

export function curlSnippet(origin: string, projectId: string, slug: string, method: string, input: string): string {
  const url = `${origin}/api/v1/projects/${projectId}/functions/${slug}`;
  if (method === 'GET') {
    let qs = '';
    try {
      const obj = JSON.parse(input) as Record<string, unknown>;
      const p = new URLSearchParams();
      for (const [k, v] of Object.entries(obj ?? {})) p.append(k, typeof v === 'string' ? v : JSON.stringify(v));
      qs = p.toString();
    } catch { /* ignore */ }
    return `curl -H "Authorization: Bearer $RELAY_API_KEY" \\\n  "${url}${qs ? `?${qs}` : ''}"`;
  }
  let body = '{}';
  try { body = JSON.stringify(JSON.parse(input)); } catch { /* ignore */ }
  return `curl -X POST -H "Authorization: Bearer $RELAY_API_KEY" \\\n  -H "Content-Type: application/json" \\\n  -d '${body.replace(/'/g, `'\\''`)}' \\\n  "${url}"`;
}
