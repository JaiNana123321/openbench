import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { MemberProfile, MemberSummary, Page, Skill } from '../../api/types';
import { alice, ben } from '../../test/members';
import { json, mockApi, problem, session, type MockReply } from '../../test/mockApi';
import { renderApp } from '../../test/renderApp';

const statistics: Skill = { id: 1, name: 'Statistics', retired: false };
const java: Skill = { id: 2, name: 'Java', retired: false };
const microscopy: Skill = { id: 3, name: 'Microscopy', retired: false };
const cobol: Skill = { id: 9, name: 'COBOL', retired: true };

const bensProfile: MemberProfile = {
  id: ben.id,
  name: 'Ben Ortiz',
  email: 'ben@openbench.example',
  team: ben.team,
  skills: [java],
  availability: [{ from: '2026-11-07', to: '2027-01-24' }],
};

function serve(me = ben, routes: Record<string, MockReply> = {}) {
  return mockApi({
    'GET /api/me': session(me),
    'GET /api/members/me/profile': json(bensProfile),
    'GET /api/skills': json([java, microscopy, statistics]),
    ...routes,
  });
}

describe('My profile', () => {
  it('shows name, email, and a read-only Team, and saves skills chosen from the list', async () => {
    const server = serve(ben, {
      'PUT /api/members/me/skills': json({ ...bensProfile, skills: [java, statistics] }),
    });
    const { user } = renderApp('/profile');

    const profile = await screen.findByLabelText('Profile');
    expect(profile).toHaveTextContent('Ben Ortiz');
    expect(profile).toHaveTextContent('ben@openbench.example');
    expect(profile).toHaveTextContent('Platform');
    expect(within(profile).queryByRole('textbox')).not.toBeInTheDocument();

    const skills = await screen.findByRole('form', { name: 'Skills' });
    expect(within(skills).queryByRole('textbox')).not.toBeInTheDocument();
    expect(within(skills).getByRole('checkbox', { name: 'Java' })).toBeChecked();
    await user.click(within(skills).getByRole('checkbox', { name: 'Statistics' }));
    await user.click(within(skills).getByRole('button', { name: 'Save skills' }));

    expect(await within(skills).findByRole('status')).toHaveTextContent('Saved.');
    expect(JSON.parse(server.callsTo('PUT /api/members/me/skills')[0].body ?? '')).toEqual({ skills: [2, 1] });
  });

  it('offers a retired skill only to a Member who still holds it, and can drop a skill', async () => {
    const server = serve(ben, {
      'GET /api/members/me/profile': json({ ...bensProfile, skills: [cobol, java] }),
      'PUT /api/members/me/skills': json({ ...bensProfile, skills: [cobol] }),
    });
    const { user } = renderApp('/profile');

    const skills = await screen.findByRole('form', { name: 'Skills' });
    expect(within(skills).getByRole('checkbox', { name: 'COBOL (retired)' })).toBeChecked();
    await user.click(within(skills).getByRole('checkbox', { name: 'Java' }));
    await user.click(within(skills).getByRole('button', { name: 'Save skills' }));

    await within(skills).findByRole('status');
    expect(JSON.parse(server.callsTo('PUT /api/members/me/skills')[0].body ?? '')).toEqual({ skills: [9] });
  });

  it('shows the refusal of a skill', async () => {
    serve(ben, {
      'PUT /api/members/me/skills': problem(422, 'SKILL_RETIRED', 'Statistics has been retired and can no longer be chosen.'),
    });
    const { user } = renderApp('/profile');

    const skills = await screen.findByRole('form', { name: 'Skills' });
    await user.click(within(skills).getByRole('checkbox', { name: 'Statistics' }));
    await user.click(within(skills).getByRole('button', { name: 'Save skills' }));

    expect(await within(skills).findByRole('alert')).toHaveTextContent('Statistics has been retired');
  });

  it('replaces the availability with the edited ranges, as the server merges them', async () => {
    const merged: MemberProfile = {
      ...bensProfile,
      availability: [
        { from: '2026-11-07', to: '2026-11-25' },
        { from: '2026-11-27', to: '2027-01-24' },
      ],
    };
    const server = serve(ben, { 'PUT /api/members/me/availability': json(merged) });
    const { user } = renderApp('/profile');

    const availability = await screen.findByRole('form', { name: 'Availability' });
    const first = within(availability).getByRole('group', { name: 'Range 1' });
    await user.clear(within(first).getByLabelText('To'));
    await user.type(within(first).getByLabelText('To'), '2026-11-25');
    await user.click(within(availability).getByRole('button', { name: 'Add range' }));
    const second = within(availability).getByRole('group', { name: 'Range 2' });
    await user.type(within(second).getByLabelText('From'), '2026-11-27');
    await user.type(within(second).getByLabelText('To'), '2027-01-24');
    await user.click(within(availability).getByRole('button', { name: 'Save availability' }));

    expect(await within(availability).findByRole('status')).toHaveTextContent('Saved.');
    expect(JSON.parse(server.callsTo('PUT /api/members/me/availability')[0].body ?? '')).toEqual({
      ranges: [
        { from: '2026-11-07', to: '2026-11-25' },
        { from: '2026-11-27', to: '2027-01-24' },
      ],
    });
    expect(within(availability).getAllByRole('group')).toHaveLength(2);
  });

  it('removes a range and shows a refused range', async () => {
    const server = serve(ben, {
      'PUT /api/members/me/availability': problem(422, 'RANGE_INVALID', 'The range ends before it starts.'),
    });
    const { user } = renderApp('/profile');

    const availability = await screen.findByRole('form', { name: 'Availability' });
    await user.click(within(availability).getByRole('button', { name: 'Remove' }));
    expect(within(availability).getByText('No availability ranges.')).toBeInTheDocument();
    await user.click(within(availability).getByRole('button', { name: 'Save availability' }));

    expect(await within(availability).findByRole('alert')).toHaveTextContent('The range ends before it starts.');
    expect(JSON.parse(server.callsTo('PUT /api/members/me/availability')[0].body ?? '')).toEqual({ ranges: [] });
  });

  it('shows an empty skill list and no availability', async () => {
    serve(ben, {
      'GET /api/members/me/profile': json({ ...bensProfile, skills: [], availability: [] }),
      'GET /api/skills': json([]),
    });
    renderApp('/profile');

    expect(await screen.findByText('The skill list is empty.')).toBeInTheDocument();
    expect(screen.getByText('No availability ranges.')).toBeInTheDocument();
  });
});

describe('Team view', () => {
  const roster: Page<MemberSummary> = {
    content: [
      { id: alice.id, name: 'Alice Chen', username: 'alice', team: alice.team, active: true, roles: alice.roles },
      { id: ben.id, name: 'Ben Ortiz', username: 'ben', team: ben.team, active: true, roles: ben.roles },
      { id: 11, name: 'Old Hand', username: 'old', team: ben.team, active: false, roles: ['MEMBER'] },
    ],
    page: 0,
    size: 200,
    totalElements: 3,
  };

  it("lists the Manager's Team and opens a Member's profile to edit their availability", async () => {
    const server = mockApi({
      'GET /api/me': session(alice),
      'GET /api/teams/:id/members': json(roster),
      'GET /api/members/:id/profile': json(bensProfile),
      'PUT /api/members/:id/availability': json({
        ...bensProfile,
        availability: [...bensProfile.availability, { from: '2027-02-03', to: '2027-02-13' }],
      }),
    });
    const { user } = renderApp('/team');

    const table = await screen.findByRole('table', { name: 'Team Members' });
    expect(server.calls[1].path).toBe(`/api/teams/${alice.team.id}/members`);
    expect(within(table).getByRole('row', { name: /Old Hand/ })).toHaveTextContent('Deactivated');
    await user.click(within(table).getByRole('link', { name: 'Ben Ortiz' }));

    expect(await screen.findByRole('heading', { name: 'Ben Ortiz' })).toBeInTheDocument();
    expect(screen.getByRole('list', { name: 'Skills' })).toHaveTextContent('Java');
    const availability = screen.getByRole('form', { name: 'Availability' });
    await user.click(within(availability).getByRole('button', { name: 'Add range' }));
    const added = within(availability).getByRole('group', { name: 'Range 2' });
    await user.type(within(added).getByLabelText('From'), '2027-02-03');
    await user.type(within(added).getByLabelText('To'), '2027-02-13');
    await user.click(within(availability).getByRole('button', { name: 'Save availability' }));

    await within(availability).findByRole('status');
    const put = server.callsTo('PUT /api/members/:id/availability')[0];
    expect(put.path).toBe(`/api/members/${ben.id}/availability`);
    expect(JSON.parse(put.body ?? '').ranges).toHaveLength(2);
  });

  it('shows a Member without skills', async () => {
    mockApi({
      'GET /api/me': session(alice),
      'GET /api/members/:id/profile': json({ ...bensProfile, skills: [] }),
    });
    renderApp(`/team/members/${ben.id}`);
    expect(await screen.findByText('No skills.')).toBeInTheDocument();
  });

  it("shows Page not found for another Team's Member", async () => {
    mockApi({
      'GET /api/me': session(alice),
      'GET /api/members/:id/profile': problem(404, 'NOT_FOUND', 'Member 4 does not exist.'),
    });
    renderApp('/team/members/4');
    expect(await screen.findByRole('heading', { name: 'Page not found' })).toBeInTheDocument();
  });

  it('shows the error of a roster that cannot be read', async () => {
    mockApi({
      'GET /api/me': session(alice),
      'GET /api/teams/:id/members': problem(404, 'NOT_FOUND', 'Team 1 does not exist.'),
    });
    renderApp('/team');
    expect(await screen.findByRole('heading', { name: 'Page not found' })).toBeInTheDocument();
  });

  it('is not offered to a Member who is not a Manager', async () => {
    serve(ben);
    renderApp('/team');
    expect(await screen.findByRole('heading', { name: 'Not allowed' })).toBeInTheDocument();
  });
});
