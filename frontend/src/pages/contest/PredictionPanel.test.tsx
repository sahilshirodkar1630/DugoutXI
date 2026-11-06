import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Contest, MyPrediction, PredictionRow } from '../../api/types';
import { renderWithRouter, stubApi } from '../../test/render';
import { PredictionPanel } from './PredictionPanel';

/**
 * The prediction screen, which is where the one rule everybody cares about lives: you can change your pick until
 * the contest starts, and not afterwards, and nobody can see anyone else's pick before then.
 */

const teamA = { id: 10, name: 'Mumbai Indians', shortCode: 'MI' };
const teamB = { id: 11, name: 'Chennai Super Kings', shortCode: 'CSK' };

function contest(overrides: Partial<Contest> = {}): Contest {
  return {
    id: 7,
    leagueId: 3,
    leagueName: 'Office IPL',
    name: null,
    contestNumber: 1,
    tournamentId: 1,
    tournamentName: 'IPL 2026',
    teamA,
    teamB,
    startTime: new Date(Date.now() + 86_400_000).toISOString(),
    contestAmount: '100.00',
    registrationDeadline: null,
    paymentDeadline: null,
    paymentInstructions: null,
    status: 'SCHEDULED',
    resultType: null,
    winningTeamId: null,
    winningTeam: null,
    leftoverAmount: '0.00',
    lockedAt: null,
    cancelledReason: null,
    confirmedParticipants: 3,
    myParticipantStatus: 'CONFIRMED',
    standalone: false,
    organizer: false,
    organizerDisplayName: 'Riya',
    version: 0,
    ...overrides,
  };
}

function myPrediction(overrides: Partial<MyPrediction> = {}): MyPrediction {
  return {
    contestId: 7,
    selectedTeamId: null,
    selectedTeamName: null,
    selectedTeamShortCode: null,
    predictedAt: null,
    startTime: new Date(Date.now() + 86_400_000).toISOString(),
    locked: false,
    teamA,
    teamB,
    ...overrides,
  };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('PredictionPanel', () => {
  it('offers both teams and sends the one that is clicked', async () => {
    const fetchMock = stubApi({
      '/predictions/me': myPrediction(),
    });
    renderWithRouter(<PredictionPanel contest={contest()} />);

    const button = await screen.findByRole('button', { name: /Mumbai Indians/ });
    await userEvent.click(button);

    await waitFor(() => {
      const put = fetchMock.mock.calls.find(([, init]) => (init as RequestInit)?.method === 'PUT');
      expect(put).toBeDefined();
      expect((put?.[1] as RequestInit).body).toBe(JSON.stringify({ teamId: 10 }));
    });
  });

  it('warns a player who has not picked that their entry still counts', async () => {
    stubApi({ '/predictions/me': myPrediction() });
    renderWithRouter(<PredictionPanel contest={contest()} />);

    expect(await screen.findByText(/cannot win/i)).toBeInTheDocument();
  });

  it('marks the pick that was made and says it can still be changed', async () => {
    stubApi({
      '/predictions/me': myPrediction({
        selectedTeamId: 10,
        selectedTeamShortCode: 'MI',
        predictedAt: new Date().toISOString(),
      }),
    });
    renderWithRouter(<PredictionPanel contest={contest()} />);

    const chosen = await screen.findByRole('button', { name: /Mumbai Indians/ });
    expect(chosen).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByText(/can change it until the contest starts/i)).toBeInTheDocument();
  });

  it('disables both teams once the contest has locked', async () => {
    stubApi({
      '/predictions/me': myPrediction({ locked: true, selectedTeamId: 11, selectedTeamShortCode: 'CSK' }),
    });
    renderWithRouter(<PredictionPanel contest={contest({ status: 'LOCKED' })} />);

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Mumbai Indians/ })).toBeDisabled();
    });
    expect(screen.getByRole('button', { name: /Chennai Super Kings/ })).toBeDisabled();
  });

  it('keeps other people’s picks hidden while the contest is still to start', async () => {
    const fetchMock = stubApi({ '/predictions/me': myPrediction() });
    renderWithRouter(<PredictionPanel contest={contest()} />);

    expect(await screen.findByText(/Picks appear once the contest starts/i)).toBeInTheDocument();
    // The list is not even requested before the start.
    expect(
      fetchMock.mock.calls.some(([url]) => String(url).endsWith('/predictions')),
    ).toBe(false);
  });

  it('shows everyone once it has started, including whoever never picked', async () => {
    const rows: PredictionRow[] = [
      { userId: 1, displayName: 'Sahil', selectedTeamId: 10, selectedTeamShortCode: 'MI', me: true },
      { userId: 2, displayName: 'Riya', selectedTeamId: 11, selectedTeamShortCode: 'CSK', me: false },
      { userId: 3, displayName: 'Arjun', selectedTeamId: null, selectedTeamShortCode: null, me: false },
    ];
    stubApi({
      '/predictions/me': myPrediction({ locked: true, selectedTeamId: 10, selectedTeamShortCode: 'MI' }),
      '/predictions': rows,
    });
    renderWithRouter(<PredictionPanel contest={contest({ status: 'LOCKED' })} />);

    expect(await screen.findByText('Riya')).toBeInTheDocument();
    expect(screen.getByText('Arjun')).toBeInTheDocument();
    expect(screen.getByText('no pick')).toBeInTheDocument();
  });

  it('says so plainly when the user is not in the contest at all', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 403,
        text: () => Promise.resolve(JSON.stringify({ detail: 'You are not a participant in this contest' })),
      } as unknown as Response),
    );
    renderWithRouter(<PredictionPanel contest={contest()} />);

    expect(await screen.findByText(/not a participant/i)).toBeInTheDocument();
  });
});
