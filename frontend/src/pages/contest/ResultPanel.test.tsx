import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Contest, ResultPreview } from '../../api/types';
import { renderWithRouter } from '../../test/render';
import { ResultPanel } from './ResultPanel';

/**
 * The result screen, and in particular the two-step confirm.
 *
 * The organizer must see the figures before anything is saved, and the token from that preview has to go back with
 * the confirmation. If the token were dropped, the backend would refuse the save, so it is worth a test of its own.
 */

const teamA = { id: 10, name: 'Mumbai Indians', shortCode: 'MI' };
const teamB = { id: 11, name: 'Chennai Super Kings', shortCode: 'CSK' };

function contest(overrides: Partial<Contest> = {}): Contest {
  return {
    id: 7,
    leagueId: null,
    leagueName: null,
    name: null,
    contestNumber: null,
    tournamentId: 1,
    tournamentName: 'IPL 2026',
    teamA,
    teamB,
    startTime: new Date(Date.now() - 3_600_000).toISOString(),
    contestAmount: '100.00',
    registrationDeadline: null,
    paymentDeadline: null,
    paymentInstructions: null,
    status: 'LOCKED',
    resultType: null,
    winningTeamId: null,
    winningTeam: null,
    leftoverAmount: '0.00',
    lockedAt: new Date().toISOString(),
    cancelledReason: null,
    confirmedParticipants: 3,
    myParticipantStatus: 'CONFIRMED',
    standalone: false,
    organizer: true,
    organizerDisplayName: 'Sahil',
    version: 0,
    ...overrides,
  };
}

const preview: ResultPreview = {
  contestId: 7,
  contestLabel: 'MI vs CSK',
  winningTeamId: 10,
  winningTeamShortCode: 'MI',
  noResult: false,
  entryAmount: '100.00',
  confirmedCount: 3,
  pool: '300.00',
  winnerCount: 2,
  perWinner: '150.00',
  leftover: '0.00',
  everyoneRefunded: false,
  refundReason: null,
  outcomes: [
    {
      participantId: 1,
      userId: 1,
      displayName: 'Sahil',
      selectedTeamId: 10,
      selectedTeamShortCode: 'MI',
      result: 'WON',
      entrySpent: '100.00',
      winnings: '150.00',
      netPl: '50.00',
    },
    {
      participantId: 2,
      userId: 2,
      displayName: 'Riya',
      selectedTeamId: 11,
      selectedTeamShortCode: 'CSK',
      result: 'LOST',
      entrySpent: '100.00',
      winnings: '0.00',
      netPl: '-100.00',
    },
  ],
  previewToken: 'abc123token',
};

/** Answers the preview and the confirm with the same figures, and records what was sent. */
function stubResultFlow(previewBody: ResultPreview = preview) {
  const fetchMock = vi.fn(async (input: string | URL | Request, _init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input.toString();
    if (url.includes('/result/preview') || url.includes('/result/confirm') || url.includes('/result/correct')) {
      return {
        ok: true,
        status: 200,
        text: () => Promise.resolve(JSON.stringify(previewBody)),
      } as unknown as Response;
    }
    // The outcomes list is only asked for once a result exists.
    return {
      ok: true,
      status: 200,
      text: () => Promise.resolve(JSON.stringify(previewBody.outcomes)),
    } as unknown as Response;
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('ResultPanel', () => {
  it('will not offer to confirm until a preview has been seen', async () => {
    stubResultFlow();
    renderWithRouter(<ResultPanel contest={contest()} onChanged={() => {}} />);

    await userEvent.click(screen.getByRole('button', { name: /Mumbai Indians won/ }));

    expect(screen.queryByRole('button', { name: /Confirm this result/ })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Work out the payouts/ })).toBeEnabled();
  });

  it('shows the pool and each winner’s share before anything is saved', async () => {
    const fetchMock = stubResultFlow();
    renderWithRouter(<ResultPanel contest={contest()} onChanged={() => {}} />);

    await userEvent.click(screen.getByRole('button', { name: /Mumbai Indians won/ }));
    await userEvent.click(screen.getByRole('button', { name: /Work out the payouts/ }));

    expect(await screen.findByText('₹300.00')).toBeInTheDocument();
    // The winner's share shows twice: once as the headline figure and once on the winner's own row.
    expect(screen.getAllByText('₹150.00')).toHaveLength(2);
    expect(screen.getByText(/Nothing has been saved yet/)).toBeInTheDocument();
    // A preview must not save anything.
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/result/confirm'))).toBe(false);
  });

  it('sends the preview token back with the confirmation', async () => {
    const fetchMock = stubResultFlow();
    const onChanged = vi.fn();
    renderWithRouter(<ResultPanel contest={contest()} onChanged={onChanged} />);

    await userEvent.click(screen.getByRole('button', { name: /Mumbai Indians won/ }));
    await userEvent.click(screen.getByRole('button', { name: /Work out the payouts/ }));
    await userEvent.click(await screen.findByRole('button', { name: /Confirm this result/ }));

    await waitFor(() => {
      const confirm = fetchMock.mock.calls.find(([url]) => String(url).includes('/result/confirm'));
      expect(confirm).toBeDefined();
      expect(JSON.parse((confirm?.[1] as RequestInit).body as string)).toEqual({
        winningTeamId: 10,
        previewToken: 'abc123token',
      });
    });
    expect(onChanged).toHaveBeenCalled();
  });

  it('explains a refund-all outcome rather than just showing zeroes', async () => {
    stubResultFlow({
      ...preview,
      everyoneRefunded: true,
      winnerCount: 0,
      perWinner: '0.00',
      refundReason: 'Nobody picked the winning team, so every entry is refunded',
    });
    renderWithRouter(<ResultPanel contest={contest()} onChanged={() => {}} />);

    await userEvent.click(screen.getByRole('button', { name: /Mumbai Indians won/ }));
    await userEvent.click(screen.getByRole('button', { name: /Work out the payouts/ }));

    expect(await screen.findByText(/Nobody picked the winning team/)).toBeInTheDocument();
  });

  it('offers a no-result option for an abandoned match', async () => {
    const fetchMock = stubResultFlow();
    renderWithRouter(<ResultPanel contest={contest()} onChanged={() => {}} />);

    await userEvent.click(screen.getByRole('button', { name: /No result/ }));
    await userEvent.click(screen.getByRole('button', { name: /Work out the payouts/ }));

    await waitFor(() => {
      const call = fetchMock.mock.calls.find(([url]) => String(url).includes('/result/preview'));
      expect(JSON.parse((call?.[1] as RequestInit).body as string)).toEqual({ noResult: true });
    });
  });

  it('will not let a correction go without a reason', async () => {
    stubResultFlow();
    renderWithRouter(
      <ResultPanel
        contest={contest({ status: 'RESULT_DECLARED', resultType: 'WINNER', winningTeamId: teamA.id, winningTeam: teamA })}
        onChanged={() => {}}
      />,
    );

    await userEvent.click(await screen.findByRole('button', { name: /Correct the result/ }));
    await userEvent.click(screen.getByRole('button', { name: /Chennai Super Kings won/ }));

    expect(screen.getByRole('button', { name: /Correct it/ })).toBeDisabled();

    await userEvent.type(screen.getByRole('textbox'), 'I read the scorecard wrong');
    expect(screen.getByRole('button', { name: /Correct it/ })).toBeEnabled();
  });

  it('shows no declare panel to somebody who does not organize the contest', () => {
    stubResultFlow();
    renderWithRouter(<ResultPanel contest={contest({ organizer: false })} onChanged={() => {}} />);

    expect(screen.queryByRole('button', { name: /Work out the payouts/ })).not.toBeInTheDocument();
  });
});
