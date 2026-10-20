import { useState, type FormEvent } from 'react';
import type { Id, RequestLine } from '../../api/types';
import {
  Button,
  EmptyState,
  formatCredits,
  formatEnum,
  formatWindow,
  ProblemMessage,
  StatusBadge,
  Table,
  type Column,
} from '../../ui';
import { useRemoveLine, useSetRequired, useSplitLine } from './queries';

interface LinesTableProps {
  requestId: Id;
  lines: RequestLine[];
  /** True in a Draft its Manager edits: the Required toggle, split, and remove are offered. */
  editable: boolean;
}

/** The Request's lines with their Quotes; in an editable Draft, the Required mark, split, and remove. */
export function LinesTable({ requestId, lines, editable }: LinesTableProps) {
  const setRequired = useSetRequired(requestId);
  const split = useSplitLine(requestId);
  const remove = useRemoveLine(requestId);
  const number = new Map(lines.map((line, index) => [line.id, index + 1]));

  const columns: Column<RequestLine>[] = [
    { header: '#', cell: (line) => number.get(line.id), numeric: true },
    { header: 'Resource Type', cell: (line) => `${line.typeName} (${formatEnum(line.allocationClass)})` },
    {
      header: 'Resource',
      cell: (line) => line.namedResourceName ?? (line.allocationClass === 'EXCLUSIVE' ? 'Any' : '—'),
    },
    { header: 'Quantity', cell: (line) => amountText(line), numeric: true },
    { header: 'Window', cell: (line) => formatWindow(line.window) },
    { header: 'Quote', cell: (line) => (line.quote === null ? 'No price' : formatCredits(line.quote)), numeric: true },
    {
      header: 'Required',
      cell: (line) =>
        editable ? (
          <label className="checkbox">
            <input
              type="checkbox"
              checked={line.required}
              disabled={setRequired.isPending}
              aria-label={`Line ${number.get(line.id)} Required`}
              onChange={(event) => setRequired.mutate({ lineId: line.id, required: event.target.checked })}
            />
            {line.required ? 'Required' : 'Optional'}
          </label>
        ) : line.required ? (
          'Required'
        ) : (
          'Optional'
        ),
    },
  ];
  if (editable) {
    columns.push({
      header: 'Actions',
      cell: (line) => (
        <div className="line-actions">
          <SplitControl
            line={line}
            number={number.get(line.id) ?? 0}
            busy={split.isPending}
            onSplit={(quantity) => split.mutate({ lineId: line.id, quantity })}
          />
          <Button
            variant="danger"
            busy={remove.isPending && remove.variables === line.id}
            aria-label={`Remove line ${number.get(line.id)}`}
            onClick={() => remove.mutate(line.id)}
          >
            Remove
          </Button>
        </div>
      ),
    });
  } else {
    columns.push({ header: 'State', cell: (line) => <StatusBadge status={line.state} /> });
  }

  return (
    <section className="section" aria-labelledby="lines-title">
      <h2 id="lines-title" className="section-title">
        Lines
      </h2>
      <Table
        label="Lines"
        columns={columns}
        rows={lines}
        rowKey={(line) => line.id}
        empty={<EmptyState title="No lines yet.">{editable && 'Add the first line above.'}</EmptyState>}
      />
      <ProblemMessage error={setRequired.error ?? split.error ?? remove.error} />
    </section>
  );
}

function amountText(line: RequestLine): string {
  if (line.allocationClass === 'METERED') return `${line.quantity} ${line.quantity === 1 ? 'unit' : 'units'}`;
  return `${line.quantity} ${line.quantity === 1 ? 'instance' : 'instances'}`;
}

interface SplitControlProps {
  line: RequestLine;
  number: number;
  busy: boolean;
  onSplit: (quantity: number) => void;
}

/** "Split": asks how many instances or units move to a new Optional line. A line of 1 cannot split. */
function SplitControl({ line, number, busy, onSplit }: SplitControlProps) {
  const [open, setOpen] = useState(false);
  const [quantity, setQuantity] = useState('1');
  if (line.quantity < 2) return null;

  if (!open) {
    return (
      <Button variant="secondary" aria-label={`Split line ${number}`} onClick={() => setOpen(true)}>
        Split
      </Button>
    );
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    onSplit(Number(quantity));
    setOpen(false);
    setQuantity('1');
  }

  return (
    <form className="split-form" onSubmit={handleSubmit}>
      <label>
        <span className="visually-hidden">Quantity for the new Optional line {number}</span>
        <input
          type="number"
          min={1}
          max={line.quantity - 1}
          step={1}
          value={quantity}
          required
          onChange={(event) => setQuantity(event.target.value)}
        />
      </label>
      <Button type="submit" variant="secondary" busy={busy}>
        Move to new line
      </Button>
      <Button variant="secondary" onClick={() => setOpen(false)}>
        Cancel
      </Button>
    </form>
  );
}
