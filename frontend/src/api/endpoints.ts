/**
 * Every backend call the app makes, named after what it does rather than the URL it hits.
 *
 * Screens import from here instead of building paths themselves, so a route that moves is changed in one place
 * and the compiler finds every caller.
 */
import { api, downloadFile, request } from './client';
import type {
  AdminUser,
  AuthResponse,
  Contest,
  ContestSummary,
  FixtureImportResult,
  FixtureRow,
  PeriodStats,
  StatsRange,
  TemporaryPassword,
  CreateContestBody,
  CreateLeagueBody,
  DeclareResultBody,
  League,
  LeagueBoard,
  LeagueBoardRow,
  LeagueSummary,
  Member,
  MyPrediction,
  MyStats,
  MySummary,
  Notification,
  Participant,
  ParticipantOutcome,
  ParticipantStatus,
  Payout,
  PredictionRow,
  ResultPreview,
  SettlementRow,
  SettlementSummary,
  Team,
  Tournament,
  User,
} from './types';

export const auth = {
  register: (body: { name: string; email: string; password: string; displayName?: string }) =>
    request<AuthResponse>('/api/auth/register', { method: 'POST', body, anonymous: true }),
  login: (body: { email: string; password: string }) =>
    request<AuthResponse>('/api/auth/login', { method: 'POST', body, anonymous: true }),
  /** Tells the admins somebody is locked out. Answers the same whether or not the email has an account. */
  forgotPassword: (email: string) =>
    request<void>('/api/auth/forgot-password', { method: 'POST', body: { email }, anonymous: true }),
};

export const users = {
  /** The caller's own profile. Also the cheapest way to check that a stored token is still good. */
  me: () => api.get<User>('/api/me'),
  /** Returns a new token: every other session is signed out. */
  changePassword: (currentPassword: string, newPassword: string) =>
    api.post<AuthResponse>('/api/me/password', { currentPassword, newPassword }),
  exportMine: () => downloadFile('/api/me/export', 'Dugout XI history.xlsx'),
  updateProfile: (body: {
    name?: string;
    displayName?: string;
    phoneNumber?: string;
    upiId?: string;
    photoUrl?: string;
  }) => api.patch<User>('/api/me', body),
};

export const tournaments = {
  list: () => api.get<Tournament[]>('/api/tournaments'),
  teams: (id: number) => api.get<Team[]>(`/api/tournaments/${id}/teams`),
};

export const leagues = {
  mine: () => api.get<LeagueSummary[]>('/api/leagues'),
  get: (id: number) => api.get<League>(`/api/leagues/${id}`),
  byInviteCode: (code: string) => api.get<League>(`/api/leagues/invite/${code}`),
  create: (body: CreateLeagueBody) => api.post<League>('/api/leagues', body),
  /**
   * Replaces the editable fields. `version` must be the one the organizer last saw: the backend refuses a stale
   * version, which is what stops a second tab from quietly overwriting a newer change.
   */
  update: (id: number, body: CreateLeagueBody & { version: number }) =>
    api.put<League>(`/api/leagues/${id}`, body),
  open: (id: number) => api.post<League>(`/api/leagues/${id}/open`),
  cancel: (id: number, reason?: string) => api.post<League>(`/api/leagues/${id}/cancel`, { reason }),

  // membership
  requestToJoin: (id: number) => api.post<Member>(`/api/leagues/${id}/join-requests`),
  withdraw: (id: number) => api.del<Member>(`/api/leagues/${id}/join-requests/me`),
  members: (id: number, status?: string) =>
    api.get<Member[]>(`/api/leagues/${id}/members${status ? `?status=${status}` : ''}`),
  accept: (id: number, memberId: number, paid: boolean, reason?: string) =>
    api.post<Member>(`/api/leagues/${id}/members/${memberId}/accept`, { paid, reason }),
  confirmPayment: (id: number, memberId: number, reason?: string) =>
    api.post<Member>(`/api/leagues/${id}/members/${memberId}/confirm-payment`, { reason }),
  reject: (id: number, memberId: number, reason?: string) =>
    api.post<Member>(`/api/leagues/${id}/members/${memberId}/reject`, { reason }),
  remove: (id: number, memberId: number, reason?: string) =>
    api.post<Member>(`/api/leagues/${id}/members/${memberId}/remove`, { reason }),
  reverse: (id: number, memberId: number, body: { reason: string; target: 'ACCEPTED_UNPAID' | 'REMOVED' }) =>
    api.post<Member>(`/api/leagues/${id}/members/${memberId}/reverse`, body),

  // contests inside the league
  contests: (id: number) => api.get<ContestSummary[]>(`/api/leagues/${id}/contests`),
  /** Many matches at once, all or nothing. dryRun checks the list without creating anything. */
  importFixtures: (id: number, rows: FixtureRow[], dryRun: boolean) =>
    api.post<FixtureImportResult>(`/api/leagues/${id}/contests/import`, { rows, dryRun }),
  exportSeason: (id: number) => downloadFile(`/api/leagues/${id}/export`, 'Season.xlsx'),

  // the board
  board: (id: number) => api.get<LeagueBoard>(`/api/leagues/${id}/board`),
  myLine: (id: number) => api.get<LeagueBoardRow>(`/api/leagues/${id}/board/me`),

  // settlement
  end: (id: number, reason?: string) => api.post<SettlementSummary>(`/api/leagues/${id}/end`, { reason }),
  settlementRows: (id: number) => api.get<SettlementRow[]>(`/api/leagues/${id}/settlement`),
  settlementSummary: (id: number) => api.get<SettlementSummary>(`/api/leagues/${id}/settlement/summary`),
  setSettlementStatus: (id: number, rowId: number, status: 'PENDING' | 'SETTLED', reason?: string) =>
    api.put<SettlementRow>(`/api/leagues/${id}/settlement/${rowId}/status`, { status, reason }),
  complete: (id: number, reason?: string) =>
    api.post<SettlementSummary>(`/api/leagues/${id}/complete`, { reason }),
  forceComplete: (id: number, reason: string) =>
    api.post<SettlementSummary>(`/api/leagues/${id}/force-complete`, { reason }),
};

export const contests = {
  mine: () => api.get<ContestSummary[]>('/api/contests/mine'),
  get: (id: number) => api.get<Contest>(`/api/contests/${id}`),
  create: (body: CreateContestBody) => api.post<Contest>('/api/contests', body),
  changeStartTime: (id: number, startTime: string, reason?: string) =>
    api.patch<Contest>(`/api/contests/${id}/start-time`, { startTime, reason }),
  cancel: (id: number, reason?: string) => api.post<Contest>(`/api/contests/${id}/cancel`, { reason }),

  // joining a standalone contest
  requestToJoin: (id: number) => api.post<Participant>(`/api/contests/${id}/join-requests`),
  withdraw: (id: number) => api.del<Participant>(`/api/contests/${id}/join-requests/me`),
  participants: (id: number, status?: ParticipantStatus) =>
    api.get<Participant[]>(`/api/contests/${id}/participants${status ? `?status=${status}` : ''}`),
  accept: (id: number, participantId: number, paid: boolean, reason?: string) =>
    api.post<Participant>(`/api/contests/${id}/participants/${participantId}/accept`, { paid, reason }),
  confirmPayment: (id: number, participantId: number, reason?: string) =>
    api.post<Participant>(`/api/contests/${id}/participants/${participantId}/confirm-payment`, { reason }),
  reject: (id: number, participantId: number, reason?: string) =>
    api.post<Participant>(`/api/contests/${id}/participants/${participantId}/reject`, { reason }),
  remove: (id: number, participantId: number, reason?: string) =>
    api.post<Participant>(`/api/contests/${id}/participants/${participantId}/remove`, { reason }),
  reverse: (id: number, participantId: number, body: { reason: string; target: 'ACCEPTED_UNPAID' | 'REMOVED' }) =>
    api.post<Participant>(`/api/contests/${id}/participants/${participantId}/reverse`, body),

  // predictions
  myPrediction: (id: number) => api.get<MyPrediction>(`/api/contests/${id}/predictions/me`),
  predict: (id: number, teamId: number) =>
    api.put<MyPrediction>(`/api/contests/${id}/predictions/me`, { teamId }),
  allPredictions: (id: number) => api.get<PredictionRow[]>(`/api/contests/${id}/predictions`),

  // results
  previewResult: (id: number, body: DeclareResultBody) =>
    api.post<ResultPreview>(`/api/contests/${id}/result/preview`, body),
  confirmResult: (id: number, body: DeclareResultBody) =>
    api.post<ResultPreview>(`/api/contests/${id}/result/confirm`, body),
  correctResult: (id: number, body: DeclareResultBody) =>
    api.post<ResultPreview>(`/api/contests/${id}/result/correct`, body),
  outcomes: (id: number) => api.get<ParticipantOutcome[]>(`/api/contests/${id}/outcomes`),

  // payouts
  payouts: (id: number) => api.get<Payout[]>(`/api/contests/${id}/payouts`),
  setPayoutStatus: (id: number, payoutId: number, status: 'PENDING' | 'PAID_OUT', reason?: string) =>
    api.put<Payout>(`/api/contests/${id}/payouts/${payoutId}/status`, { status, reason }),
};

export const dashboard = {
  myStats: () => api.get<MyStats>('/api/me/stats'),
  periodStats: (range: StatsRange) => api.get<PeriodStats>(`/api/me/stats/period?range=${range}`),
  mySummary: () => api.get<MySummary>('/api/me/summary'),
};

export const notifications = {
  list: (limit = 50) => api.get<Notification[]>(`/api/notifications?limit=${limit}`),
  unreadCount: () => api.get<{ unread: number }>('/api/notifications/unread-count'),
  markRead: (id: number) => api.post<Notification>(`/api/notifications/${id}/read`),
  markAllRead: () => api.post<{ marked: number }>('/api/notifications/read-all'),
};

export const admin = {
  users: (search?: string) =>
    api.get<AdminUser[]>(`/api/admin/users${search ? `?search=${encodeURIComponent(search)}` : ''}`),
  setUserActive: (id: number, active: boolean, reason: string) =>
    api.put<User>(`/api/admin/users/${id}/active`, { active, reason }),
  setUserRole: (id: number, role: 'USER' | 'ADMIN', reason: string) =>
    api.put<User>(`/api/admin/users/${id}/role`, { role, reason }),
  /** A temporary password, shown once, which the person must replace when they sign in. */
  resetPassword: (id: number, reason: string) =>
    api.post<TemporaryPassword>(`/api/admin/users/${id}/password-reset`, { reason }),

  /** Every tournament, retired ones included. */
  allTournaments: () => api.get<Tournament[]>('/api/admin/tournaments'),
  /** Every team, inactive ones included. */
  allTeams: () => api.get<Team[]>('/api/admin/teams'),
  /** The teams linked to a tournament, inactive ones included. */
  tournamentTeams: (id: number) => api.get<Team[]>(`/api/admin/tournaments/${id}/teams`),

  createTournament: (body: { name: string; startDate?: string; endDate?: string }) =>
    api.post<Tournament>('/api/admin/tournaments', body),
  setTournamentActive: (id: number, active: boolean, reason: string) =>
    api.put<Tournament>(`/api/admin/tournaments/${id}/active`, { active, reason }),
  createTeam: (body: { name: string; shortCode: string; logoUrl?: string }) =>
    api.post<Team>('/api/admin/teams', body),
  setTeamActive: (id: number, active: boolean, reason: string) =>
    api.put<Team>(`/api/admin/teams/${id}/active`, { active, reason }),
  addTeamToTournament: (tournamentId: number, teamId: number) =>
    api.put<Team[]>(`/api/admin/tournaments/${tournamentId}/teams/${teamId}`),
  removeTeamFromTournament: (tournamentId: number, teamId: number, reason: string) =>
    // The backend reads the reason from the query string, not a body.
    api.del<void>(`/api/admin/tournaments/${tournamentId}/teams/${teamId}?reason=${encodeURIComponent(reason)}`),

  // Only available when the backend runs with app.dev-tools.enabled=true.
  fastForwardContest: (contestId: number) =>
    api.post<{ contestId: number; changed: boolean }>(`/api/dev/contests/${contestId}/fast-forward`),
  startContestNow: (contestId: number) =>
    api.post<{ contestId: number; contestStatus: string }>(`/api/dev/contests/${contestId}/start-now`),
  runJobs: () => api.post<Record<string, number>>('/api/dev/jobs/run'),
};
