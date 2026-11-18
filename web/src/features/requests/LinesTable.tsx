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
import { personAsk, personCount, whenText } from './personLineText';
import { TierSelect } from './TierSelect';
import { useRemoveLine, useSetPriority, useSetRequired, useSplitLine, useRequestSuggestions } from './queries';

interface LinesTableProps {
  requestId: Id;
  lines: RequestLine[];
  /** True in a Draft its Manager edits: the Required and Priority toggles, split, and remove are offered. */
  editable: boolean;
}

/**
 * The Request's lines with their Quotes; in an editable Draft, the Required mark, the Priority mark
 * (Exclusive lines only), the service tier (Metered lines only), split, and remove.
 */
export function LinesTable({ requestId, lines, editable }: LinesTableProps) {
  const setRequired = useSetRequired(requestId);
  const setPriority = useSetPriority(requestId);
  const split = useSplitLine(requestId);
  const remove = useRemoveLine(requestId);
  const suggestions = useRequestSuggestions(requestId, editable && lines.length > 0);
  const number = new Map(lines.map((line, index) => [line.id, index + 1]));

  const columns: Column<RequestLine>[] = [
    { header: '#', cell: (line) => number.get(line.id), numeric: true },
    { header: 'Resource Type', cell: (line) => `${line.typeName} (${formatEnum(line.allocationClass)})` },
    {
      header: 'Resource',
      cell: (line) =>
        personAsk(line) ?? line.namedResourceName ?? (line.allocationClass === 'EXCLUSIVE' ? 'Any' : '—'),
    },
    { header: 'Quantity', cell: (line) => amountText(line), numeric: true },
    { header: 'Window', cell: (line) => whenText(line) },
    { header: 'Quote', cell: (line) => quoteText(line), numeric: true },
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
    {
      header: 'Priority',
      cell: (line) =>
        editable && line.allocationClass === 'EXCLUSIVE' ? (
          <label className="checkbox">
            <input
              type="checkbox"
              checked={line.priority}
              disabled={setPriority.isPending}
              aria-label={`Line ${number.get(line.id)} Priority`}
              onChange={(event) => setPriority.mutate({ lineId: line.id, priority: event.target.checked })}
            />
            {line.priority ? 'Priority' : 'Standard'}
          </label>
        ) : line.priority ? (
          'Priority'
        ) : (
          '—'
        ),
    },
    {
      header: 'Tier',
      cell: (line) =>
        editable && line.allocationClass === 'METERED' ? (
          <TierSelect requestId={requestId} line={line} number={number.get(line.id) ?? 0} />
        ) : (
          (line.tierName ?? '—')
        ),
    },
  ];
  // Only lines linked to an accepted Suggestion fill this column, so it shows only when one does.
  const linked = new Set(suggestions.data?.map((s) => s.acceptedLineId));
  if (editable && lines.some((line) => linked.has(line.id))) {
    columns.push({
      header: 'Suggestions',
      cell: (line) => (
        <ul>
          {suggestions.data?.filter((s) => s.acceptedLineId === line.id).map((s) => (
            <li key={s.id}>
              #{s.id} · {s.memberName} · {s.proposal.quantity} · {s.note ?? 'No note'}
              <br />
              {formatWindow({ start: s.proposal.start, end: s.proposal.end })}
            </li>
          ))}
        </ul>
      ),
    });
  }
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
      <ProblemMessage error={suggestions.error} />
      <ProblemMessage error={setRequired.error ?? setPriority.error ?? split.error ?? remove.error} />
    </section>
  );
}

/** The Quote, with the demand surcharge multiplier in it when one applies: "90.00 ×1.5 demand". */
function quoteText(line: RequestLine) {
  if (line.quote === null) return 'No price';
  if (!line.surcharge) return formatCredits(line.quote);
  return (
    <>
      {formatCredits(line.quote)}
      <span className="quote-note">×{line.surcharge} demand</span>
    </>
  );
}

/** The window, and for a weekly line how many weeks it repeats: "Wed Dec 2, 10:00 to 12:00, weekly ×3". */
function amountText(line: RequestLine): string {
  const people = personCount(line);
  if (people) return people;
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
