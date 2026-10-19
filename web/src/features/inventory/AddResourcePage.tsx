import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import { fieldError } from '../../api/client';
import { ErrorPage } from '../../shell/ErrorPage';
import { Button, Field, Loading, PageHeader, ProblemMessage } from '../../ui';
import { AttributeFields } from './AttributeFields';
import { useAddResource, useResourceTypes } from './queries';

/**
 * /inventory/resources/new: a Resource Owner adds a Resource. The fields follow the chosen type's
 * attributes, and a Metered type also asks for units; the caller becomes the owner.
 */
export function AddResourcePage() {
  const types = useResourceTypes();
  const add = useAddResource();
  const navigate = useNavigate();
  const [typeId, setTypeId] = useState('');
  const [name, setName] = useState('');
  const [units, setUnits] = useState('');
  const [values, setValues] = useState<Record<string, string>>({});

  if (types.isError) return <ErrorPage error={types.error} />;
  if (types.isPending) return <Loading />;

  // A Matched type's Resources are Members, so only Exclusive and Metered types take new Resources.
  const choices = types.data.filter((type) => type.allocationClass !== 'MATCHED');
  const type = choices.find((choice) => String(choice.id) === typeId);
  const metered = type?.allocationClass === 'METERED';

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!type) return;
    add.mutate(
      { typeId: type.id, name, units: metered ? Number(units) : undefined, attributes: values },
      { onSuccess: (resource) => navigate(`/inventory/resources/${resource.id}`) },
    );
  }

  return (
    <>
      <PageHeader title="Add Resource" subtitle="You become the owner of the Resource you add." />
      <form className="panel form" onSubmit={handleSubmit}>
        <Field label="Resource Type" error={fieldError(add.error, 'typeId')}>
          <select
            value={typeId}
            required
            onChange={(event) => {
              setTypeId(event.target.value);
              setValues({});
            }}
          >
            <option value="">Choose a type</option>
            {choices.map((choice) => (
              <option key={choice.id} value={choice.id}>
                {choice.name}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Name" error={fieldError(add.error, 'name')}>
          <input value={name} required onChange={(event) => setName(event.target.value)} />
        </Field>
        {metered && (
          <Field label="Units" hint="How many units this Resource supplies at once." error={fieldError(add.error, 'units')}>
            <input type="number" min={1} step={1} value={units} required onChange={(event) => setUnits(event.target.value)} />
          </Field>
        )}
        {type && <AttributeFields attributes={type.attributes} values={values} onChange={setValues} />}
        <ProblemMessage error={add.error} />
        <div className="actions">
          <Button type="submit" busy={add.isPending} disabled={!type}>
            Add Resource
          </Button>
          <Link to="/inventory" className="button button--secondary">
            Cancel
          </Link>
        </div>
      </form>
    </>
  );
}
