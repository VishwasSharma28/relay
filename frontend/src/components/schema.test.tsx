import { useState } from 'react';
import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { FieldEditor } from './FieldEditor';
import { fieldsToSchema, newField, schemaToFields, type FieldRow } from './schema';

let latest: FieldRow[] = [];
function Harness() {
  const [fields, setFields] = useState<FieldRow[]>([]);
  latest = fields;
  return <FieldEditor fields={fields} onChange={setFields} />;
}

describe('schema field editor', () => {
  it('builds a correct schemaDefinition from edited fields', () => {
    render(<Harness />);
    fireEvent.click(screen.getByText('+ Add field'));
    fireEvent.change(screen.getByLabelText('Field 1 name'), { target: { value: 'title' } });
    fireEvent.click(screen.getByLabelText('Field 1 required'));
    fireEvent.click(screen.getByLabelText('Field 1 unique'));

    fireEvent.click(screen.getByText('+ Add field'));
    fireEvent.change(screen.getByLabelText('Field 2 name'), { target: { value: 'done' } });
    fireEvent.change(screen.getByLabelText('Field 2 type'), { target: { value: 'BOOLEAN' } });
    fireEvent.change(screen.getByLabelText('Field 2 default'), { target: { value: 'false' } });

    fireEvent.click(screen.getByText('+ Add field'));
    fireEvent.change(screen.getByLabelText('Field 3 name'), { target: { value: 'priority' } });
    fireEvent.change(screen.getByLabelText('Field 3 type'), { target: { value: 'INTEGER' } });
    fireEvent.change(screen.getByLabelText('Field 3 default'), { target: { value: '3' } });

    fireEvent.click(screen.getByText('+ Add field'));
    fireEvent.click(screen.getByLabelText('Remove field 4'));

    expect(fieldsToSchema(latest)).toEqual({
      type: 'object',
      properties: {
        title: { type: 'STRING', required: true, unique: true },
        done: { type: 'BOOLEAN', required: false, unique: false, default: false },
        priority: { type: 'INTEGER', required: false, unique: false, default: 3 },
      },
    });
  });

  it('parses JSON and decimal defaults and round-trips', () => {
    const rows = [
      newField({ name: 'meta', type: 'JSON', defaultText: '{"a":1}' }),
      newField({ name: 'price', type: 'DECIMAL', defaultText: '9.5' }),
    ];
    const schema = fieldsToSchema(rows);
    expect(schema.properties.meta.default).toEqual({ a: 1 });
    expect(schema.properties.price.default).toBe(9.5);
    expect(fieldsToSchema(schemaToFields(schema))).toEqual(schema);
  });

  it('rejects invalid input', () => {
    expect(() => fieldsToSchema([newField({ name: '' })])).toThrow(/needs a name/);
    expect(() => fieldsToSchema([newField({ name: 'a' }), newField({ name: 'a' })])).toThrow(/Duplicate/);
    expect(() => fieldsToSchema([newField({ name: 'n', type: 'INTEGER', defaultText: '1.5' })])).toThrow(/integer/);
    expect(() => fieldsToSchema([newField({ name: 'b', type: 'BOOLEAN', defaultText: 'yes' })])).toThrow(/true or false/);
  });
});
