import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { League, SettlementRow, SettlementSummary } from '../../api/types';
import { renderSignedIn, stubApi } from '../../test/render';
import { SettlementTab } from './SettlementTab';

/**
 * Settling up.
 *
 * Two things here protect real money. A league cannot be closed while somebody is still unsettled or while the
 * add-up check fails, and undoing a settlement needs a written reason. Both are enforced by the backend as well;
 * these tests make sure the screen does not invite the user into a refusal.
 */

function league(overrides: Partial<League> = {}): League {
  return {
    id: 3,
    name: 'Office IPL',
    tournamentId: 1,
    tournamentName: 'IPL 2026',
    organizerId: 1,
    organizerDisplayName: 'Sahil',
    amountPerContest: '100.00',
    totalContests: 10,
    joiningAmount: '1000.00',
    registrationStart: new Date().toISOString(),
    registrationDeadline: new Date().toISOString(),
    paymentDeadline: new Date().toISOString(),
    paymentInstructions: null,
    inviteCode: 'ABCD1234',
    status: 'PENDING_SETTLEMENT',
    startedAt: new Date().toISOString(),
    endedAt: new Date().toISOString(),
    completedAt: null,
    contestsCreated: 1,
    confirmedMembers: 3,
    organizer: true,
    myMembershipStatus: 'CONFIRMED',
    myPaymentStatus: 'PAID',
    version: 0,
    ...overrides,
  };
}

function row(overrides: Partial<SettlementRow> = {}): SettlementRow {
  return {
    rowId: 1,
    leagueId: 3,
    userId: 2,
    displayName: 'Riya',
    phoneNumber: null,
    upiId: 'riya@okbank',
    organizer: false,
    joiningAmountPaid: '1000.00',
    netPl: '50.00',
    finalBalance: '1050.00',
    status: 'PENDING',
    settledAt: null,
    writtenOffReason: null,
    me: false,
    ...overrides,
  };
}

function summary(overrides: Partial<SettlementSummary> = {}): SettlementSummary {
  const winner = row();
  const loser = row({ rowId: 2, userId: 3, displayName: 'Arjun', netPl: '-100.00', finalBalance: '900.00' });
  return {
    leagueId: 3,
    leagueName: 'Office IPL',
    leagueStatus: 'PENDING_SETTLEMENT',
    memberCount: 3,
    totalPaidIn: '3000.00',
    totalFinalBalance: '3000.00',
    totalLeftover: '0.00',
    balanced: true,
    pendingCount: 2,
    settledCount: 1,
    writtenOffCount: 0,
    // Everyone paid up front, so both members get their final balance back and nobody owes the organizer.
    owedMoney: [winner, loser],
    oweMoney: [],
    rows: [winner, loser],
    ...overrides,
  };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('SettlementTab', () => {
  it('lists the whole final balance each player gets back, the loser included', async () => {
    stubApi({ '/settlement/summary': summary(), '/api/me': { id: 1, role: 'USER' } });
    renderSignedIn(<SettlementTab league={league()} onChanged={() => {}} />);

    expect(await screen.findByText('Money to hand back')).toBeInTheDocument();
    expect(screen.queryByText(/owe the organizer/)).not.toBeInTheDocument();
    expect(screen.getAllByText('₹1,050.00').length).toBeGreaterThan(0);
    expect(screen.getAllByText('₹900.00').length).toBeGreaterThan(0);
    // The net result is still shown, signed, so everyone can see how their season went.
    expect(screen.getAllByText('+₹50.00').length).toBeGreaterThan(0);
    expect(screen.getAllByText('−₹100.00').length).toBeGreaterThan(0);
  });

  it('says the figures add up when they do', async () => {
    stubApi({ '/settlement/summary': summary(), '/api/me': { id: 1, role: 'USER' } });
    renderSignedIn(<SettlementTab league={league()} onChanged={() => {}} />);

    expect(await screen.findByText(/The figures add up/)).toBeInTheDocument();
  });

  it('refuses to let the league close while the figures do not add up', async () => {
    stubApi({
      '/settlement/summary': summary({ balanced: false, pendingCount: 0 }),
      '/api/me': { id: 1, role: 'USER' },
    });
    renderSignedIn(<SettlementTab league={league()} onChanged={() => {}} />);

    expect(await screen.findByRole('alert')).toHaveTextContent('do not add up');
    expect(screen.getByRole('button', { name: /Close the league/ })).toBeDisabled();
  });

  it('refuses to let the league close while somebody has not settled', async () => {
    stubApi({ '/settlement/summary': summary(), '/api/me': { id: 1, role: 'USER' } });
    renderSignedIn(<SettlementTab league={league()} onChanged={() => {}} />);

    expect(await screen.findByRole('button', { name: /Close the league/ })).toBeDisabled();
    expect(screen.getByText(/2 players still to settle/)).toBeInTheDocument();
  });

  it('lets the league close once everybody is ticked off', async () => {
    stubApi({
      '/settlement/summary': summary({ pendingCount: 0, settledCount: 3 }),
      '/api/me': { id: 1, role: 'USER' },
    });
    renderSignedIn(<SettlementTab league={league()} onChanged={() => {}} />);

    expect(await screen.findByRole('button', { name: /Close the league/ })).toBeEnabled();
  });

  it('asks for a reason before undoing a settlement', async () => {
    stubApi({
      '/settlement/summary': summary({
        rows: [row({ status: 'SETTLED', settledAt: new Date().toISOString() })],
        owedMoney: [],
        oweMoney: [],
        pendingCount: 0,
      }),
      '/api/me': { id: 1, role: 'USER' },
    });
    renderSignedIn(<SettlementTab league={league()} onChanged={() => {}} />);

    await userEvent.click(await screen.findByRole('button', { name: 'Undo' }));

    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent('Undo settling with Riya?');
    // The confirm stays shut until a reason is given.
    const confirm = screen.getByRole('button', { name: /Back to pending/ });
    expect(confirm).toBeDisabled();

    await userEvent.type(screen.getByRole('textbox'), 'the transfer never arrived');
    expect(confirm).toBeEnabled();
  });

  it('marks somebody settled with the amount spelled out', async () => {
    const fetchMock = stubApi({
      '/settlement/summary': summary(),
      '/settlement/': row({ status: 'SETTLED' }),
      '/api/me': { id: 1, role: 'USER' },
    });
    renderSignedIn(<SettlementTab league={league()} onChanged={() => {}} />);

    await userEvent.click((await screen.findAllByRole('button', { name: 'Mark settled' }))[0]);
    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent('handed ₹1,050.00 back to them');

    // The dialog has its own confirm button with the same name as the one in the table.
    await userEvent.click(within(dialog).getByRole('button', { name: 'Mark settled' }));

    await waitFor(() => {
      const put = fetchMock.mock.calls.find(([, init]) => (init as RequestInit)?.method === 'PUT');
      expect(JSON.parse((put?.[1] as RequestInit).body as string)).toMatchObject({ status: 'SETTLED' });
    });
  });

  it('says nothing is settled yet while the league is still being played', async () => {
    stubApi({ '/api/me': { id: 1, role: 'USER' } });
    renderSignedIn(<SettlementTab league={league({ status: 'ACTIVE' })} onChanged={() => {}} />);

    expect(await screen.findByText(/Balances are worked out once it ends/)).toBeInTheDocument();
  });
});
