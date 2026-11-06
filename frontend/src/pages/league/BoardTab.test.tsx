import { screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { LeagueBoard } from '../../api/types';
import { renderWithRouter, stubApi } from '../../test/render';
import { BoardTab } from './BoardTab';

/**
 * The league table.
 *
 * The ranking is on net profit rather than balance, because the balance includes the buy-in everybody paid
 * equally; a table sorted by balance would say the same thing but hide what each player actually gained or lost.
 */

function board(overrides: Partial<LeagueBoard> = {}): LeagueBoard {
  return {
    leagueId: 3,
    leagueName: 'Office IPL',
    leagueStatus: 'ACTIVE',
    contestsPlayed: 1,
    contestsPlanned: 10,
    amountPerContest: '100.00',
    joiningAmount: '1000.00',
    totalLeftover: '0.00',
    rows: [
      {
        rank: 1,
        userId: 1,
        displayName: 'Sahil',
        wonCount: 1,
        lostCount: 0,
        refundedCount: 0,
        winnings: '150.00',
        entriesSpent: '100.00',
        netPl: '50.00',
        joiningAmountPaid: '1000.00',
        runningBalance: '1050.00',
        settlementStatus: null,
        me: true,
      },
      {
        rank: 2,
        userId: 2,
        displayName: 'Riya',
        wonCount: 0,
        lostCount: 1,
        refundedCount: 0,
        winnings: '0.00',
        entriesSpent: '100.00',
        netPl: '-100.00',
        joiningAmountPaid: '1000.00',
        runningBalance: '900.00',
        settlementStatus: null,
        me: false,
      },
    ],
    me: null,
    ...overrides,
  };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('BoardTab', () => {
  it('lists every player with their profit and their balance', async () => {
    const data = board();
    stubApi({ '/board': { ...data, me: data.rows[0] } });
    renderWithRouter(<BoardTab leagueId={3} />);

    expect(await screen.findByText('Riya')).toBeInTheDocument();
    expect(screen.getAllByText('+₹50.00').length).toBeGreaterThan(0);
    expect(screen.getByText('−₹100.00')).toBeInTheDocument();
    expect(screen.getByText('₹900.00')).toBeInTheDocument();
  });

  it('marks the caller’s own line', async () => {
    const data = board();
    stubApi({ '/board': { ...data, me: data.rows[0] } });
    renderWithRouter(<BoardTab leagueId={3} />);

    expect(await screen.findByText('(you)')).toBeInTheDocument();
  });

  it('shows how far through the league is', async () => {
    stubApi({ '/board': board() });
    renderWithRouter(<BoardTab leagueId={3} />);

    expect(await screen.findByText('1 of 10')).toBeInTheDocument();
    expect(screen.getByText('₹1,000.00')).toBeInTheDocument();
  });

  it('explains an empty table rather than showing nothing', async () => {
    stubApi({ '/board': board({ rows: [], contestsPlayed: 0 }) });
    renderWithRouter(<BoardTab leagueId={3} />);

    expect(await screen.findByText(/Nobody has played a contest yet/)).toBeInTheDocument();
  });

  it('mentions the paise a pool could not divide, when there are any', async () => {
    stubApi({ '/board': board({ totalLeftover: '0.01' }) });
    renderWithRouter(<BoardTab leagueId={3} />);

    expect(await screen.findByText('Unsplit paise')).toBeInTheDocument();
    expect(screen.getByText('₹0.01')).toBeInTheDocument();
  });

  it('shows settlement status once the league has ended', async () => {
    const data = board({ leagueStatus: 'PENDING_SETTLEMENT' });
    data.rows[0].settlementStatus = 'SETTLED';
    data.rows[1].settlementStatus = 'PENDING';
    stubApi({ '/board': data });
    renderWithRouter(<BoardTab leagueId={3} />);

    // "Settled" is also a column heading, so the badge is what is checked here.
    expect(await screen.findByText('Settled', { selector: '.badge' })).toBeInTheDocument();
    expect(screen.getByText('Pending', { selector: '.badge' })).toBeInTheDocument();
  });
});
