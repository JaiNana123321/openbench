import { act, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { MemberNotification, Page } from '../../api/types';
import { UNREAD_REFRESH_MS } from '../../shell/unreadNotifications';
import { alice } from '../../test/members';
import { json, mockApi, problem, session, type MockReply } from '../../test/mockApi';
import { renderApp } from '../../test/renderApp';

const rollover: MemberNotification = {
  id: 8,
  recipient: 1,
  kind: 'PERIOD_ROLLOVER',
  text: 'The Period from Oct 1, 2026 to Oct 31, 2026 has ended: 970.00 granted credits expired, and earned credits stay.',
  link: '/statement',
  createdAt: '2026-11-01T00:05:00-04:00',
  readAt: null,
};

const allocated: MemberNotification = {
  id: 7,
  recipient: 1,
  kind: 'REQUEST_UPDATE',
  text: 'Request 42 is Allocated; its Charges total 1150.00 credits.',
  link: '/requests/42',
  createdAt: '2026-10-23T10:15:00-04:00',
  readAt: null,
};

function page(content: MemberNotification[]): Page<MemberNotification> {
  return { content, page: 0, size: 50, totalElements: content.length };
}

function serve(routes: Record<string, MockReply> = {}) {
  return mockApi({
    'GET /api/me': session(alice),
    'GET /api/notifications': json(page([rollover, allocated])),
    ...routes,
  });
}

describe('Notifications', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('shows the number of unread notices in the header, linking to the list', async () => {
    serve();
    renderApp('/');

    const header = await screen.findByRole('banner');
    const link = await within(header).findByRole('link', { name: 'Notifications, 2 unread' });
    expect(link).toHaveAttribute('href', '/notifications');
    expect(link).toHaveTextContent('2');
  });

  it('shows no count when nothing is unread', async () => {
    serve({ 'GET /api/notifications': json(page([])) });
    renderApp('/notifications');

    expect(await screen.findByText('No unread notifications.')).toBeInTheDocument();
    expect(within(screen.getByRole('banner')).getByRole('link', { name: 'Notifications' })).not.toHaveTextContent(
      /\d/,
    );
  });

  it('refreshes the unread notices every 30 seconds', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let unread = [allocated];
    const server = serve({ 'GET /api/notifications': () => json(page(unread)) });
    renderApp('/');

    expect(await screen.findByRole('link', { name: 'Notifications, 1 unread' })).toBeInTheDocument();
    unread = [rollover, allocated];
    await act(async () => {
      await vi.advanceTimersByTimeAsync(UNREAD_REFRESH_MS);
    });

    expect(await screen.findByRole('link', { name: 'Notifications, 2 unread' })).toBeInTheDocument();
    expect(server.callsTo('GET /api/notifications').length).toBeGreaterThanOrEqual(2);
  });

  it('lists each unread notice, newest first, linking to its page', async () => {
    const server = serve();
    renderApp('/notifications');

    const table = await screen.findByRole('table', { name: 'Unread notifications' });
    const rows = within(table).getAllByRole('row');
    expect(rows).toHaveLength(3);
    expect(rows[1]).toHaveTextContent('Period rollover');
    expect(within(rows[1]).getByRole('link', { name: /970.00 granted credits expired/ })).toHaveAttribute(
      'href',
      '/statement',
    );
    expect(rows[2]).toHaveTextContent('Request update');
    expect(within(rows[2]).getByRole('link', { name: /Request 42 is Allocated/ })).toHaveAttribute(
      'href',
      '/requests/42',
    );
    expect(screen.getByText('2 unread notices')).toBeInTheDocument();
    expect(server.callsTo('GET /api/notifications')[0].query.get('size')).toBe('50');
  });

  it('shows a notice without a link as plain text', async () => {
    serve({ 'GET /api/notifications': json(page([{ ...allocated, link: null }])) });
    renderApp('/notifications');

    const table = await screen.findByRole('table', { name: 'Unread notifications' });
    expect(within(table).getByText(allocated.text)).toBeInTheDocument();
    expect(within(table).queryByRole('link')).not.toBeInTheDocument();
    expect(screen.getByText('1 unread notice')).toBeInTheDocument();
  });

  it('marks a notice read, after which it leaves the list and the count', async () => {
    let unread = [rollover, allocated];
    const server = serve({
      'GET /api/notifications': () => json(page(unread)),
      'POST /api/notifications/:id/read': () => {
        unread = [rollover];
        return json({ ...allocated, readAt: '2026-11-09T11:00:00-05:00' });
      },
    });
    const { user } = renderApp('/notifications');

    await user.click(await screen.findByRole('button', { name: `Mark read: ${allocated.text}` }));

    expect(await screen.findByRole('link', { name: 'Notifications, 1 unread' })).toBeInTheDocument();
    expect(screen.queryByText(allocated.text)).not.toBeInTheDocument();
    expect(server.callsTo('POST /api/notifications/:id/read').map((call) => call.params.id)).toEqual(['7']);
  });

  it('shows the refusal of marking a notice read', async () => {
    serve({
      'POST /api/notifications/:id/read': problem(404, 'NOT_FOUND', 'Notification 7 does not exist.'),
    });
    const { user } = renderApp('/notifications');

    await user.click(await screen.findByRole('button', { name: `Mark read: ${allocated.text}` }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Notification 7 does not exist.');
  });

  it('shows a failed read of the list', async () => {
    serve({ 'GET /api/notifications': problem(500, 'INTERNAL', 'Something went wrong.') });
    renderApp('/notifications');

    expect(await screen.findByText('Something went wrong.')).toBeInTheDocument();
  });
});
