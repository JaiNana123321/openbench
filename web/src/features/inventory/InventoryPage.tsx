import { Link, useSearchParams } from 'react-router';
import type { Resource, ResourceType } from '../../api/types';
import { useCurrentMember } from '../../auth/currentMember';
import { hasAnyRole } from '../../auth/roles';
import { ErrorPage } from '../../shell/ErrorPage';
import { Button, EmptyState, Field, Loading, PageHeader, Table, formatEnum, type Column } from '../../ui';
import { attributeSummary } from './AttributeFields';
import { RESOURCE_PAGE_SIZE, useResourceTypes, useResources } from './queries';

const typeColumns: Column<ResourceType>[] = [
  { header: 'Resource Type', cell: (type) => type.name },
  { header: 'Allocation Class', cell: (type) => formatEnum(type.allocationClass) },
  {
    header: 'Attributes',
    cell: (type) => (type.attributes.length === 0 ? '—' : type.attributes.map((a) => a.name).join(', ')),
  },
  { header: 'Pool Capacity', cell: (type) => type.poolCapacity ?? '—', numeric: true },
];

const resourceColumns: Column<Resource>[] = [
  { header: 'Resource', cell: (resource) => <Link to={`/inventory/resources/${resource.id}`}>{resource.name}</Link> },
  { header: 'Type', cell: (resource) => resource.typeName },
  { header: 'Owner', cell: (resource) => resource.ownerName },
  { header: 'Attributes', cell: (resource) => attributeSummary(resource.attributes) },
  { header: 'Units', cell: (resource) => resource.units, numeric: true },
];

/** /inventory: every Resource Type with its class and Pool Capacity, and the Resources by type. */
export function InventoryPage() {
  const me = useCurrentMember();
  const [params, setParams] = useSearchParams();
  const type = params.get('type') ? Number(params.get('type')) : undefined;
  const page = Number(params.get('page') ?? 0);
  const types = useResourceTypes();
  const resources = useResources({ type, page });

  if (types.isError) return <ErrorPage error={types.error} />;
  if (resources.isError) return <ErrorPage error={resources.error} />;

  const pages = resources.data ? Math.ceil(resources.data.totalElements / RESOURCE_PAGE_SIZE) : 0;
  const show = (next: { type?: number; page?: number }) => {
    const search = new URLSearchParams();
    if (next.type !== undefined) search.set('type', String(next.type));
    if (next.page) search.set('page', String(next.page));
    setParams(search);
  };

  return (
    <>
      <PageHeader
        title="Inventory"
        subtitle="Every Resource Type and the Resources that exist."
        actions={
          hasAnyRole(me, ['RESOURCE_OWNER']) && (
            <Link to="/inventory/resources/new" className="button button--primary">
              Add Resource
            </Link>
          )
        }
      />

      <section className="section" aria-labelledby="types-title">
        <h2 id="types-title" className="section-title">
          Resource Types
        </h2>
        {types.isPending ? (
          <Loading />
        ) : (
          <Table
            label="Resource Types"
            columns={typeColumns}
            rows={types.data}
            rowKey={(row) => row.id}
            empty={<EmptyState title="No Resource Types yet." />}
          />
        )}
      </section>

      <section className="section" aria-labelledby="resources-title">
        <h2 id="resources-title" className="section-title">
          Resources
        </h2>
        <div className="toolbar">
          <Field label="Type">
            <select
              value={type ?? ''}
              onChange={(event) => show({ type: event.target.value ? Number(event.target.value) : undefined })}
            >
              <option value="">All types</option>
              {(types.data ?? [])
                .filter((choice) => choice.allocationClass !== 'MATCHED')
                .map((choice) => (
                  <option key={choice.id} value={choice.id}>
                    {choice.name}
                  </option>
                ))}
            </select>
          </Field>
        </div>
        {resources.isPending ? (
          <Loading />
        ) : (
          <>
            <Table
              label="Resources"
              columns={resourceColumns}
              rows={resources.data.content}
              rowKey={(row) => row.id}
              empty={<EmptyState title="No Resources match." />}
            />
            {pages > 1 && (
              <div className="pager">
                <Button variant="secondary" disabled={page === 0} onClick={() => show({ type, page: page - 1 })}>
                  Previous
                </Button>
                <span>
                  Page {page + 1} of {pages}
                </span>
                <Button variant="secondary" disabled={page + 1 >= pages} onClick={() => show({ type, page: page + 1 })}>
                  Next
                </Button>
              </div>
            )}
          </>
        )}
      </section>
    </>
  );
}
