import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { RefundQuote, RequestDetail } from '../../api/types';
import { alice, bob, erin } from '../../test/members';
import { json, mockApi, problem, session } from '../../test/mockApi';
import { renderApp } from '../../test/renderApp';
import { aliceAllocated, aliceDraft, bobWaiting, gpuLine, roomLine } from './fixtures';

/** Room A on Tue Oct 27, 14:00 to 16:00 (30.00), released seven or more days ahead: 75%. */
const wholeRoom: RefundQuote = {
  attributable: 30,
  refund: 22.5,
  pieces: [
    {
      occurrence: roomLine.window,
      released: roomLine.window,
      amount: 1,
      leadHours: 200,
      fraction: 0.75,
      attributable: 30,
      refund: 22.5,
    },
  ],
};

/** The GPU line's hours from Wed 09:00: 4 units, half of 1120.00, 75%. */
const gpuTail: RefundQuote = {
  attributable: 560,
  refund: 420,
  pieces: [
    {
      occurrence: gpuLine.window,
      released: { start: '2026-10-28T09:00:00-04:00', end: gpuLine.window.end },
      amount: 4,
      leadHours: 200,
      fraction: 0.75,
      attributable: 560,
      refund: 420,
    },
  ],
};

function serve(me = alice, detail: RequestDetail = aliceAllocated, routes = {}) {
  return mockApi({
    'GET /api/me': session(me),
    'GET /api/requests/:id/draft': json({ ...aliceDraft, request: detail.request }),
    'GET /api/requests/:id': json(detail),
    'GET /api/requests/:id/lines/:line/release-preview': (request) =>
      json(request.query.get('kind') === 'EARLIER_END' ? gpuTail : wholeRoom),
    ...routes,
  });
}

describe('RequestPage, Release', () => {
  it('shows what each line holds now and previews the Refund before releasing a whole line', async () => {
    let released = false;
    const server = serve(alice, aliceAllocated, {
      'GET /api/requests/:id': () =>
        json(
          released
            ? {
                ...aliceAllocated,
                lines: [
                  { ...aliceAllocated.lines[0], line: { ...roomLine, state: 'RELEASED' }, allocatedParts: [] },
                  aliceAllocated.lines[1],
                ],
              }
            : aliceAllocated,
        ),
      'POST /api/requests/:id/lines/:line/release': () => {
        released = true;
        return json({ line: { ...roomLine, state: 'RELEASED' }, refund: wholeRoom });
      },
    });
    const { user } = renderApp('/requests/42');

    const rows = within(await screen.findByRole('table', { name: 'Lines' })).getAllByRole('row').slice(1);
    expect(rows[0]).toHaveTextContent('Tue Oct 27, 14:00 to 16:00, 1 instance');
    expect(rows[1]).toHaveTextContent('4 units');
    await user.click(within(rows[0]).getByRole('button', { name: 'Release…' }));

    expect(await screen.findByRole('heading', { name: 'Release line 1' })).toBeInTheDocument();
    expect(await screen.findByText('Expected Refund: 22.50 credits')).toBeInTheDocument();
    expect(screen.getByLabelText('Expected Refund')).toHaveTextContent('75% of 30.00 = 22.50');
    expect(server.callsTo('GET /api/requests/:id/lines/:line/release-preview')[0].query.get('kind')).toBe('WHOLE');

    await user.click(screen.getByRole('button', { name: 'Release' }));

    expect(await screen.findByRole('status')).toHaveTextContent('The line is now Released. A Refund of 22.50 credits was posted.');
    const posted = server.callsTo('POST /api/requests/:id/lines/:line/release');
    expect(posted.map((call) => call.path)).toEqual(['/api/requests/42/lines/101/release']);
    expect(JSON.parse(posted[0].body ?? '')).toEqual({ kind: 'WHOLE' });
    const after = within(await screen.findByRole('table', { name: 'Lines' })).getAllByRole('row').slice(1);
    expect(within(after[0]).queryByRole('button', { name: 'Release…' })).not.toBeInTheDocument();
  });

  it('releases a Metered line’s hours after an earlier end', async () => {
    const server = serve(alice, aliceAllocated, {
      'POST /api/requests/:id/lines/:line/release': json({ line: { ...gpuLine, state: 'PARTLY_RELEASED' }, refund: gpuTail }),
    });
    const { user } = renderApp('/requests/42');

    const rows = within(await screen.findByRole('table', { name: 'Lines' })).getAllByRole('row').slice(1);
    await user.click(within(rows[1]).getByRole('button', { name: 'Release…' }));
    await user.selectOptions(screen.getByLabelText('Give back'), 'Everything after an earlier end');
    expect(screen.queryByRole('option', { name: 'Some occurrences' })).not.toBeInTheDocument();
    await user.type(screen.getByLabelText('New end'), '2026-10-28T09:00');

    expect(await screen.findByText('Expected Refund: 420.00 credits')).toBeInTheDocument();
    const preview = server.callsTo('GET /api/requests/:id/lines/:line/release-preview').at(-1);
    expect(preview?.query.get('newEnd')).toBe('2026-10-28T09:00:00-04:00');
    await user.click(screen.getByRole('button', { name: 'Release' }));

    expect(await screen.findByRole('status')).toHaveTextContent('The line is now Partly released. A Refund of 420.00 credits');
    expect(JSON.parse(server.callsTo('POST /api/requests/:id/lines/:line/release')[0].body ?? '')).toEqual({
      kind: 'EARLIER_END',
      newEnd: '2026-10-28T09:00:00-04:00',
    });
  });

  it('gives back fewer units of a Metered line and occurrences of an Exclusive one', async () => {
    const server = serve();
    const { user } = renderApp('/requests/42');

    const rows = within(await screen.findByRole('table', { name: 'Lines' })).getAllByRole('row').slice(1);
    await user.click(within(rows[1]).getByRole('button', { name: 'Release…' }));
    await user.selectOptions(screen.getByLabelText('Give back'), 'Some units');
    expect(screen.getByText('From 1 to 3; the others are given back.')).toBeInTheDocument();
    await user.type(screen.getByLabelText('Units to keep'), '2');
    await screen.findByText(/Expected Refund/);
    expect(server.callsTo('GET /api/requests/:id/lines/:line/release-preview').at(-1)?.query.get('newUnits')).toBe('2');

    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    await user.click(within(rows[0]).getByRole('button', { name: 'Release…' }));
    await user.selectOptions(screen.getByLabelText('Give back'), 'Some occurrences');
    await user.click(screen.getByRole('checkbox', { name: 'Tue Oct 27, 14:00 to 16:00' }));
    await screen.findByText(/Expected Refund/);
    expect(server.callsTo('GET /api/requests/:id/lines/:line/release-preview').at(-1)?.query.get('occurrences')).toBe(
      roomLine.window.start,
    );
  });

  it('shows why a part cannot be released and offers no Release until the preview succeeds', async () => {
    serve(alice, aliceAllocated, {
      'GET /api/requests/:id/lines/:line/release-preview': problem(
        409,
        'NOT_ALLOCATED',
        'Every allocated occurrence of this line has started.',
      ),
    });
    const { user } = renderApp('/requests/42');

    const rows = within(await screen.findByRole('table', { name: 'Lines' })).getAllByRole('row').slice(1);
    await user.click(within(rows[0]).getByRole('button', { name: 'Release…' }));

    expect(await screen.findByText('Every allocated occurrence of this line has started.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Release' })).toBeDisabled();
  });

  it('offers Release only to the Team’s Manager, on allocated lines', async () => {
    serve(erin);
    renderApp('/requests/42');
    expect(await screen.findByRole('table', { name: 'Lines' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Release…' })).not.toBeInTheDocument();
  });

  it('offers no Release on waiting lines', async () => {
    serve(bob, bobWaiting);
    renderApp('/requests/43');
    expect(await screen.findByRole('table', { name: 'Lines' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Release…' })).not.toBeInTheDocument();
  });
});
