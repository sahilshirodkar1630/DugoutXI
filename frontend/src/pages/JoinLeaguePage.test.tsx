import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { League } from '../api/types';
import { stubApi } from '../test/render';
import { JoinLeaguePage } from './JoinLeaguePage';

function league(overrides: Partial<League> = {}): League {
  const soon = new Date(Date.now() + 3 * 86_400_000).toISOString();
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
    registrationDeadline: soon,
    paymentDeadline: soon,
    paymentInstructions: null,
    inviteCode: null,
    status: 'OPEN',
    startedAt: null,
    endedAt: null,
    completedAt: null,
    contestsCreated: 0,
    confirmedMembers: 4,
    organizer: false,
    myMembershipStatus: null,
    myPaymentStatus: null,
    version: 0,
    ...overrides,
  };
}

/** The join page plus a stand-in for the league page, so a successful request can be seen to land there. */
function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/join/:code" element={<JoinLeaguePage />} />
        <Route path="/leagues/:leagueId" element={<p>League page</p>} />
      </Routes>
    </MemoryRouter>,
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('JoinLeaguePage', () => {
  it('shows what the league costs and asks for a place', async () => {
    const fetchMock = stubApi({
      '/api/leagues/invite/ABCD1234': league(),
      '/api/leagues/3/join-requests': { memberId: 9, status: 'PENDING_APPROVAL' },
    });
    renderAt('/join/abcd1234');

    expect(await screen.findByText('Office IPL')).toBeInTheDocument();
    expect(screen.getByText('₹1,000.00')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /Ask to join Office IPL/ }));

    expect(await screen.findByText('League page')).toBeInTheDocument();
    const post = fetchMock.mock.calls.find(([, init]) => (init as RequestInit)?.method === 'POST');
    expect(String(post?.[0])).toContain('/api/leagues/3/join-requests');
  });

  it('looks the code up in capitals whatever was typed', async () => {
    const fetchMock = stubApi({ '/api/leagues/invite/ABCD1234': league() });
    renderAt('/join/abcd1234');

    await screen.findByText('Office IPL');
    expect(String(fetchMock.mock.calls[0][0])).toContain('/api/leagues/invite/ABCD1234');
  });

  it('sends somebody who already has a place straight to the league', async () => {
    stubApi({ '/api/leagues/invite/ABCD1234': league({ myMembershipStatus: 'CONFIRMED' }) });
    renderAt('/join/ABCD1234');

    expect(await screen.findByRole('link', { name: 'Open the league' })).toHaveAttribute('href', '/leagues/3');
    expect(screen.queryByRole('button', { name: /Ask to join/ })).not.toBeInTheDocument();
  });

  it('lets somebody who was turned down ask again', async () => {
    stubApi({ '/api/leagues/invite/ABCD1234': league({ myMembershipStatus: 'REJECTED' }) });
    renderAt('/join/ABCD1234');

    expect(await screen.findByRole('button', { name: /Ask to join/ })).toBeEnabled();
  });

  it('says joining is closed instead of offering a button that would be refused', async () => {
    stubApi({ '/api/leagues/invite/ABCD1234': league({ status: 'ACTIVE' }) });
    renderAt('/join/ABCD1234');

    expect(await screen.findByText('Joining is closed for this league.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Ask to join/ })).not.toBeInTheDocument();
  });

  it('explains an unknown code', async () => {
    stubApi({});
    renderAt('/join/NOPE0000');

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
  });
});
