import type { Attribute } from '../../api/types';
import { Field } from '../../ui';

interface AttributeFieldsProps {
  attributes: readonly Attribute[];
  /** The values by attribute name; a missing or empty value means "not set". */
  values: Record<string, string>;
  onChange: (values: Record<string, string>) => void;
}

/**
 * One field per attribute of a Resource Type, whose control follows the attribute's kind: text,
 * a number, a date, or a choice of the allowed values. The server checks every value again.
 */
export function AttributeFields({ attributes, values, onChange }: AttributeFieldsProps) {
  return (
    <>
      {attributes.map((attribute) => {
        const value = values[attribute.name] ?? '';
        const set = (next: string) => onChange({ ...values, [attribute.name]: next });
        return (
          <Field key={attribute.id} label={attribute.name}>
            {attribute.kind === 'LIST' ? (
              <select value={value} onChange={(event) => set(event.target.value)}>
                <option value="">Not set</option>
                {(attribute.allowedValues ?? []).map((choice) => (
                  <option key={choice} value={choice}>
                    {choice}
                  </option>
                ))}
              </select>
            ) : (
              <input
                type={attribute.kind === 'NUMBER' ? 'number' : attribute.kind === 'DATE' ? 'date' : 'text'}
                step={attribute.kind === 'NUMBER' ? 'any' : undefined}
                value={value}
                onChange={(event) => set(event.target.value)}
              />
            )}
          </Field>
        );
      })}
    </>
  );
}

/** A Resource's attribute values in one line: "seats 8 · wing North". */
export function attributeSummary(attributes: Record<string, string>): string {
  const entries = Object.entries(attributes);
  return entries.length === 0 ? '—' : entries.map(([name, value]) => `${name} ${value}`).join(' · ');
}
