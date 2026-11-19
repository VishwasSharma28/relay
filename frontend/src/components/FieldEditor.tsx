import { FIELD_TYPES, type FieldType } from '../api/types';
import { newField, type FieldRow } from './schema';

export function FieldEditor({ fields, onChange }: { fields: FieldRow[]; onChange: (f: FieldRow[]) => void }) {
  const update = (key: string, patch: Partial<FieldRow>) =>
    onChange(fields.map((f) => (f.key === key ? { ...f, ...patch } : f)));
  return (
    <div className="field-editor">
      {fields.length === 0 && <p className="muted">No fields yet. Add one below.</p>}
      {fields.length > 0 && (
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr><th>Name</th><th>Type</th><th>Required</th><th>Unique</th><th>Default</th><th /></tr>
            </thead>
            <tbody>
              {fields.map((f, i) => (
                <tr key={f.key}>
                  <td>
                    <input aria-label={`Field ${i + 1} name`} value={f.name} placeholder="title"
                      onChange={(e) => update(f.key, { name: e.target.value })} />
                  </td>
                  <td>
                    <select aria-label={`Field ${i + 1} type`} value={f.type}
                      onChange={(e) => update(f.key, { type: e.target.value as FieldType })}>
                      {FIELD_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
                    </select>
                  </td>
                  <td className="center">
                    <input type="checkbox" aria-label={`Field ${i + 1} required`} checked={f.required}
                      onChange={(e) => update(f.key, { required: e.target.checked })} />
                  </td>
                  <td className="center">
                    <input type="checkbox" aria-label={`Field ${i + 1} unique`} checked={f.unique}
                      onChange={(e) => update(f.key, { unique: e.target.checked })} />
                  </td>
                  <td>
                    <input aria-label={`Field ${i + 1} default`} value={f.defaultText}
                      placeholder={f.type === 'BOOLEAN' ? 'true / false' : f.type === 'JSON' ? '{}' : 'none'}
                      onChange={(e) => update(f.key, { defaultText: e.target.value })} />
                  </td>
                  <td>
                    <button type="button" className="btn btn-sm btn-ghost" aria-label={`Remove field ${i + 1}`}
                      onClick={() => onChange(fields.filter((x) => x.key !== f.key))}>Remove</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <button type="button" className="btn btn-sm" onClick={() => onChange([...fields, newField()])}>+ Add field</button>
    </div>
  );
}
