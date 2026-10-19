import { useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router';
import { fieldError } from '../../api/client';
import type { Resource, ResourceType } from '../../api/types';
import { useCurrentMember } from '../../auth/currentMember';
import { ErrorPage } from '../../shell/ErrorPage';
import { Button, Field, Loading, PageHeader, StatusBadge, formatEnum } from '../../ui';
import { AttributeFields } from './AttributeFields';
import { useEditResource, useResource, useResourceType, useRetireResource, useSetUnits } from './queries';
import { RefusalMessage } from './RefusalMessage';

/**
 * /inventory/resources/:id: one Resource. Its owner renames it and edits its attributes, sets the
 * units of a Metered Resource, and retires it; everyone else only reads.
 */
export function ResourcePage() {
  const id = Number(useParams().id);
  const me = useCurrentMember();
  const resource = useResource(id);
  const type = useResourceType(resource.data?.typeId);

  if (resource.isError) return <ErrorPage error={resource.error} />;
  if (type.isError) return <ErrorPage error={type.error} />;
  if (resource.isPending || type.isPending) return <Loading />;

  const owned = resource.data.ownerId === me.id && me.roles.includes('RESOURCE_OWNER') && !resource.data.retired;

  return (
    <>
      <PageHeader
        title={resource.data.name}
        subtitle={resource.data.retired ? <StatusBadge status="RETIRED" /> : `${resource.data.typeName} owned by ${resource.data.ownerName}`}
        actions={
          <Link to="/inventory" className="button button--secondary">
            Back to Inventory
          </Link>
        }
      />
      <section className="section panel" aria-labelledby="details-title">
        <h2 id="details-title" className="section-title">
          Details
        </h2>
        <dl className="details">
          <dt>Resource Type</dt>
          <dd>{resource.data.typeName}</dd>
          <dt>Allocation Class</dt>
          <dd>{formatEnum(type.data.allocationClass)}</dd>
          <dt>Owner</dt>
          <dd>{resource.data.ownerName}</dd>
          <dt>Units</dt>
          <dd>{resource.data.units}</dd>
          {type.data.attributes.map((attribute) => (
            <AttributeDetail key={attribute.id} name={attribute.name} value={resource.data.attributes[attribute.name]} />
          ))}
        </dl>
      </section>
      {owned && (
        <>
          <EditSection key={`edit-${resource.data.id}`} resource={resource.data} type={type.data} />
          {type.data.allocationClass === 'METERED' && <UnitsSection key={`units-${resource.data.units}`} resource={resource.data} />}
          <RetireSection resource={resource.data} />
        </>
      )}
    </>
  );
}

function AttributeDetail({ name, value }: { name: string; value: string | undefined }) {
  return (
    <>
      <dt>{name}</dt>
      <dd>{value ?? '—'}</dd>
    </>
  );
}

function EditSection({ resource, type }: { resource: Resource; type: ResourceType }) {
  const edit = useEditResource(resource.id);
  const [name, setName] = useState(resource.name);
  const [values, setValues] = useState<Record<string, string>>(resource.attributes);

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    edit.mutate({ name, attributes: values });
  }

  return (
    <section className="section" aria-labelledby="edit-title">
      <h2 id="edit-title" className="section-title">
        Edit
      </h2>
      <form className="panel form" onSubmit={handleSubmit}>
        <Field label="Name" error={fieldError(edit.error, 'name')}>
          <input value={name} required onChange={(event) => setName(event.target.value)} />
        </Field>
        <AttributeFields attributes={type.attributes} values={values} onChange={setValues} />
        <RefusalMessage error={edit.error} />
        <Button type="submit" busy={edit.isPending}>
          Save changes
        </Button>
      </form>
    </section>
  );
}

function UnitsSection({ resource }: { resource: Resource }) {
  const setUnits = useSetUnits(resource.id);
  const [units, setUnitsValue] = useState(String(resource.units));

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setUnits.mutate(Number(units));
  }

  return (
    <section className="section" aria-labelledby="units-title">
      <h2 id="units-title" className="section-title">
        Units
      </h2>
      <form className="panel form" onSubmit={handleSubmit}>
        <Field
          label="Units"
          hint="A reduction is refused while allocated and held units would exceed the new Pool Capacity."
          error={fieldError(setUnits.error, 'units')}
        >
          <input type="number" min={1} step={1} value={units} required onChange={(event) => setUnitsValue(event.target.value)} />
        </Field>
        <RefusalMessage error={setUnits.error} />
        <Button type="submit" busy={setUnits.isPending}>
          Set units
        </Button>
      </form>
    </section>
  );
}

function RetireSection({ resource }: { resource: Resource }) {
  const retire = useRetireResource(resource.id);
  return (
    <section className="section" aria-labelledby="retire-title">
      <h2 id="retire-title" className="section-title">
        Retire
      </h2>
      <div className="panel">
        <p>A retired Resource is never offered again. Retirement is refused while it is held or allocated.</p>
        <RefusalMessage error={retire.error} />
        <Button variant="danger" busy={retire.isPending} onClick={() => retire.mutate()}>
          Retire {resource.name}
        </Button>
      </div>
    </section>
  );
}
