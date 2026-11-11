import { fireEvent, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { DraftView, LineDetail, RequestDetail, RequestLine, Shortlist } from '../../api/types';
import { alice, erin } from '../../test/members';
import { json, mockApi, problem, session, type MockReply } from '../../test/mockApi';
import { renderApp } from '../../test/renderApp';
import { roomA, types } from '../inventory/fixtures';
import { aliceDraft, roomLine } from './fixtures';

// Alice's A1 of the test cases: M1, a Person with Statistics on Wed Nov 25 and Thu Nov 26, and Room A
// on Hold, Pending until Monday. Ids are made up.

const statistics = { id: 31, name: 'Statistics', retired: false };
const java = { id: 32, name: 'Java', retired: false };

const m1: RequestLine = {
  id: 103,
  requestId: 42,
  typeId: 1,
  typeName: 'Person',
  allocationClass: 'MATCHED',
  quantity: 1,
  window: { start: '2026-11-25T00:00:00-05:00', end: '2026-11-27T00:00:00-05:00' },
  namedResource: null,
  namedResourceName: null,
  required: true,
  state: 'AWAITING_APPROVAL',
  quote: null,
  namedMember: null,
  namedMemberName: null,
  skills: [statistics],
  days: { from: '2026-11-25', to: '2026-11-26' },
};

function detailOf(line: LineDetail['line'], candidates: LineDetail['candidates'] = []): RequestDetail {
  return {
    request: {
      id: 42,
      team: { id: 1, name: 'Platform' },
      state: 'PENDING',
      createdAt: '2026-11-12T09:00:00-05:00',
      submittedAt: '2026-11-12T10:00:00-05:00',
      windowEndsAt: '2026-11-16T10:00:00-05:00',
    },
    lines: [
      {
        line,
        assignedResources: [],
        reason: null,
        reasonMessage: null,
        waitlistPosition: null,
        allocatedParts: [],
        candidates,
      },
      {
        line: { ...roomLine, state: 'HELD' },
        assignedResources: [{ id: 21, name: 'Room A' }],
        reason: null,
        reasonMessage: null,
        waitlistPosition: null,
        allocatedParts: [],
        candidates: [],
      },
    ],
    history: [],
  };
}

const shortlist: Shortlist = {
  lineId: 103,
  candidates: [
    { position: 1, member: 7, name: 'Dave Kim', team: { id: 3, name: 'Operations' }, skills: ['Statistics'], personDaysLent: 0 },
    {
      position: 2,
      member: 4,
      name: 'Rita Gomez',
      team: { id: 2, name: 'Research' },
      skills: ['Microscopy', 'Statistics'],
      personDaysLent: 0,
    },
  ],
};

const pending = detailOf(m1);
const pendingDraftView: DraftView = { ...aliceDraft, request: pending.request };

function serve(detail: RequestDetail = pending, me = alice, routes: Record<string, MockReply> = {}) {
  return mockApi({
    'GET /api/me': session(me),
    'GET /api/requests/:id/draft': json({ ...pendingDraftView, request: detail.request }),
    'GET /api/requests/:id': json(detail),
    'GET /api/requests/:id/lines/:lineId/shortlist': json(shortlist),
    ...routes,
  });
}

describe('Person lines in the Request detail', () => {
  it('shows the Pending Request’s window, its Held line, and the Person line’s days and skills', async () => {
    serve();
    renderApp('/requests/42');

    expect(await screen.findByText(/Objection Window until/)).toBeInTheDocument();
    const lines = await screen.findByRole('table', { name: 'Lines' });
    const rows = within(lines).getAllByRole('row').slice(1);
    expect(within(rows[0]).getByText('Nov 25, 2026 to Nov 26, 2026')).toBeInTheDocument();
    expect(within(rows[0]).getByText('1 Person')).toBeInTheDocument();
    expect(within(rows[0]).getByText('Statistics')).toBeInTheDocument();
    expect(within(rows[1]).getByText('Held')).toBeInTheDocument();
  });

  it('shows the Shortlist with name, Team, skills, and person-days lent only, and selects nobody', async () => {
    serve();
    renderApp('/requests/42');

    const table = await screen.findByRole('table', { name: 'Shortlist of line 1' });
    const rows = within(table).getAllByRole('row');
    expect(within(rows[0]).getAllByRole('columnheader').map((cell) => cell.textContent)).toEqual([
      'Select',
      '#',
      'Name',
      'Team',
      'Skills',
      'Person-days lent',
    ]);
    expect(within(rows[1]).getByText('Dave Kim')).toBeInTheDocument();
    expect(within(rows[2]).getByText('Microscopy, Statistics')).toBeInTheDocument();
    expect(within(table).getAllByRole('checkbox').every((box) => !(box as HTMLInputElement).checked)).toBe(true);
    expect(screen.getByText('No Candidate is selected yet.')).toBeInTheDocument();
  });

  it('selects exactly the missing Candidates and sends them as the API takes them', async () => {
    const server = serve(pending, alice, {
      'POST /api/requests/:id/lines/:lineId/candidates': json(pending),
    });
    const { user } = renderApp('/requests/42');

    await user.click(await screen.findByRole('checkbox', { name: 'Select Dave Kim' }));
    const select = screen.getByRole('button', { name: 'Select 1 Candidate' });
    expect(select).not.toBeDisabled();
    await user.click(screen.getByRole('checkbox', { name: 'Select Rita Gomez' }));
    expect(select).toBeDisabled();
    await user.click(screen.getByRole('checkbox', { name: 'Select Rita Gomez' }));
    await user.click(select);

    await screen.findByRole('table', { name: 'Lines' });
    const [post] = server.callsTo('POST /api/requests/:id/lines/:lineId/candidates');
    expect(post.path).toBe('/api/requests/42/lines/103/candidates');
    expect(JSON.parse(post.body ?? '')).toEqual({ members: [7] });
  });

  it('shows a refused selection as the server words it', async () => {
    serve(pending, alice, {
      'POST /api/requests/:id/lines/:lineId/candidates': problem(422, 'NOT_A_CANDIDATE', 'Dave Kim is not on the line’s Shortlist.'),
    });
    const { user } = renderApp('/requests/42');

    await user.click(await screen.findByRole('checkbox', { name: 'Select Dave Kim' }));
    await user.click(screen.getByRole('button', { name: 'Select 1 Candidate' }));

    expect(await screen.findByText('Dave Kim is not on the line’s Shortlist.')).toBeInTheDocument();
  });

  it('shows each Candidate’s approval, and offers no selection once the quantity is selected', async () => {
    serve(
      detailOf(m1, [
        {
          approval: 9,
          member: 7,
          name: 'Dave Kim',
          team: { id: 3, name: 'Operations' },
          state: 'APPROVED',
          escalated: false,
          decidedByName: 'Oscar Reed',
          decidedAt: '2026-11-13T09:00:00-05:00',
          reason: 'Dave is free that week',
        },
      ]),
    );
    renderApp('/requests/42');

    const candidates = await screen.findByRole('table', { name: 'Candidates of line 1' });
    expect(within(candidates).getByText('Approved')).toBeInTheDocument();
    expect(within(candidates).getByText(/Oscar Reed/)).toBeInTheDocument();
    expect(within(candidates).getByText('Dave is free that week')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Select/ })).not.toBeInTheDocument();
  });

  it('shows the Shortlist to a Capacity Planner without the picker', async () => {
    serve(pending, erin);
    renderApp('/requests/42');

    const table = await screen.findByRole('table', { name: 'Shortlist of line 1' });
    expect(within(table).queryByRole('checkbox')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Select/ })).not.toBeInTheDocument();
  });
});

describe('Person lines in the Draft editor', () => {
  function serveDraft() {
    return mockApi({
      'GET /api/me': session(alice),
      'GET /api/requests/:id/draft': json(aliceDraft),
      'GET /api/inventory/types': json(types),
      'GET /api/inventory/resources': json({ content: [roomA], page: 0, size: 200, totalElements: 1 }),
      'GET /api/skills': json([java, statistics]),
      'GET /api/members/lendable': json([
        { id: 7, name: 'Dave Kim', username: 'dave', team: { id: 3, name: 'Operations' }, active: true, roles: ['MEMBER'] },
      ]),
      'POST /api/requests/:id/lines': json(m1, 201),
    });
  }

  it('adds a skill-based Person line over whole days', async () => {
    const server = serveDraft();
    const { user } = renderApp('/requests/42');

    await user.click(await screen.findByRole('checkbox', { name: 'Statistics' }));
    const people = screen.getByLabelText(/^People$/);
    await user.clear(people);
    await user.type(people, '2');
    fireEvent.change(screen.getByLabelText(/^First day/), { target: { value: '2026-11-25' } });
    fireEvent.change(screen.getByLabelText(/^Last day/), { target: { value: '2026-11-26' } });
    await user.click(screen.getByRole('button', { name: 'Add Person line' }));

    await screen.findByRole('table', { name: 'Lines' });
    const [post] = server.callsTo('POST /api/requests/:id/lines');
    expect(JSON.parse(post.body ?? '')).toEqual({
      typeId: 1,
      quantity: 2,
      start: '2026-11-25T00:00:00-05:00',
      end: '2026-11-27T00:00:00-05:00',
      skills: [31],
    });
  });

  it('adds a line naming one Person of another Team, quantity 1', async () => {
    const server = serveDraft();
    const { user } = renderApp('/requests/42');

    await user.click(await screen.findByRole('radio', { name: /One named Person/ }));
    await screen.findByRole('option', { name: 'Dave Kim (Operations)' });
    await user.selectOptions(screen.getByLabelText(/^Named Person/), 'Dave Kim (Operations)');
    fireEvent.change(screen.getByLabelText(/^First day/), { target: { value: '2026-11-25' } });
    fireEvent.change(screen.getByLabelText(/^Last day/), { target: { value: '2026-11-26' } });
    await user.click(screen.getByRole('button', { name: 'Add Person line' }));

    await screen.findByRole('table', { name: 'Lines' });
    const [post] = server.callsTo('POST /api/requests/:id/lines');
    expect(JSON.parse(post.body ?? '')).toEqual({
      typeId: 1,
      quantity: 1,
      start: '2026-11-25T00:00:00-05:00',
      end: '2026-11-27T00:00:00-05:00',
      namedMemberId: 7,
    });
  });
});
