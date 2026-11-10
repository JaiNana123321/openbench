import { useState, type FormEvent } from 'react';
import type { Id, Instant, LineDetail, RefundQuote, ReleaseBody, ReleaseKind } from '../../api/types';
import { Button, Field, formatCredits, formatWindow, ProblemMessage, toInstant } from '../../ui';
import { useReleaseLine, useReleasePreview } from './queries';

const KIND_LABELS: Record<ReleaseKind, string> = {
  WHOLE: 'The whole line',
  EARLIER_END: 'Everything after an earlier end',
  OCCURRENCES: 'Some occurrences',
  FEWER_UNITS: 'Some units',
};

/**
 * Releases an allocated line, in whole or in part (Release.Line). The Manager picks what to give
 * back; the server previews the Refund under the Refund Schedule (Release.Refund) before the
 * Manager confirms. Occurrences are offered for an Exclusive line, units for a Metered one.
 */
export function ReleaseForm({
  requestId,
  detail,
  number,
  onClose,
}: {
  requestId: Id;
  detail: LineDetail;
  number: number | undefined;
  onClose: () => void;
}) {
  const { line, allocatedParts } = detail;
  const metered = line.allocationClass === 'METERED';
  const kinds: ReleaseKind[] = ['WHOLE', 'EARLIER_END', metered ? 'FEWER_UNITS' : 'OCCURRENCES'];
  const [kind, setKind] = useState<ReleaseKind>('WHOLE');
  const [newEnd, setNewEnd] = useState('');
  const [newUnits, setNewUnits] = useState('');
  const [occurrences, setOccurrences] = useState<Instant[]>([]);
  const release = useReleaseLine(requestId);

  const body = releaseBody(kind, newEnd, newUnits, occurrences);
  const preview = useReleasePreview(requestId, line.id, body);
  const held = allocatedParts[0]?.amount ?? line.quantity;

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (body) release.mutate({ lineId: line.id, body });
  }

  function toggle(start: Instant, checked: boolean) {
    setOccurrences((chosen) => (checked ? [...chosen, start] : chosen.filter((other) => other !== start)));
  }

  if (release.isSuccess) {
    return (
      <section className="section panel" aria-labelledby="release-title">
        <h2 id="release-title" className="section-title">
          Line {number} released
        </h2>
        <p role="status">
          The line is now {release.data.line.state === 'RELEASED' ? 'Released' : 'Partly released'}. A Refund of{' '}
          {formatCredits(release.data.refund.refund)} credits was posted.
        </p>
        <div className="actions">
          <Button variant="secondary" onClick={onClose}>
            Close
          </Button>
        </div>
      </section>
    );
  }

  return (
    <section className="section panel" aria-labelledby="release-title">
      <h2 id="release-title" className="section-title">
        Release line {number}
      </h2>
      <p>
        Allocated now:{' '}
        {allocatedParts.map((part) => `${formatWindow(part.window)} (${amountText(metered, part.amount)})`).join('; ')}
      </p>
      <form className="form" onSubmit={handleSubmit}>
        <Field label="Give back">
          <select value={kind} onChange={(event) => setKind(event.target.value as ReleaseKind)}>
            {kinds.map((choice) => (
              <option key={choice} value={choice}>
                {KIND_LABELS[choice]}
              </option>
            ))}
          </select>
        </Field>
        {kind === 'EARLIER_END' && (
          <Field label="New end" hint="On a whole hour; every hour from then on is given back.">
            <input type="datetime-local" step={3600} value={newEnd} onChange={(event) => setNewEnd(event.target.value)} />
          </Field>
        )}
        {kind === 'FEWER_UNITS' && (
          <Field label="Units to keep" hint={`From 1 to ${held - 1}; the others are given back.`}>
            <input
              type="number"
              min={1}
              max={held - 1}
              value={newUnits}
              onChange={(event) => setNewUnits(event.target.value)}
            />
          </Field>
        )}
        {kind === 'OCCURRENCES' && (
          <fieldset className="field">
            <legend className="field__label">Occurrences to give back</legend>
            {allocatedParts.map((part) => (
              <label key={part.window.start} className="checkbox">
                <input
                  type="checkbox"
                  checked={occurrences.includes(part.window.start)}
                  onChange={(event) => toggle(part.window.start, event.target.checked)}
                />
                {formatWindow(part.window)}
              </label>
            ))}
          </fieldset>
        )}

        {preview.isError && <ProblemMessage error={preview.error} />}
        {preview.data && <RefundPreview quote={preview.data} metered={metered} />}

        <ProblemMessage error={release.error} />
        <div className="actions">
          <Button type="submit" variant="danger" busy={release.isPending} disabled={!preview.isSuccess}>
            Release
          </Button>
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
        </div>
      </form>
    </section>
  );
}

function RefundPreview({ quote, metered }: { quote: RefundQuote; metered: boolean }) {
  return (
    <div aria-label="Expected Refund">
      <p>
        <strong>Expected Refund: {formatCredits(quote.refund)} credits</strong> of {formatCredits(quote.attributable)}{' '}
        attributable to the part.
      </p>
      <ul>
        {quote.pieces.map((piece) => (
          <li key={`${piece.released.start}-${piece.released.end}`}>
            {formatWindow(piece.released)}, {amountText(metered, piece.amount)}:{' '}
            {piece.leadHours < 0 ? 'already started, ' : ''}
            {Math.round(piece.fraction * 100)}% of {formatCredits(piece.attributable)} ={' '}
            {formatCredits(piece.refund)}
          </li>
        ))}
      </ul>
    </div>
  );
}

/** The request body once the chosen kind has what it needs; undefined while it is incomplete. */
function releaseBody(kind: ReleaseKind, newEnd: string, newUnits: string, occurrences: Instant[]): ReleaseBody | undefined {
  switch (kind) {
    case 'WHOLE':
      return { kind };
    case 'EARLIER_END':
      return newEnd === '' ? undefined : { kind, newEnd: toInstant(newEnd) };
    case 'FEWER_UNITS':
      return newUnits === '' ? undefined : { kind, newUnits: Number(newUnits) };
    case 'OCCURRENCES':
      return occurrences.length === 0 ? undefined : { kind, occurrences };
  }
}

function amountText(metered: boolean, amount: number): string {
  if (metered) return `${amount} ${amount === 1 ? 'unit' : 'units'}`;
  return `${amount} ${amount === 1 ? 'instance' : 'instances'}`;
}
