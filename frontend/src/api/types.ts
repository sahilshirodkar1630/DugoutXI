/**
 * The shapes the backend sends and accepts.
 *
 * These mirror the Java records one for one. Keeping them in a single file means a change to an endpoint shows up
 * as a type error in every screen that reads it, rather than as an undefined at runtime.
 */

// ---------- auth and users ----------

export type Role = 'USER' | 'ADMIN';

export interface User {
  id: number;
  name: string;
  displayName: string;
  email: string;
  phoneNumber: string | null;
  photoUrl: string | null;
  upiId: string | null;
  role: Role;
  /** True after an admin reset: the app asks for a new password before anything else. */
  mustChangePassword: boolean;
}

export interface AuthResponse {
  token: string;
  expiresAt: string;
  user: User;
}

/** What the admin user list returns: the active flag matters here, contact details do not. */
export interface AdminUser {
  id: number;
  name: string;
  displayName: string;
  email: string;
  role: Role;
  active: boolean;
}

// ---------- tournaments and teams ----------

export interface Team {
  id: number;
  name: string;
  shortCode: string;
  logoUrl: string | null;
  active: boolean;
}

export interface Tournament {
  id: number;
  name: string;
  startDate: string | null;
  endDate: string | null;
  active: boolean;
}

export interface TeamRef {
  id: number;
  name: string;
  shortCode: string;
}

// ---------- leagues ----------

export type LeagueStatus =
  | 'UPCOMING'
  | 'OPEN'
  | 'ACTIVE'
  | 'PENDING_SETTLEMENT'
  | 'COMPLETED'
  | 'CANCELLED';

export type LeagueMemberStatus =
  | 'PENDING_APPROVAL'
  | 'ACCEPTED_UNPAID'
  | 'CONFIRMED'
  | 'REJECTED'
  | 'REMOVED'
  | 'CANCELLED';

export interface League {
  id: number;
  name: string;
  tournamentId: number;
  tournamentName: string;
  organizerId: number;
  organizerDisplayName: string;
  amountPerContest: string;
  totalContests: number;
  joiningAmount: string;
  registrationStart: string;
  registrationDeadline: string;
  paymentDeadline: string;
  paymentInstructions: string | null;
  inviteCode: string | null;
  status: LeagueStatus;
  startedAt: string | null;
  endedAt: string | null;
  completedAt: string | null;
  /** Contests created so far that are not cancelled, out of totalContests. */
  contestsCreated: number;
  confirmedMembers: number;
  organizer: boolean;
  myMembershipStatus: LeagueMemberStatus | null;
  myPaymentStatus: 'PENDING' | 'PAID' | null;
  version: number;
}

export interface LeagueSummary {
  id: number;
  name: string;
  tournamentName: string;
  organizerDisplayName: string;
  status: LeagueStatus;
  amountPerContest: string;
  joiningAmount: string;
  totalContests: number;
  confirmedMembers: number;
  organizer: boolean;
  myMembershipStatus: LeagueMemberStatus | null;
}

export interface CreateLeagueBody {
  name: string;
  tournamentId: number;
  amountPerContest: number;
  totalContests: number;
  registrationStart: string;
  registrationDeadline: string;
  paymentDeadline: string;
  paymentInstructions?: string;
}

export interface Member {
  memberId: number;
  userId: number;
  displayName: string;
  phoneNumber: string | null;
  upiId: string | null;
  status: LeagueMemberStatus;
  paymentStatus: 'PENDING' | 'PAID';
  paidAt: string | null;
  requestedAt: string;
  confirmedAt: string | null;
  removedAt: string | null;
  statusReason: string | null;
  organizer: boolean;
}

// ---------- contests ----------

export type ContestStatus = 'SCHEDULED' | 'LOCKED' | 'RESULT_DECLARED' | 'SETTLED' | 'CANCELLED';

export type ParticipantStatus = LeagueMemberStatus;

export interface Contest {
  id: number;
  leagueId: number | null;
  leagueName: string | null;
  name: string | null;
  contestNumber: number | null;
  tournamentId: number;
  tournamentName: string;
  teamA: TeamRef;
  teamB: TeamRef;
  startTime: string;
  contestAmount: string;
  registrationDeadline: string | null;
  paymentDeadline: string | null;
  paymentInstructions: string | null;
  status: ContestStatus;
  resultType: 'WINNER' | 'NO_RESULT' | null;
  /** Filled only once a result is declared. */
  winningTeamId: number | null;
  winningTeam: TeamRef | null;
  leftoverAmount: string;
  lockedAt: string | null;
  cancelledReason: string | null;
  confirmedParticipants: number;
  /** The caller's own place in the contest, if they have one. */
  myParticipantStatus: ParticipantStatus | null;
  standalone: boolean;
  organizer: boolean;
  organizerDisplayName: string;
  version: number;
}

export interface ContestSummary {
  id: number;
  leagueId: number | null;
  name: string | null;
  contestNumber: number | null;
  teamA: TeamRef;
  teamB: TeamRef;
  startTime: string;
  contestAmount: string;
  status: ContestStatus;
  resultType: 'WINNER' | 'NO_RESULT' | null;
  winningTeamId: number | null;
  winningTeam: TeamRef | null;
  confirmedParticipants: number;
  /** Only filled in the caller's own contest list. */
  myParticipantStatus: ParticipantStatus | null;
}

export interface CreateContestBody {
  leagueId?: number;
  teamAId: number;
  teamBId: number;
  startTime: string;
  name?: string;
  contestNumber?: number;
  tournamentId?: number;
  amount?: number;
  registrationDeadline?: string;
  paymentDeadline?: string;
  paymentInstructions?: string;
}

export interface Participant {
  participantId: number;
  contestId: number;
  userId: number;
  displayName: string;
  phoneNumber: string | null;
  upiId: string | null;
  status: ParticipantStatus;
  paymentStatus: 'NOT_REQUIRED' | 'PENDING' | 'PAID';
  requestedAt: string;
  confirmedAt: string | null;
  removedAt: string | null;
  statusReason: string | null;
  organizer: boolean;
  me: boolean;
}

// ---------- predictions ----------

export interface MyPrediction {
  contestId: number;
  selectedTeamId: number | null;
  selectedTeamName: string | null;
  selectedTeamShortCode: string | null;
  predictedAt: string | null;
  startTime: string;
  locked: boolean;
  teamA: TeamRef;
  teamB: TeamRef;
}

export interface PredictionRow {
  userId: number;
  displayName: string;
  selectedTeamId: number | null;
  selectedTeamShortCode: string | null;
  me: boolean;
}

// ---------- results ----------

export interface ParticipantOutcome {
  participantId: number;
  userId: number;
  displayName: string;
  selectedTeamId: number | null;
  selectedTeamShortCode: string | null;
  result: 'WON' | 'LOST' | 'REFUNDED' | null;
  entrySpent: string;
  winnings: string;
  netPl: string;
}

export interface ResultPreview {
  contestId: number;
  contestLabel: string;
  winningTeamId: number | null;
  winningTeamShortCode: string | null;
  noResult: boolean;
  entryAmount: string;
  confirmedCount: number;
  pool: string;
  winnerCount: number;
  perWinner: string;
  leftover: string;
  everyoneRefunded: boolean;
  refundReason: string | null;
  outcomes: ParticipantOutcome[];
  previewToken: string;
}

export interface DeclareResultBody {
  winningTeamId?: number | null;
  noResult?: boolean;
  previewToken?: string;
  reason?: string;
}

// ---------- payouts ----------

export interface Payout {
  id: number;
  contestId: number;
  userId: number;
  displayName: string;
  upiId: string | null;
  type: 'WINNING' | 'REFUND';
  amount: string;
  status: 'PENDING' | 'PAID_OUT' | 'VOID';
  paidOutAt: string | null;
  me: boolean;
}

// ---------- settlement ----------

export type SettlementStatus = 'PENDING' | 'SETTLED' | 'WRITTEN_OFF';

export interface SettlementRow {
  rowId: number;
  leagueId: number;
  userId: number;
  displayName: string;
  phoneNumber: string | null;
  upiId: string | null;
  organizer: boolean;
  joiningAmountPaid: string;
  netPl: string;
  finalBalance: string;
  status: SettlementStatus;
  settledAt: string | null;
  writtenOffReason: string | null;
  me: boolean;
}

export interface SettlementSummary {
  leagueId: number;
  leagueName: string;
  leagueStatus: LeagueStatus;
  memberCount: number;
  totalPaidIn: string;
  totalFinalBalance: string;
  totalLeftover: string;
  balanced: boolean;
  pendingCount: number;
  settledCount: number;
  writtenOffCount: number;
  owedMoney: SettlementRow[];
  oweMoney: SettlementRow[];
  rows: SettlementRow[];
}

// ---------- dashboard ----------

export interface LeagueBoardRow {
  rank: number;
  userId: number;
  displayName: string;
  wonCount: number;
  lostCount: number;
  refundedCount: number;
  winnings: string;
  entriesSpent: string;
  netPl: string;
  joiningAmountPaid: string;
  runningBalance: string;
  settlementStatus: SettlementStatus | null;
  me: boolean;
}

export interface LeagueBoard {
  leagueId: number;
  leagueName: string;
  leagueStatus: LeagueStatus;
  contestsPlayed: number;
  contestsPlanned: number;
  amountPerContest: string;
  joiningAmount: string;
  totalLeftover: string;
  rows: LeagueBoardRow[];
  me: LeagueBoardRow | null;
}

export interface MyStats {
  userId: number;
  displayName: string;
  leaguesJoined: number;
  contestsPlayed: number;
  contestsWon: number;
  contestsLost: number;
  contestsRefunded: number;
  amountWon: string;
  amountLost: string;
  netPl: string;
  winPercentage: string;
}

/** Mirrors TemporaryPasswordResponse. Shown to the admin once. */
export interface TemporaryPassword {
  userId: number;
  displayName: string;
  email: string;
  temporaryPassword: string;
}

export type StatsRange = 'ONE_MONTH' | 'THREE_MONTHS' | 'SIX_MONTHS' | 'ONE_YEAR' | 'ALL';

/** Mirrors PeriodStatsResponse. Money is a string, as everywhere. */
export interface PeriodStats {
  range: StatsRange;
  from: string | null;
  to: string;
  contestsPlayed: number;
  contestsWon: number;
  contestsLost: number;
  contestsRefunded: number;
  contestsPending: number;
  winPercentage: string;
  amountInvested: string;
  amountWon: string;
  netPl: string;
  returnPercentage: string;
  biggestWin: string;
  bestStreak: number;
  currentStreak: number;
  leaguesPlayed: number;
  leagueContestsPlayed: number;
  oneOffContestsPlayed: number;
  leagueNetPl: string;
  oneOffNetPl: string;
  months: { month: string; played: number; won: number; invested: string; netPl: string }[];
}

/** One row of a fixture import, as sent (FixtureRow). */
export interface FixtureRow {
  teamA: string;
  teamB: string;
  startTime: string;
  name?: string;
  contestNumber?: number;
}

/** Mirrors ImportFixturesResponse. */
export interface FixtureImportResult {
  ok: boolean;
  dryRun: boolean;
  created: number;
  rows: {
    row: number;
    teamA: TeamRef | null;
    teamB: TeamRef | null;
    startTime: string;
    name: string | null;
    contestNumber: number | null;
    contestId: number | null;
    error: string | null;
  }[];
}

export interface PendingItem {
  kind: 'PAYOUT' | 'SETTLEMENT' | 'RESULT_DUE' | 'SETTLEMENT_DUE';
  referenceId: number;
  label: string;
  amount: string;
  owedToMe: boolean;
  status: string;
}

export interface MySummary {
  stats: MyStats;
  unreadNotifications: number;
  pendingPayoutTotal: string;
  pendingPayouts: PendingItem[];
  pendingSettlementTotal: string;
  pendingSettlements: PendingItem[];
  awaitingMyAction: PendingItem[];
}

// ---------- notifications ----------

export interface Notification {
  id: number;
  type: string;
  referenceType: 'LEAGUE' | 'CONTEST' | null;
  referenceId: number | null;
  title: string;
  message: string | null;
  sentAt: string;
  readAt: string | null;
}
