import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { Loan, MemberProfile } from '../../api/types';
import { alice, dave } from '../../test/members';
import { json, mockApi, problem, session } from '../../test/mockApi';
import { renderApp } from '../../test/renderApp';

const davesProfile: MemberProfile = {
  id: dave.id,
  name: 'Dave Kim',
  email: 'dave@openbench.example',
  team: dave.team,
  skills: [],
  availability: [],
};

const loanToResearch: Loan = {
  id: 41,
  line: 12,
  member: dave.id,
  memberName: 'Dave Kim',
  lendingTeam: dave.team,
  borrowingTeam: { id: 2, name: 'Research' },
  days: { from: '2026-11-25', to: '2026-11-26' },
  daysLent: 2,
  endedEarly: false,
};

describe('Loans on a profile (prf-visibility, mat-loan-form)', () => {
  it("lists the caller's Loans with Team, days, and days lent, and nothing to accept or decline", async () => {
    const server = mockApi({
      'GET /api/me': session(dave),
      'GET /api/members/me/profile': json(davesProfile),
      'GET /api/skills': json([]),
      'GET /api/members/:id/loans': json([loanToResearch]),
    });
    renderApp('/profile');

    const loans = await screen.findByRole('table', { name: 'Loans' });
    const row = within(loans).getByRole('row', { name: /Research/ });
    expect(row).toHaveTextContent('Nov 25, 2026 to Nov 26, 2026');
    expect(row).toHaveTextContent('2');
    expect(within(loans).queryByRole('button')).not.toBeInTheDocument();
    expect(server.callsTo('GET /api/members/:id/loans')[0].path).toBe('/api/members/me/loans');
  });

  it("shows a Team Member's Loans to their Manager", async () => {
    const server = mockApi({
      'GET /api/me': session(alice),
      'GET /api/members/:id/profile': json({ ...davesProfile, team: alice.team }),
      'GET /api/members/:id/loans': json([{ ...loanToResearch, lendingTeam: alice.team }]),
    });
    renderApp(`/team/members/${dave.id}`);

    const loans = await screen.findByRole('table', { name: 'Loans' });
    expect(within(loans).getByRole('row', { name: /Research/ })).toHaveTextContent('Nov 25, 2026 to Nov 26, 2026');
    expect(server.callsTo('GET /api/members/:id/loans')[0].path).toBe(`/api/members/${dave.id}/loans`);
  });

  it('shows a Loan ended early with its new last day (prf-loan-overlap)', async () => {
    mockApi({
      'GET /api/me': session(dave),
      'GET /api/members/me/profile': json(davesProfile),
      'GET /api/skills': json([]),
      'GET /api/members/:id/loans': json([
        { ...loanToResearch, days: { from: '2026-11-25', to: '2026-11-25' }, daysLent: 1, endedEarly: true },
      ]),
    });
    renderApp('/profile');

    const loans = await screen.findByRole('table', { name: 'Loans' });
    const row = within(loans).getByRole('row', { name: /Research/ });
    expect(row).toHaveTextContent('Nov 25, 2026 to Nov 25, 2026 (ended early)');
    expect(row).toHaveTextContent('1');
  });

  it('says so when there are no Loans', async () => {
    mockApi({
      'GET /api/me': session(dave),
      'GET /api/members/me/profile': json(davesProfile),
      'GET /api/skills': json([]),
      'GET /api/members/:id/loans': json([]),
    });
    renderApp('/profile');
    expect(await screen.findByText('No Loans.')).toBeInTheDocument();
  });

  it('shows the refusal of Loans that cannot be read', async () => {
    mockApi({
      'GET /api/me': session(dave),
      'GET /api/members/me/profile': json(davesProfile),
      'GET /api/skills': json([]),
      'GET /api/members/:id/loans': problem(404, 'NOT_FOUND', 'Member 7 does not exist.'),
    });
    renderApp('/profile');
    const section = (await screen.findByRole('heading', { name: 'Loans' })).closest('section') as HTMLElement;
    expect(await within(section).findByRole('alert')).toHaveTextContent('Member 7 does not exist.');
  });
});
