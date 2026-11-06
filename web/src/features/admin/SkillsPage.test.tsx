import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { Skill } from '../../api/types';
import { carol } from '../../test/members';
import { json, mockApi, problem, session, type MockReply } from '../../test/mockApi';
import { renderApp } from '../../test/renderApp';

const java: Skill = { id: 2, name: 'Java', retired: false };
const cobol: Skill = { id: 9, name: 'COBOL', retired: true };
const python: Skill = { id: 12, name: 'Python', retired: false };

function serve(routes: Record<string, MockReply> = {}) {
  return mockApi({
    'GET /api/me': session(carol),
    'GET /api/skills': json([cobol, java]),
    ...routes,
  });
}

describe('Skill list administration', () => {
  it('lists every skill, retired ones included, and adds "Python"', async () => {
    const server = serve({ 'POST /api/admin/skills': json(python, 201) });
    const { user } = renderApp('/admin/skills');

    const table = await screen.findByRole('table', { name: 'Skills' });
    expect(server.callsTo('GET /api/skills')[0].query.get('includeRetired')).toBe('true');
    expect(within(table).getByRole('row', { name: /COBOL/ })).toHaveTextContent('Retired');
    expect(within(table).getByRole('row', { name: /Java/ })).toHaveTextContent('Offered');

    await user.type(screen.getByLabelText('Name'), 'Python');
    await user.click(screen.getByRole('button', { name: 'Add skill' }));

    await screen.findByDisplayValue('');
    expect(JSON.parse(server.callsTo('POST /api/admin/skills')[0].body ?? '')).toEqual({ name: 'Python' });
    expect(server.callsTo('GET /api/skills').length).toBeGreaterThan(1);
  });

  it('shows the refusal of a name already on the list', async () => {
    serve({
      'POST /api/admin/skills': problem(422, 'SKILL_NAME_TAKEN', 'A skill named java is already on the list.'),
    });
    const { user } = renderApp('/admin/skills');

    await user.type(await screen.findByLabelText('Name'), 'java');
    await user.click(screen.getByRole('button', { name: 'Add skill' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('A skill named java is already on the list.');
  });

  it('retires a skill', async () => {
    const server = serve({ 'POST /api/admin/skills/:id/retire': json({ ...java, retired: true }) });
    const { user } = renderApp('/admin/skills');

    await user.click(await screen.findByRole('button', { name: 'Retire Java' }));

    await screen.findByRole('table', { name: 'Skills' });
    expect(server.callsTo('POST /api/admin/skills/:id/retire')[0].params.id).toBe('2');
    expect(screen.queryByRole('button', { name: 'Retire COBOL' })).not.toBeInTheDocument();
  });

  it('shows an empty list', async () => {
    serve({ 'GET /api/skills': json([]) });
    renderApp('/admin/skills');
    expect(await screen.findByText('The skill list is empty.')).toBeInTheDocument();
  });

  it('shows the error of a list that cannot be read', async () => {
    serve({ 'GET /api/skills': problem(403, 'FORBIDDEN', 'You do not have a role this action needs.') });
    renderApp('/admin/skills');
    expect(await screen.findByRole('heading', { name: 'Not allowed' })).toBeInTheDocument();
  });

  it('offers the Administrator no policy setting (adm-skills-no-policy, step 2)', async () => {
    serve();
    renderApp('/');

    const menu = await screen.findByRole('navigation', { name: 'Main' });
    const labels = within(menu)
      .getAllByRole('link')
      .map((link) => link.textContent ?? '');
    expect(labels).toEqual(expect.arrayContaining(['Skill list']));
    for (const setting of ['Settings', 'Unit Price', 'Price Band', 'Grant', 'Quota', 'Day Rate', 'Refund']) {
      expect(labels.some((label) => label.includes(setting))).toBe(false);
    }
  });
});
