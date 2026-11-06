import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { contests } from '../api/endpoints';
import type { Contest } from '../api/types';
import { Card, ErrorNote, InfoNote, Loader, Matchup, Stat, Status, Tabs } from '../components/ui';
import { useAction, useLoad } from '../hooks/useLoad';
import { usePrompt } from '../hooks/usePrompt';
import { isPast, localInputInDays, money, relative, toIsoInstant, when } from '../lib/format';
import { shareOnWhatsApp } from '../lib/share';
import { ParticipantsPanel } from './contest/ParticipantsPanel';
import { PayoutsPanel } from './contest/PayoutsPanel';
import { PredictionPanel } from './contest/PredictionPanel';
import { ResultPanel } from './contest/ResultPanel';

type TabId = 'pick' | 'players' | 'result' | 'money';

/**
 * One contest, whether it belongs to a league or stands on its own.
 *
 * The two kinds differ mainly in money. A league contest is paid for by the league's buy-in and has no payouts of
 * its own; a one-off contest collects its own entries and settles straight away. The "Money" tab therefore only
 * appears for a one-off.
 */
export function ContestPage() {
  const { contestId } = useParams();
  const id = Number(contestId);
  const contest = useLoad(() => contests.get(id), [id]);
  const [tab, setTab] = useState<TabId>('pick');

  if (Number.isNaN(id)) {
    return <ErrorNote>That is not a contest.</ErrorNote>;
  }

  return (
    <Loader state={contest}>
      {(data) => (
        <>
          <ContestHeader contest={data} onChanged={contest.reload} />

          <Tabs<TabId>
            active={tab}
            onChange={setTab}
            tabs={[
              { id: 'pick', label: 'Picks' },
              { id: 'players', label: 'Players', badge: data.confirmedParticipants },
              { id: 'result', label: 'Result' },
              ...(data.leagueId === null ? ([{ id: 'money', label: 'Money' }] as const) : []),
            ]}
          />

          {tab === 'pick' && <PredictionPanel contest={data} />}
          {tab === 'players' && <ParticipantsPanel contest={data} onChanged={contest.reload} />}
          {tab === 'result' && <ResultPanel contest={data} onChanged={contest.reload} />}
          {tab === 'money' && <PayoutsPanel contest={data} onChanged={contest.reload} />}
        </>
      )}
    </Loader>
  );
}

function ContestHeader({ contest, onChanged }: { contest: Contest; onChanged: () => void }) {
  const prompt = usePrompt(onChanged);
  const { run, busy, error } = useAction();
  const [rescheduling, setRescheduling] = useState(false);
  const [newStart, setNewStart] = useState(localInputInDays(2));

  const standalone = contest.leagueId === null;
  const scheduled = contest.status === 'SCHEDULED';
  const mine = contest.myParticipantStatus;
  // Somebody rejected, or who withdrew, may ask again; anyone else with a row already has their place or request.
  const hasLivePlace = mine !== null && mine !== 'REJECTED' && mine !== 'CANCELLED';
  const joinable =
    standalone &&
    scheduled &&
    !isPast(contest.registrationDeadline) &&
    !isPast(contest.startTime) &&
    !contest.organizer &&
    !hasLivePlace;
  const canWithdraw =
    standalone && scheduled && !contest.organizer && (mine === 'PENDING_APPROVAL' || mine === 'ACCEPTED_UNPAID');

  const pool = Number(contest.contestAmount) * contest.confirmedParticipants;

  return (
    <>
      {prompt.element}

      <div className="page-head">
        <div>
          {contest.leagueId ? (
            <Link to={`/leagues/${contest.leagueId}`} className="muted small">
              ← {contest.leagueName}
            </Link>
          ) : (
            <Link to="/contests" className="muted small">
              ← One-off contests
            </Link>
          )}
          <h1>
            <Matchup teamA={contest.teamA} teamB={contest.teamB} winnerId={contest.winningTeam?.id} />
          </h1>
          <p className="muted small">
            {contest.name ? `${contest.name} · ` : ''}
            {contest.contestNumber ? `Match ${contest.contestNumber} · ` : ''}
            {contest.tournamentName} · organized by {contest.organizer ? 'you' : contest.organizerDisplayName} ·{' '}
            <Status value={contest.status} />
          </p>
        </div>

        <div className="row gap">
          {joinable && (
            <button
              type="button"
              className="btn btn-primary"
              disabled={busy}
              onClick={async () => {
                const asked = await run(() => contests.requestToJoin(contest.id));
                if (asked) onChanged();
              }}
            >
              {mine === 'REJECTED' || mine === 'CANCELLED' ? 'Ask again' : 'Ask to join'}
            </button>
          )}

          {canWithdraw && (
            <button
              type="button"
              className="btn"
              disabled={busy}
              onClick={async () => {
                const withdrawn = await run(() => contests.withdraw(contest.id));
                if (withdrawn) onChanged();
              }}
            >
              Withdraw my request
            </button>
          )}

          {contest.organizer && scheduled && (
            <>
              <button type="button" className="btn" onClick={() => setRescheduling((open) => !open)}>
                {rescheduling ? 'Close' : 'Reschedule'}
              </button>
              <button
                type="button"
                className="btn btn-danger"
                onClick={() =>
                  prompt.ask(
                    {
                      title: 'Cancel this contest?',
                      message: standalone
                        ? 'Everyone who has paid gets a refund to hand back. This cannot be undone.'
                        : 'This frees up the match number so you can add a replacement. Nobody is out of pocket: the league buy-in covers the season.',
                      confirmLabel: 'Cancel contest',
                      reason: 'optional',
                      danger: true,
                    },
                    (reason) => contests.cancel(contest.id, reason),
                  )
                }
              >
                Cancel contest
              </button>
            </>
          )}
        </div>
      </div>

      <ErrorNote>{error}</ErrorNote>

      {standalone && mine === 'PENDING_APPROVAL' && (
        <InfoNote>Your request is with the organizer. You will get an alert when they decide.</InfoNote>
      )}
      {standalone && mine === 'ACCEPTED_UNPAID' && (
        <InfoNote>
          You are accepted. Send the entry of {money(contest.contestAmount)} to the organizer by{' '}
          {when(contest.paymentDeadline)} ({relative(contest.paymentDeadline)}), or your place is released
          automatically. {contest.paymentInstructions ?? ''}
        </InfoNote>
      )}
      {standalone && mine === 'REJECTED' && <InfoNote>The organizer declined your request.</InfoNote>}
      {standalone && mine === 'REMOVED' && <InfoNote>You no longer have a place in this contest.</InfoNote>}

      {rescheduling && (
        <Card title="Move the start time" subtitle="Everyone is told, and the reminders are queued again.">
          <form
            className="row gap"
            onSubmit={async (event) => {
              event.preventDefault();
              const moved = await run(() => contests.changeStartTime(contest.id, toIsoInstant(newStart)));
              if (moved) {
                setRescheduling(false);
                onChanged();
              }
            }}
          >
            <input
              type="datetime-local"
              value={newStart}
              onChange={(event) => setNewStart(event.target.value)}
              style={{ maxWidth: 260 }}
              required
            />
            <button type="submit" className="btn btn-primary" disabled={busy}>
              {busy ? 'Moving…' : 'Move it'}
            </button>
          </form>
        </Card>
      )}

      {standalone && scheduled && <ShareContest contest={contest} />}

      {contest.status === 'CANCELLED' && (
        <InfoNote>Cancelled. {contest.cancelledReason}</InfoNote>
      )}

      <Card>
        <div className="grid-stats">
          <Stat
            label="Starts"
            value={when(contest.startTime)}
            hint={scheduled ? `picks lock ${relative(contest.startTime)}` : `locked ${when(contest.lockedAt)}`}
          />
          <Stat label="Entry" value={money(contest.contestAmount)} />
          <Stat
            label="Players"
            value={contest.confirmedParticipants}
            hint={`pool ${money(pool)}`}
          />
          {standalone && scheduled && (
            <Stat
              label="Joining closes"
              value={when(contest.registrationDeadline)}
              hint={relative(contest.registrationDeadline)}
            />
          )}
          {standalone && scheduled && (
            <Stat
              label="Payment due"
              value={when(contest.paymentDeadline)}
              hint={relative(contest.paymentDeadline)}
            />
          )}
          {contest.winningTeam && <Stat label="Winner" value={contest.winningTeam.shortCode} />}
          {contest.resultType === 'NO_RESULT' && <Stat label="Result" value="No result" hint="Everyone refunded" />}
        </div>

        {contest.resultType && (
          <div className="row gap">
            <button
              type="button"
              className="btn btn-small"
              onClick={() =>
                shareOnWhatsApp(
                  (contest.winningTeam
                    ? `🏆 ${contest.winningTeam.name} won ${contest.teamA.shortCode} v ${contest.teamB.shortCode}!`
                    : `${contest.teamA.shortCode} v ${contest.teamB.shortCode}: no result, everyone is refunded.`) +
                    `\nSee who called it on Dugout XI: ${window.location.origin}/contests/${contest.id}`,
                )
              }
            >
              Share result on WhatsApp
            </button>
          </div>
        )}

        {standalone && contest.paymentInstructions && (
          <p className="muted small" style={{ margin: 0 }}>
            How to pay: {contest.paymentInstructions}
          </p>
        )}

        {!standalone && (
          <p className="muted small" style={{ margin: 0 }}>
            Part of {contest.leagueName}. Your league buy-in covers this entry, and everything is netted off when
            the league ends.
          </p>
        )}
      </Card>
    </>
  );
}

/**
 * The contest number and a link to it, for the organizer to send round. Anyone signed in can open a one-off contest
 * by its link and ask for a place; somebody without an account is sent to sign in or register first and brought
 * straight back here afterwards.
 */
function ShareContest({ contest }: { contest: Contest }) {
  const [copied, setCopied] = useState<'number' | 'link' | null>(null);
  const link = `${window.location.origin}/contests/${contest.id}`;
  const canShare = typeof navigator !== 'undefined' && typeof navigator.share === 'function';

  const copy = async (what: 'number' | 'link') => {
    try {
      await navigator.clipboard.writeText(what === 'number' ? String(contest.id) : link);
      setCopied(what);
      window.setTimeout(() => setCopied(null), 2000);
    } catch {
      // Clipboard access can be refused; the number and link are on screen anyway.
    }
  };

  return (
    <Card
      title={contest.organizer ? 'Invite players' : 'Share this contest'}
      subtitle="Send the link. Friends sign in or register, then ask for a place, and you accept them."
    >
      <div className="row gap" style={{ alignItems: 'center', flexWrap: 'wrap' }}>
        <Stat label="Contest number" value={`#${contest.id}`} />
        <code className="mono small" style={{ wordBreak: 'break-all' }}>
          {link}
        </code>
      </div>
      <div className="row gap" style={{ flexWrap: 'wrap' }}>
        <button
          type="button"
          className="btn btn-primary btn-small"
          onClick={() =>
            shareOnWhatsApp(
              `🏏 ${contest.teamA.name} v ${contest.teamB.name}, ${when(contest.startTime)}\n` +
                `Entry ${money(contest.contestAmount)}. Pick the winner with me on Dugout XI (contest #${contest.id}).\n` +
                `Ask for a place before ${when(contest.registrationDeadline)}:\n${link}`,
            )
          }
        >
          Share on WhatsApp
        </button>
        <button type="button" className="btn btn-small" onClick={() => copy('link')}>
          {copied === 'link' ? 'Copied' : 'Copy invite link'}
        </button>
        <button type="button" className="btn btn-quiet btn-small" onClick={() => copy('number')}>
          {copied === 'number' ? 'Copied' : 'Copy number'}
        </button>
        {canShare && (
          <button
            type="button"
            className="btn btn-quiet btn-small"
            onClick={() =>
              navigator
                .share({
                  title: 'Join my contest on Dugout XI',
                  text: `${contest.teamA.shortCode} vs ${contest.teamB.shortCode}, entry ${money(contest.contestAmount)}. Join here:`,
                  url: link,
                })
                .catch(() => undefined)
            }
          >
            Share…
          </button>
        )}
      </div>
    </Card>
  );
}
