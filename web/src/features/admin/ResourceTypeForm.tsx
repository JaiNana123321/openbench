import { useState, type FormEvent } from 'react';
import { fieldError } from '../../api/client';
import type { AllocationClass, AttributeKind, Id, ResourceType, ResourceTypeDefinition } from '../../api/types';
import { Button, Field, ProblemMessage } from '../../ui';

const CLASSES: { value: AllocationClass; label: string }[] = [
  { value: 'EXCLUSIVE', label: 'Exclusive (one holder at a time, such as a room)' },
  { value: 'METERED', label: 'Metered (units shared by the hour, such as GPUs)' },
  { value: 'MATCHED', label: 'Matched (people, matched by skill)' },
];

const KINDS: { value: AttributeKind; label: string }[] = [
  { value: 'TEXT', label: 'Text' },
  { value: 'NUMBER', label: 'Number' },
  { value: 'DATE', label: 'Date' },
  { value: 'LIST', label: 'List of allowed values' },
];

/** One attribute row as the form edits it; `allowedValues` is the comma-separated text. */
interface AttributeRow {
  key: number;
  id?: Id;
  name: string;
  kind: AttributeKind;
  allowedValues: string;
}

interface ResourceTypeFormProps {
  /** The type to change; absent when defining a new one. */
  initial?: ResourceType;
  submitLabel: string;
  busy: boolean;
  /** The failed save, shown under the form. */
  error: unknown;
  onSubmit: (definition: ResourceTypeDefinition) => void;
}

let nextKey = 0;

function rowsOf(type: ResourceType | undefined): AttributeRow[] {
  return (type?.attributes ?? []).map((attribute) => ({
    key: nextKey++,
    id: attribute.id,
    name: attribute.name,
    kind: attribute.kind,
    allowedValues: (attribute.allowedValues ?? []).join(', '),
  }));
}

/**
 * The definition of a Resource Type: a name, one Allocation Class, and attributes, each with a kind
 * and, for a list, its allowed values. Attributes keep their ids, so a renamed one keeps its values.
 */
export function ResourceTypeForm({ initial, submitLabel, busy, error, onSubmit }: ResourceTypeFormProps) {
  const [name, setName] = useState(initial?.name ?? '');
  const [allocationClass, setAllocationClass] = useState<AllocationClass>(initial?.allocationClass ?? 'EXCLUSIVE');
  const [rows, setRows] = useState<AttributeRow[]>(() => rowsOf(initial));

  const update = (key: number, change: Partial<AttributeRow>) =>
    setRows((current) => current.map((row) => (row.key === key ? { ...row, ...change } : row)));

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    onSubmit({
      name,
      allocationClass,
      attributes: rows.map((row) => ({
        ...(row.id === undefined ? {} : { id: row.id }),
        name: row.name,
        kind: row.kind,
        allowedValues:
          row.kind === 'LIST'
            ? row.allowedValues
                .split(',')
                .map((value) => value.trim())
                .filter((value) => value !== '')
            : null,
      })),
    });
  }

  return (
    <form className="panel form" onSubmit={handleSubmit}>
      <Field label="Name" error={fieldError(error, 'name')}>
        <input value={name} required onChange={(event) => setName(event.target.value)} />
      </Field>
      <Field
        label="Allocation Class"
        hint="Fixed once a Resource of the type exists."
        error={fieldError(error, 'allocationClass')}
      >
        <select value={allocationClass} onChange={(event) => setAllocationClass(event.target.value as AllocationClass)}>
          {CLASSES.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
      </Field>

      <h3 className="form__heading">Attributes</h3>
      {rows.length === 0 && <p className="field__hint">No attributes yet.</p>}
      {rows.map((row, index) => (
        <fieldset key={row.key} className="attribute-row">
          <legend className="visually-hidden">Attribute {index + 1}</legend>
          <Field label="Name">
            <input value={row.name} required onChange={(event) => update(row.key, { name: event.target.value })} />
          </Field>
          <Field label="Kind">
            <select value={row.kind} onChange={(event) => update(row.key, { kind: event.target.value as AttributeKind })}>
              {KINDS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </Field>
          {row.kind === 'LIST' ? (
            <Field label="Allowed values" hint="Separate the values with commas.">
              <input
                value={row.allowedValues}
                required
                onChange={(event) => update(row.key, { allowedValues: event.target.value })}
              />
            </Field>
          ) : (
            <span />
          )}
          <Button
            variant="secondary"
            aria-label={`Remove attribute ${index + 1}`}
            onClick={() => setRows((current) => current.filter((other) => other.key !== row.key))}
          >
            Remove
          </Button>
        </fieldset>
      ))}
      <div className="actions">
        <Button
          variant="secondary"
          onClick={() => setRows((current) => [...current, { key: nextKey++, name: '', kind: 'TEXT', allowedValues: '' }])}
        >
          Add attribute
        </Button>
      </div>

      <ProblemMessage error={error} />
      <div className="actions">
        <Button type="submit" busy={busy}>
          {submitLabel}
        </Button>
      </div>
    </form>
  );
}
