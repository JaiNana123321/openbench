import { useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router';
import { isApiError } from '../../api/client';
import type { Instant, Resource, ResourceType } from '../../api/types';
import { useCurrentMember } from '../../auth/currentMember';
import { hasAnyRole } from '../../auth/roles';
import { ErrorPage } from '../../shell/ErrorPage';
import {
  Button,
  EmptyState,
  Field,
  Loading,
  PageHeader,
  ProblemMessage,
  Table,
  formatEnum,
  toInstant,
  toPickerValue,
  type Column,
} from '../../ui';
import { AttributeFields, attributeSummary } from './AttributeFields';
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

/** Query parameters `attr.<name>=<value>` filter by attribute value, as on the API. */
const ATTRIBUTE_PARAMETER = 'attr.';

interface ListState {
  type?: number;
  page?: number;
  attributes?: Record<string, string>;
  free?: { from: Instant; to: Instant };
}

/**
 * /inventory: every Resource Type with its class and Pool Capacity, and the Resources, filtered by
 * type, by attribute value, and, for an Exclusive type, by being free for a whole window
 * (SRS Inventory.View.Filter). The filters live in the address, so a filtered list can be shared.
 */
export function InventoryPage() {
  const me = useCurrentMember();
  const [params, setParams] = useSearchParams();
  const type = params.get('type') ? Number(params.get('type')) : undefined;
  const page = Number(params.get('page') ?? 0);
  const attributes: Record<string, string> = {};
  params.forEach((value, name) => {
    if (name.startsWith(ATTRIBUTE_PARAMETER)) attributes[name.slice(ATTRIBUTE_PARAMETER.length)] = value;
  });
  const from = params.get('from');
  const to = params.get('to');
  const free = type !== undefined && from && to ? { from, to } : undefined;
  const types = useResourceTypes();
  const resources = useResources({ type, page, attributes, free });

  if (types.isError) return <ErrorPage error={types.error} />;
  const refused = resources.isError && isApiError(resources.error) && [400, 404, 422].includes(resources.error.status);
  if (resources.isError && !refused) return <ErrorPage error={resources.error} />;

  const selectedType = types.data?.find((choice) => choice.id === type);
  const pages = resources.data ? Math.ceil(resources.data.totalElements / RESOURCE_PAGE_SIZE) : 0;
  const show = (next: ListState) => {
    const search = new URLSearchParams();
    if (next.type !== undefined) search.set('type', String(next.type));
    for (const [name, value] of Object.entries(next.attributes ?? {})) search.set(ATTRIBUTE_PARAMETER + name, value);
    if (next.free) {
      search.set('from', next.free.from);
      search.set('to', next.free.to);
    }
    if (next.page) search.set('page', String(next.page));
    setParams(search);
  };
  const turnTo = (next: number) => show({ type, attributes, free, page: next });

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
        {selectedType && (selectedType.attributes.length > 0 || selectedType.allocationClass === 'EXCLUSIVE') && (
          <FilterForm
            key={params.toString()}
            type={selectedType}
            attributes={attributes}
            free={free}
            onApply={(next) => show({ type, ...next })}
          />
        )}
        {resources.isError ? (
          <ProblemMessage error={resources.error} />
        ) : resources.isPending ? (
          <Loading />
        ) : (
          <>
            <Table
              label="Resources"
              columns={resourceColumns}
              rows={resources.data.content}
              rowKey={(row) => row.id}
              empty={<EmptyState title={free ? 'No Resources match that are free for the whole window.' : 'No Resources match.'} />}
            />
            {pages > 1 && (
              <div className="pager">
                <Button variant="secondary" disabled={page === 0} onClick={() => turnTo(page - 1)}>
                  Previous
                </Button>
                <span>
                  Page {page + 1} of {pages}
                </span>
                <Button variant="secondary" disabled={page + 1 >= pages} onClick={() => turnTo(page + 1)}>
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

interface FilterFormProps {
  type: ResourceType;
  attributes: Record<string, string>;
  free: { from: Instant; to: Instant } | undefined;
  onApply: (filter: Pick<ListState, 'attributes' | 'free'>) => void;
}

/**
 * The filters of one type: a value for each of its attributes, and for an Exclusive type a window
 * the Resources must be free for. Back-to-back windows do not overlap: a Resource allocated until
 * 12:00 is free from 12:00.
 */
function FilterForm({ type, attributes, free, onApply }: FilterFormProps) {
  const [values, setValues] = useState(attributes);
  const [from, setFrom] = useState(free ? toPickerValue(free.from) : '');
  const [to, setTo] = useState(free ? toPickerValue(free.to) : '');
  const exclusive = type.allocationClass === 'EXCLUSIVE';

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const given = Object.fromEntries(Object.entries(values).filter(([, value]) => value !== ''));
    onApply({ attributes: given, free: exclusive && from && to ? { from: toInstant(from), to: toInstant(to) } : undefined });
  }

  return (
    <form className="filter-form" aria-label="Filters" onSubmit={handleSubmit}>
      <AttributeFields attributes={type.attributes} values={values} onChange={setValues} />
      {exclusive && (
        <>
          <Field label="Free from">
            <input type="datetime-local" step={3600} value={from} onChange={(event) => setFrom(event.target.value)} />
          </Field>
          <Field label="Free to">
            <input type="datetime-local" step={3600} value={to} onChange={(event) => setTo(event.target.value)} />
          </Field>
        </>
      )}
      <div className="filter-form__actions">
        <Button type="submit" variant="secondary">
          Apply filters
        </Button>
        <Button type="button" variant="secondary" onClick={() => onApply({})}>
          Clear filters
        </Button>
      </div>
    </form>
  );
}
