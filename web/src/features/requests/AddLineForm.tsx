import { useState, type FormEvent } from 'react';
import { fieldError } from '../../api/client';
import type { Id } from '../../api/types';
import { Button, COMPANY_TIME_ZONE, Field, formatEnum, ProblemMessage, toInstant } from '../../ui';
import { useAddLine, useLineTypes, useResourcesOfType } from './queries';

/**
 * Adds a line to a Draft: a Resource Type (Exclusive or Metered; People come later), for an
 * Exclusive type optionally one specific Resource, the instances or units, and a window on whole
 * hours. The server validates and prices it; the Draft view then refetches.
 */
export function AddLineForm({ requestId }: { requestId: Id }) {
  const types = useLineTypes();
  const add = useAddLine(requestId);
  const [typeId, setTypeId] = useState('');
  const [resourceId, setResourceId] = useState('');
  const [quantity, setQuantity] = useState('1');
  const [start, setStart] = useState('');
  const [end, setEnd] = useState('');

  const choices = (types.data ?? []).filter((type) => type.allocationClass !== 'MATCHED');
  const type = choices.find((choice) => String(choice.id) === typeId);
  const exclusive = type?.allocationClass === 'EXCLUSIVE';
  const resources = useResourcesOfType(exclusive ? type.id : undefined);

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!type) return;
    add.mutate(
      {
        typeId: type.id,
        quantity: Number(quantity),
        start: toInstant(start),
        end: toInstant(end),
        namedResourceId: exclusive && resourceId !== '' ? Number(resourceId) : undefined,
      },
      {
        onSuccess: () => {
          setResourceId('');
          setQuantity('1');
        },
      },
    );
  }

  return (
    <section className="section panel" aria-labelledby="add-line-title">
      <h2 id="add-line-title" className="section-title">
        Add a line
      </h2>
      <form onSubmit={handleSubmit}>
        <div className="line-form">
          <Field label="Resource Type" error={fieldError(add.error, 'typeId')}>
            <select
              value={typeId}
              required
              disabled={types.isPending}
              onChange={(event) => {
                setTypeId(event.target.value);
                setResourceId('');
              }}
            >
              <option value="">Choose a type</option>
              {choices.map((choice) => (
                <option key={choice.id} value={choice.id}>
                  {choice.name} ({formatEnum(choice.allocationClass)})
                </option>
              ))}
            </select>
          </Field>
          {exclusive && (
            <Field label="Specific Resource" hint="Optional: leave as any to take whichever is free.">
              <select value={resourceId} onChange={(event) => setResourceId(event.target.value)}>
                <option value="">Any {type.name}</option>
                {resources.data?.content.map((resource) => (
                  <option key={resource.id} value={resource.id}>
                    {resource.name}
                  </option>
                ))}
              </select>
            </Field>
          )}
          <Field
            label={type?.allocationClass === 'METERED' ? 'Units' : 'Quantity'}
            hint={type?.allocationClass === 'METERED' ? 'Units held at once.' : 'Instances.'}
            error={fieldError(add.error, 'quantity')}
          >
            <input
              type="number"
              min={1}
              step={1}
              value={quantity}
              required
              onChange={(event) => setQuantity(event.target.value)}
            />
          </Field>
          <Field label="Start" hint={`Whole hours, ${COMPANY_TIME_ZONE} time.`} error={fieldError(add.error, 'start')}>
            <input
              type="datetime-local"
              step={3600}
              value={start}
              required
              onChange={(event) => setStart(event.target.value)}
            />
          </Field>
          <Field label="End" hint="The end hour is not included." error={fieldError(add.error, 'end')}>
            <input
              type="datetime-local"
              step={3600}
              value={end}
              required
              onChange={(event) => setEnd(event.target.value)}
            />
          </Field>
        </div>
        <ProblemMessage error={add.error} />
        <div className="actions">
          <Button type="submit" busy={add.isPending} disabled={!type}>
            Add line
          </Button>
        </div>
      </form>
    </section>
  );
}
