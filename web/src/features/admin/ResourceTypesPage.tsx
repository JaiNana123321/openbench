import { Link, useNavigate, useParams } from 'react-router';
import type { ResourceType } from '../../api/types';
import { ErrorPage } from '../../shell/ErrorPage';
import {
  Button,
  EmptyState,
  Loading,
  PageHeader,
  ProblemMessage,
  StatusBadge,
  Table,
  formatEnum,
  type Column,
} from '../../ui';
import { ResourceTypeForm } from './ResourceTypeForm';
import { useAllResourceTypes, useChangeType, useDefineType, useResourceType, useRetireType } from './queries';

const columns: Column<ResourceType>[] = [
  { header: 'Resource Type', cell: (type) => <Link to={`/admin/types/${type.id}`}>{type.name}</Link> },
  { header: 'Allocation Class', cell: (type) => formatEnum(type.allocationClass) },
  {
    header: 'Attributes',
    cell: (type) =>
      type.attributes.length === 0
        ? '—'
        : type.attributes.map((attribute) => `${attribute.name} (${formatEnum(attribute.kind)})`).join(', '),
  },
  { header: 'Pool Capacity', cell: (type) => type.poolCapacity ?? '—', numeric: true },
  {
    header: 'Status',
    cell: (type) => (type.retired ? <StatusBadge status="RETIRED" /> : <StatusBadge status="ACTIVE" tone="success" />),
  },
];

/** /admin/types: every Resource Type, retired ones included, for an Administrator. */
export function ResourceTypesPage() {
  const types = useAllResourceTypes();
  if (types.isError) return <ErrorPage error={types.error} />;
  return (
    <>
      <PageHeader
        title="Resource Types"
        subtitle="Kinds of Resources, defined as data: a new kind needs no code change."
        actions={
          <Link to="/admin/types/new" className="button button--primary">
            Define Resource Type
          </Link>
        }
      />
      {types.isPending ? (
        <Loading />
      ) : (
        <Table
          label="Resource Types"
          columns={columns}
          rows={types.data}
          rowKey={(type) => type.id}
          empty={<EmptyState title="No Resource Types yet." />}
        />
      )}
    </>
  );
}

/** /admin/types/new: define a Resource Type. */
export function DefineTypePage() {
  const define = useDefineType();
  const navigate = useNavigate();
  return (
    <>
      <PageHeader title="Define Resource Type" />
      <ResourceTypeForm
        submitLabel="Define type"
        busy={define.isPending}
        error={define.error}
        onSubmit={(definition) => define.mutate(definition, { onSuccess: () => navigate('/admin/types') })}
      />
    </>
  );
}

/** /admin/types/:id: change or retire a Resource Type. */
export function ChangeTypePage() {
  const id = Number(useParams().id);
  const type = useResourceType(id);
  const change = useChangeType(id);
  const retire = useRetireType(id);
  const navigate = useNavigate();

  if (type.isError) return <ErrorPage error={type.error} />;
  if (type.isPending) return <Loading />;

  return (
    <>
      <PageHeader
        title={type.data.name}
        subtitle={type.data.retired ? <StatusBadge status="RETIRED" /> : formatEnum(type.data.allocationClass)}
        actions={
          <Link to="/admin/types" className="button button--secondary">
            All Resource Types
          </Link>
        }
      />
      <section className="section" aria-labelledby="change-title">
        <h2 id="change-title" className="section-title">
          Change
        </h2>
        <ResourceTypeForm
          key={type.data.id}
          initial={type.data}
          submitLabel="Save changes"
          busy={change.isPending}
          error={change.error}
          onSubmit={(definition) => change.mutate(definition, { onSuccess: () => navigate('/admin/types') })}
        />
      </section>
      {!type.data.retired && (
        <section className="section" aria-labelledby="retire-title">
          <h2 id="retire-title" className="section-title">
            Retire
          </h2>
          <div className="panel">
            <p>A retired type accepts no new Resources or Request Lines; existing ones are unaffected.</p>
            <ProblemMessage error={retire.error} />
            <Button variant="danger" busy={retire.isPending} onClick={() => retire.mutate()}>
              Retire {type.data.name}
            </Button>
          </div>
        </section>
      )}
    </>
  );
}
