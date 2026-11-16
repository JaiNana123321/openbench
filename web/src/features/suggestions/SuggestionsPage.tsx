import { useState, type FormEvent } from 'react';
import { useCurrentMember } from '../../auth/currentMember';
import type { Suggestion } from '../../api/types';
import { ErrorPage } from '../../shell/ErrorPage';
import { Button, EmptyState, Field, formatEnum, formatInstant, formatWindow, Loading, PageHeader, ProblemMessage, StatusBadge, Table, toInstant } from '../../ui';
import { useCreateSuggestion, useDecideSuggestion, useSuggestionDraft, useSuggestionDrafts, useSuggestions, useSuggestionSkills, useSuggestionTypes } from './queries';

export function SuggestionsPage() {
  const me = useCurrentMember();
  const [team, setTeam] = useState(me.roles.includes('MANAGER'));
  const [page, setPage] = useState(0);
  const suggestions = useSuggestions(team, page);
  if (suggestions.isError) return <ErrorPage error={suggestions.error} />;
  return <>
    <PageHeader title="Suggestions" subtitle="Send one line to your own Manager. Your Suggestion keeps its original ask." />
    <CreateSuggestion />
    {me.roles.includes('MANAGER') && <div className="actions">
      <Button variant={team ? 'primary' : 'secondary'} onClick={() => { setTeam(true); setPage(0); }}>Team Suggestions</Button>
      <Button variant={!team ? 'primary' : 'secondary'} onClick={() => { setTeam(false); setPage(0); }}>My Suggestions</Button>
    </div>}
    {suggestions.isPending ? <Loading /> : <>
      <Table label="Suggestions" rows={suggestions.data.content} rowKey={s => s.id}
        empty={<EmptyState title="No Suggestions yet." />}
        columns={[
          { header: 'Suggestion', cell: s => `#${s.id}: ${s.typeName}` },
          { header: 'Member / Manager', cell: s => `${s.memberName} → ${s.managerName ?? 'No Manager'}` },
          { header: 'Original ask', cell: s => <>{s.proposal.quantity} · {formatWindow({ start: s.proposal.start, end: s.proposal.end })}{s.proposal.recurrence && ` · weekly ×${s.proposal.recurrence.occurrences}`}</> },
          { header: 'Note', cell: s => s.note ?? '—' },
          { header: 'State', cell: s => <><StatusBadge status={s.state} />{s.acceptedLineId && <p>Linked to line #{s.acceptedLineId}</p>}{s.reason && <p>{s.reason}</p>}{s.decidedAt && <p>{s.decidedByName} · {formatInstant(s.decidedAt)}</p>}</> },
          ...(team ? [{ header: 'Decision', cell: (s: Suggestion) => s.state === 'OPEN' ? <Decision suggestion={s} /> : '—' }] : []),
        ]} />
      {suggestions.data.totalElements > 50 && <div className="pager">
        <Button variant="secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</Button>
        <span>Page {page + 1}</span>
        <Button variant="secondary" disabled={(page + 1) * 50 >= suggestions.data.totalElements} onClick={() => setPage(page + 1)}>Next</Button>
      </div>}
    </>}
  </>;
}

function CreateSuggestion() {
  const types = useSuggestionTypes();
  const create = useCreateSuggestion();
  const [typeId, setTypeId] = useState('');
  const [quantity, setQuantity] = useState('1');
  const [start, setStart] = useState('');
  const [end, setEnd] = useState('');
  const [note, setNote] = useState('');
  const [skills, setSkills] = useState<string[]>([]);
  const type = types.data?.find(t => String(t.id) === typeId);
  const matched = type?.allocationClass === 'MATCHED';
  const offeredSkills = useSuggestionSkills(matched);
  function submit(event: FormEvent) {
    event.preventDefault();
    create.mutate({ lines: [{ typeId: Number(typeId), quantity: Number(quantity), start: toInstant(start), end: toInstant(end), ...(matched ? { skills: skills.map(Number) } : {}) }], note },
      { onSuccess: () => setNote('') });
  }
  return <section className="section panel" aria-label="New Suggestion">
    <h2 className="section-title">New Suggestion</h2>
    <p>Addressed to your Team’s Manager. No Hold or credits are committed.</p>
    <form onSubmit={submit}>
      <div className="line-form">
        <Field label="Resource Type"><select required disabled={types.isPending} value={typeId} onChange={e => { setTypeId(e.target.value); setSkills([]); }}>
          <option value="">Choose a type</option>
          {types.data?.map(t => <option key={t.id} value={t.id}>{t.name} ({formatEnum(t.allocationClass)})</option>)}
        </select></Field>
        <Field label={type?.allocationClass === 'METERED' ? 'Units' : 'Quantity'}><input type="number" required min={1} step={1} value={quantity} onChange={e => setQuantity(e.target.value)} /></Field>
        <Field label="Start" hint={matched ? 'Midnight of the first day, America/New_York.' : 'Whole hours, America/New_York.'}><input type="datetime-local" step={3600} required value={start} onChange={e => setStart(e.target.value)} /></Field>
        <Field label="End" hint={matched ? 'Midnight after the last day.' : 'The end hour is not included.'}><input type="datetime-local" step={3600} required value={end} onChange={e => setEnd(e.target.value)} /></Field>
        {matched && <Field label="Skills"><select multiple value={skills} onChange={e => setSkills(Array.from(e.target.selectedOptions, o => o.value))}>
          {offeredSkills.data?.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
        </select></Field>}
        <Field label="Note"><textarea value={note} onChange={e => setNote(e.target.value)} /></Field>
      </div>
      <ProblemMessage error={types.error ?? offeredSkills.error ?? create.error} />
      <div className="actions"><Button type="submit" disabled={!type} busy={create.isPending}>Send Suggestion</Button></div>
      {create.isSuccess && <p role="status">Suggestion sent to {create.data.managerName}.</p>}
    </form>
  </section>;
}

function Decision({ suggestion }: { suggestion: Suggestion }) {
  const [mode, setMode] = useState<'accept' | 'decline' | null>(null);
  const [requestId, setRequestId] = useState('');
  const [lineId, setLineId] = useState('');
  const [reason, setReason] = useState('');
  const [draftPage, setDraftPage] = useState(0);
  const decide = useDecideSuggestion(suggestion.id);
  return <div>
    <div className="actions">
      <Button variant="secondary" onClick={() => setMode('accept')}>Accept #{suggestion.id}</Button>
      <Button variant="secondary" onClick={() => setMode('decline')}>Decline #{suggestion.id}</Button>
    </div>
    {mode === 'accept' && <DraftPicker suggestion={suggestion} page={draftPage} setPage={setDraftPage}
      requestId={requestId} lineId={lineId} setRequestId={value => { setRequestId(value); setLineId(''); }} setLineId={setLineId}
      busy={decide.isPending} accept={() => decide.mutate({ action: 'accept', ...(lineId ? { lineId: Number(lineId) } : { requestId: Number(requestId) }) })} />}
    {mode === 'decline' && <form onSubmit={e => { e.preventDefault(); decide.mutate({ action: 'decline', reason }); }}>
      <Field label={`Reason for Suggestion #${suggestion.id}`}><textarea value={reason} onChange={e => setReason(e.target.value)} /></Field>
      <Button type="submit" busy={decide.isPending}>Confirm decline</Button>
    </form>}
    <ProblemMessage error={decide.error} />
  </div>;
}

function DraftPicker({ suggestion, page, setPage, requestId, lineId, setRequestId, setLineId, accept, busy }: {
  suggestion: Suggestion; page: number; setPage: (page: number) => void;
  requestId: string; lineId: string; setRequestId: (id: string) => void; setLineId: (id: string) => void; accept: () => void; busy: boolean;
}) {
  const drafts = useSuggestionDrafts(page);
  const draft = useSuggestionDraft(requestId ? Number(requestId) : undefined);
  return <div>
    <Field label={`Draft for Suggestion #${suggestion.id}`}><select value={requestId} onChange={e => setRequestId(e.target.value)}>
      <option value="">Choose a Draft</option>
      {drafts.data?.content.map(r => <option key={r.id} value={r.id}>Request #{r.id}</option>)}
    </select></Field>
    {drafts.data && drafts.data.totalElements > 50 && <div className="pager">
      <Button variant="secondary" disabled={page === 0} onClick={() => { setPage(page - 1); setRequestId(''); }}>Previous Drafts</Button>
      <Button variant="secondary" disabled={(page + 1) * 50 >= drafts.data.totalElements} onClick={() => { setPage(page + 1); setRequestId(''); }}>Next Drafts</Button>
    </div>}
    {requestId && <Field label={`Target for Suggestion #${suggestion.id}`}><select value={lineId} onChange={e => setLineId(e.target.value)}>
      <option value="">Add a new line</option>
      {draft.data?.lines.filter(l => l.typeId === suggestion.proposal.typeId).map(l => <option key={l.id} value={l.id}>Line #{l.id} · {l.typeName} · {l.quantity} · {formatWindow(l.window)}</option>)}
    </select></Field>}
    <ProblemMessage error={drafts.error ?? draft.error} />
    <Button busy={busy} disabled={!requestId || draft.isPending || draft.isError} onClick={accept}>Confirm accept</Button>
  </div>;
}
