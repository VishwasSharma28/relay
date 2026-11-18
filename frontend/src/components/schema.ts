import type { FieldSchema, FieldType, SchemaDefinition } from '../api/types';

export interface FieldRow {
  key: string;
  name: string;
  type: FieldType;
  required: boolean;
  unique: boolean;
  /** Raw text of the default value; empty means "no default". */
  defaultText: string;
}

let seq = 0;
export const newField = (partial: Partial<FieldRow> = {}): FieldRow => ({
  key: `f${++seq}`, name: '', type: 'STRING', required: false, unique: false, defaultText: '', ...partial,
});

export function parseDefault(type: FieldType, text: string): unknown {
  const t = text.trim();
  switch (type) {
    case 'INTEGER': {
      if (!/^-?\d+$/.test(t)) throw new Error(`Default "${text}" is not an integer`);
      return Number(t);
    }
    case 'DECIMAL': {
      const n = Number(t);
      if (t === '' || isNaN(n)) throw new Error(`Default "${text}" is not a number`);
      return n;
    }
    case 'BOOLEAN':
      if (t !== 'true' && t !== 'false') throw new Error('Boolean default must be true or false');
      return t === 'true';
    case 'JSON':
      try { return JSON.parse(t); } catch { throw new Error(`Default for JSON field is not valid JSON`); }
    default:
      return text;
  }
}

const NAME_RE = /^[A-Za-z_][A-Za-z0-9_]*$/;

export function fieldsToSchema(rows: FieldRow[]): SchemaDefinition {
  const properties: Record<string, FieldSchema> = {};
  for (const row of rows) {
    const name = row.name.trim();
    if (!name) throw new Error('Every field needs a name');
    if (!NAME_RE.test(name)) throw new Error(`Invalid field name "${name}" (letters, digits, underscore)`);
    if (properties[name]) throw new Error(`Duplicate field name "${name}"`);
    const field: FieldSchema = { type: row.type, required: row.required, unique: row.unique };
    if (row.defaultText.trim() !== '') field.default = parseDefault(row.type, row.defaultText);
    properties[name] = field;
  }
  return { type: 'object', properties };
}

export function schemaToFields(schema: SchemaDefinition | null | undefined): FieldRow[] {
  if (!schema?.properties) return [];
  return Object.entries(schema.properties).map(([name, f]) =>
    newField({
      name,
      type: f.type,
      required: !!f.required,
      unique: !!f.unique,
      defaultText: f.default === undefined || f.default === null
        ? ''
        : typeof f.default === 'string' ? f.default : JSON.stringify(f.default),
    }),
  );
}
