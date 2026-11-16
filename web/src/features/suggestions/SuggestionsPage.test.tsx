import { fireEvent, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { Suggestion } from '../../api/types';
import { alice, ben } from '../../test/members';
import { json, mockApi, problem, session } from '../../test/mockApi';
import { renderApp } from '../../test/renderApp';
import { types } from '../inventory/fixtures';
import { aliceDraft } from '../requests/fixtures';

const suggestion: Suggestion = {
  id: 7, memberId: ben.id, memberName: ben.name, managerId: alice.id, managerName: alice.name,
  typeName: 'Meeting room', proposal: { typeId: 2, quantity: 1, start: '2026-12-02T10:00:00-05:00', end: '2026-12-02T12:00:00-05:00' },
  note: 'Design review', state: 'OPEN', acceptedLineId: null, decidedBy: null, decidedByName: null,
  decidedAt: null, reason: null, createdAt: '2026-11-15T10:00:00-05:00',
};
const page = (content: Suggestion[]) => ({ content, page: 0, size: 50, totalElements: content.length });
function serve(me = ben, routes = {}) {
  return mockApi({
    'GET /api/me': session(me),
    'GET /api/inventory/types': json(types),
    'GET /api/suggestions/mine': json(page([suggestion])),
    'GET /api/suggestions/team': json(page([suggestion])),
    'GET /api/requests': json({ content: [{ id: 42 }], page: 0, size: 50, totalElements: 1 }),
    'GET /api/requests/:id/draft': json(aliceDraft),
    ...routes,
  });
}

describe('Suggestions', () => {
  it('sends exactly one line to the own Manager and shows the original ask and state', async () => {
    const server = serve(ben, { 'POST /api/suggestions': json(suggestion, 201) });
    const { user } = renderApp('/suggestions');
    await screen.findByRole('option', { name: 'Meeting room (Exclusive)' });
    await user.selectOptions(screen.getByLabelText('Resource Type'), '2');
    fireEvent.change(screen.getByLabelText(/^Start/), { target: { value: '2026-12-02T10:00' } });
    fireEvent.change(screen.getByLabelText(/^End/), { target: { value: '2026-12-02T12:00' } });
    await user.type(screen.getByLabelText('Note'), 'Design review');
    await user.click(screen.getByRole('button', { name: 'Send Suggestion' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Suggestion sent to Alice Chen');
    expect(JSON.parse(server.callsTo('POST /api/suggestions')[0].body!)).toEqual({ lines: [suggestion.proposal], note: 'Design review' });
    const table = screen.getByRole('table', { name: 'Suggestions' });
    expect(within(table).getByText('Ben Ortiz → Alice Chen')).toBeInTheDocument();
    expect(within(table).getByText('Open')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Accept #/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Delete/ })).not.toBeInTheDocument();
  });

  it.each([false, true])('accepts into a Draft with existing line = %s', async existing => {
    const server = serve(alice, { 'POST /api/suggestions/:id/accept': json({ ...suggestion, state: 'ACCEPTED', acceptedLineId: 101 }) });
    const { user } = renderApp('/suggestions');
    await user.click(await screen.findByRole('button', { name: 'Accept #7' }));
    await user.selectOptions(await screen.findByLabelText('Draft for Suggestion #7'), '42');
    await screen.findByRole('option', { name: /Line #101/ });
    if (existing) await user.selectOptions(screen.getByLabelText('Target for Suggestion #7'), '101');
    await user.click(screen.getByRole('button', { name: 'Confirm accept' }));
    expect(server.callsTo('POST /api/suggestions/:id/accept')).toHaveLength(1);
    expect(JSON.parse(server.callsTo('POST /api/suggestions/:id/accept')[0].body!)).toEqual(existing ? { lineId: 101 } : { requestId: 42 });
    expect(server.callsTo('GET /api/requests')[0].query.get('team')).toBe('1');
    expect(server.callsTo('GET /api/requests')[0].query.get('state')).toBe('DRAFT');
  });

  it('declines with a reason and refreshes the recorded decision', async () => {
    let decided = false;
    const server = serve(alice, {
      'GET /api/suggestions/team': () => json(page([{ ...suggestion, ...(decided ? { state: 'DECLINED', reason: 'Use the shared node', decidedByName: alice.name, decidedAt: suggestion.createdAt } : {}) }])),
      'POST /api/suggestions/:id/decline': () => { decided = true; return json({ ...suggestion, state: 'DECLINED' }); },
    });
    const { user } = renderApp('/suggestions');
    await user.click(await screen.findByRole('button', { name: 'Decline #7' }));
    await user.type(screen.getByLabelText('Reason for Suggestion #7'), 'Use the shared node');
    await user.click(screen.getByRole('button', { name: 'Confirm decline' }));
    expect(await screen.findByText('Declined')).toBeInTheDocument();
    expect(screen.getByText('Use the shared node')).toBeInTheDocument();
    expect(JSON.parse(server.callsTo('POST /api/suggestions/:id/decline')[0].body!)).toEqual({ reason: 'Use the shared node' });
    expect(screen.queryByRole('button', { name: 'Accept #7' })).not.toBeInTheDocument();
  });

  it('shows the server refusal beside the decision', async () => {
    serve(alice, { 'POST /api/suggestions/:id/decline': problem(409, 'SUGGESTION_CLOSED', 'Only Open Suggestions can change.') });
    const { user } = renderApp('/suggestions');
    await user.click(await screen.findByRole('button', { name: 'Decline #7' }));
    await user.click(screen.getByRole('button', { name: 'Confirm decline' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Only Open Suggestions can change.');
  });

  it('lists both linked original Suggestions on the Draft line', async () => {
    serve(alice, {
      'GET /api/suggestions/request/:id': json([
        { ...suggestion, acceptedLineId: 101, state: 'ACCEPTED' },
        { ...suggestion, id: 8, note: 'Second room', acceptedLineId: 101, state: 'ACCEPTED' },
      ]),
      'GET /api/inventory/resources': json({ content: [], page: 0, size: 200, totalElements: 0 }),
    });
    renderApp('/requests/42');
    const table = await screen.findByRole('table', { name: 'Lines' });
    expect(await within(table).findByText(/#7 · Ben Ortiz · 1 · Design review/)).toBeInTheDocument();
    expect(within(table).getByText(/#8 · Ben Ortiz · 1 · Second room/)).toBeInTheDocument();
  });
});
