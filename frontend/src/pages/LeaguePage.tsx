import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { leagues } from '../api/endpoints';
import type { League } from '../api/types';
import { Card, ErrorNote, InfoNote, Loader, Spinner, Stat, Status, Tabs } from '../components/ui';
import { useAction, useLoad } from '../hooks/useLoad';
import { usePrompt } from '../hooks/usePrompt';
import { isPast, money, relative, when } from '../lib/format';
import { shareOnWhatsApp } from '../lib/share';
import { BoardTab } from './league/BoardTab';
import { EditLeague } from './league/EditLeague';
import { LeagueContestsTab } from './league/LeagueContestsTab';
import { MembersTab } from './league/MembersTab';
import { SettlementTab } from './league/SettlementTab';

type TabId = 'board' | 'contests' | 'members' | 'settlement';

/**
 * One league, with everything about it behind four tabs.
 *
 * Which actions appear depends on where the league is in its life: joining can be opened only while it is
 * UPCOMING, members can be managed while it is OPEN, and it can be ended only once it is ACTIVE. Rather than show
 * buttons that the backend would refuse, the header only offers what is actually possible now.
 */
export function LeaguePage() {
  const { leagueId } = useParams();
  const id = Number(leagueId);
  const league = useLoad(() => leagues.get(id), [id]);
  const [tab, setTab] = useState<TabId>('board');

  if (Number.isNaN(id)) {
    return <ErrorNote>That is not a league.</ErrorNote>;
  }

  return (
    <Loader state={league}>
      {(data) => (
        <>
          <LeagueHeader league={data} onChanged={league.reload} />

          <Tabs<TabId>
            active={tab}
            onChange={setTab}
            tabs={[
              { id: 'board', label: 'Table' },
              { id: 'contests', label: 'Contests', badge: data.contestsCreated },
              { id: 'members', label: 'Players', badge: data.confirmedMembers },
              ...(['PENDING_SETTLEMENT', 'COMPLETED', 'CANCELLED'].includes(data.status)
                ? ([{ id: 'settlement', label: 'Settle up' }] as const)
                : []),
            ]}
          />

          {tab === 'board' &&
            (data.organizer || data.myMembershipStatus === 'CONFIRMED' ? (
              <BoardTab leagueId={id} />
            ) : (
              <InfoNote>The table, contests and players open up once the organizer has confirmed your place.</InfoNote>
            ))}
          {tab === 'contests' && <LeagueContestsTab league={data} onChanged={league.reload} />}
          {tab === 'members' && <MembersTab league={data} onChanged={league.reload} />}
          {tab === 'settlement' && <SettlementTab league={data} onChanged={league.reload} />}
        </>
      )}
    </Loader>
  );
}

function LeagueHeader({ league, onChanged }: { league: League; onChanged: () => void }) {
  const prompt = usePrompt(onChanged);
  const { run, busy, error } = useAction();
  const [copied, setCopied] = useState<'code' | 'link' | null>(null);
  const [editing, setEditing] = useState(false);
  const inviteLink = league.inviteCode ? `${window.location.origin}/join/${league.inviteCode}` : null;

  const joinable = league.status === 'OPEN' && !isPast(league.registrationDeadline);
  const iAmIn = league.myMembershipStatus === 'CONFIRMED';
  const iAmWaiting = league.myMembershipStatus === 'PENDING_APPROVAL' || league.myMembershipStatus === 'ACCEPTED_UNPAID';

  const copy = async (what: 'code' | 'link') => {
    const text = what === 'code' ? league.inviteCode : inviteLink;
    if (!text) return;
    try {
      await navigator.clipboard.writeText(text);
      setCopied(what);
      window.setTimeout(() => setCopied(null), 2000);
    } catch {
      // Clipboard access can be refused; the code and link are on screen anyway.
    }
  };

  return (
    <>
      {prompt.element}

      <div className="page-head">
        <div>
          <Link to="/leagues" className="muted small">
            ← All leagues
          </Link>
          <h1>{league.name}</h1>
          <p className="muted small">
            {league.tournamentName} · organized by {league.organizer ? 'you' : league.organizerDisplayName} ·{' '}
            <Status value={league.status} />
          </p>
        </div>

        <div className="row gap">
          {league.organizer && ['UPCOMING', 'OPEN'].includes(league.status) && (
            <button type="button" className="btn" onClick={() => setEditing((open) => !open)}>
              {editing ? 'Close' : 'Edit league'}
            </button>
          )}

          {league.organizer && league.status === 'UPCOMING' && (
            <button
              type="button"
              className="btn btn-primary"
              disabled={busy}
              onClick={async () => {
                const opened = await run(() => leagues.open(league.id));
                if (opened) onChanged();
              }}
            >
              Open for joining
            </button>
          )}

          {league.organizer && league.status === 'ACTIVE' && (
            <button
              type="button"
              className="btn btn-primary"
              onClick={() =>
                prompt.ask(
                  {
                    title: `End ${league.name}?`,
                    message:
                      'Every balance is worked out and frozen, and contests that were never played are cancelled. Every contest that has started must already have a result.',
                    confirmLabel: 'End the league',
                    reason: 'optional',
                  },
                  (reason) => leagues.end(league.id, reason),
                )
              }
            >
              End the league
            </button>
          )}

          {league.organizer && ['UPCOMING', 'OPEN'].includes(league.status) && (
            <button
              type="button"
              className="btn btn-danger"
              onClick={() =>
                prompt.ask(
                  {
                    title: `Cancel ${league.name}?`,
                    message:
                      'Anyone who has already paid gets a settlement row so you have a list of money to hand back. This cannot be undone.',
                    confirmLabel: 'Cancel the league',
                    reason: 'optional',
                    danger: true,
                  },
                  (reason) => leagues.cancel(league.id, reason),
                )
              }
            >
              Cancel league
            </button>
          )}

          {(league.organizer || iAmIn) && (
            <button
              type="button"
              className="btn"
              disabled={busy}
              title="An Excel copy of the table, every match, every pick and who is owed what"
              onClick={() => run(() => leagues.exportSeason(league.id))}
            >
              Download Excel
            </button>
          )}

          {!league.organizer && !iAmIn && !iAmWaiting && joinable && (
            <button
              type="button"
              className="btn btn-primary"
              disabled={busy}
              onClick={async () => {
                const asked = await run(() => leagues.requestToJoin(league.id));
                if (asked) onChanged();
              }}
            >
              Ask to join
            </button>
          )}

          {iAmWaiting && (
            <button
              type="button"
              className="btn"
              disabled={busy}
              onClick={async () => {
                const withdrawn = await run(() => leagues.withdraw(league.id));
                if (withdrawn) onChanged();
              }}
            >
              Withdraw my request
            </button>
          )}
        </div>
      </div>

      {busy && <Spinner label="Working" />}
      <ErrorNote>{error}</ErrorNote>

      {editing && <EditLeague league={league} onSaved={onChanged} onClose={() => setEditing(false)} />}

      {iAmWaiting && league.myMembershipStatus === 'ACCEPTED_UNPAID' && (
        <InfoNote>
          You are in once you have paid. {league.paymentInstructions ?? 'Ask the organizer how to pay.'} Pay the
          buy-in of {money(league.joiningAmount)} by {when(league.paymentDeadline)} ({relative(league.paymentDeadline)}
          ), or you will be taken out automatically.
        </InfoNote>
      )}

      {iAmWaiting && league.myMembershipStatus === 'PENDING_APPROVAL' && (
        <InfoNote>Your request is with the organizer. You will get an alert when they decide.</InfoNote>
      )}

      <Card title="The deal">
        <div className="grid-stats">
          <Stat
            label="Buy-in"
            value={money(league.joiningAmount)}
            hint={`${money(league.amountPerContest)} × ${league.totalContests} contests, paid up front`}
          />
          <Stat label="Players" value={league.confirmedMembers} />
          <Stat label="Contests" value={`${league.contestsCreated} of ${league.totalContests}`} />
          <Stat
            label="Joining closes"
            value={when(league.registrationDeadline)}
            hint={relative(league.registrationDeadline)}
          />
          <Stat
            label="Payment due"
            value={when(league.paymentDeadline)}
            hint={relative(league.paymentDeadline)}
          />
          {league.inviteCode && (
            <div className="stat">
              <span className="stat-label">Invite code</span>
              <strong className="stat-value mono">{league.inviteCode}</strong>
              <div className="row gap">
                <button type="button" className="btn btn-quiet btn-small" onClick={() => copy('code')}>
                  {copied === 'code' ? 'Copied' : 'Copy code'}
                </button>
                <button type="button" className="btn btn-quiet btn-small" onClick={() => copy('link')}>
                  {copied === 'link' ? 'Copied' : 'Copy invite link'}
                </button>
                {inviteLink && (
                  <button
                    type="button"
                    className="btn btn-primary btn-small"
                    onClick={() =>
                      shareOnWhatsApp(
                        `Join my league "${league.name}" on Dugout XI 🏏\n` +
                          `${money(league.amountPerContest)} a match × ${league.totalContests} matches ` +
                          `(${money(league.joiningAmount)} for the season, paid to me up front).\n` +
                          `Joining closes ${when(league.registrationDeadline)}.\n${inviteLink}`,
                      )
                    }
                  >
                    Share on WhatsApp
                  </button>
                )}
              </div>
            </div>
          )}
        </div>

        {league.paymentInstructions && (
          <p className="muted small" style={{ margin: 0 }}>
            How to pay: {league.paymentInstructions}
          </p>
        )}
      </Card>
    </>
  );
}
