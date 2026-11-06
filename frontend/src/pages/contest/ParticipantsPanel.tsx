import { contests } from '../../api/endpoints';
import type { Contest, Participant } from '../../api/types';
import { Card, Empty, InfoNote, Loader, Status } from '../../components/ui';
import { useLoad } from '../../hooks/useLoad';
import { usePrompt } from '../../hooks/usePrompt';
import { money, when } from '../../lib/format';

/**
 * Who is in a one-off contest, and the organizer's side of letting people in.
 *
 * Same two steps as a league: accepting gives somebody a place, confirming their payment makes them a player. Only
 * players are counted into the pool, which is why an unpaid place is taken out automatically before the contest
 * locks rather than being carried along.
 *
 * A league contest has no join flow at all — being a confirmed member of the league is the ticket — so this panel
 * is read-only for one of those.
 */
export function ParticipantsPanel({ contest, onChanged }: { contest: Contest; onChanged: () => void }) {
  const participants = useLoad(() => contests.participants(contest.id), [contest.id]);
  const prompt = usePrompt(() => {
    participants.reload();
    onChanged();
  });

  const standalone = contest.leagueId === null;
  const canManage = contest.organizer && standalone;
  const open = contest.status === 'SCHEDULED';

  const rows = participants.data ?? [];
  const waiting = rows.filter((p) => p.status === 'PENDING_APPROVAL');
  const unpaid = rows.filter((p) => p.status === 'ACCEPTED_UNPAID');
  const players = rows.filter((p) => p.status === 'CONFIRMED');
  const gone = rows.filter((p) => ['REJECTED', 'REMOVED', 'CANCELLED'].includes(p.status));

  return (
    <>
      {prompt.element}

      {!standalone && (
        <InfoNote>
          This contest belongs to a league, so everyone confirmed in the league plays in it. Nobody joins a single
          match on its own.
        </InfoNote>
      )}

      {canManage && waiting.length > 0 && (
        <Card title={`Asking to join (${waiting.length})`}>
          <Table
            rows={waiting}
            showContact
            actions={(person) => (
              <>
                <button
                  type="button"
                  className="btn btn-primary btn-small"
                  disabled={!open}
                  onClick={() =>
                    prompt.ask(
                      {
                        title: `Accept ${person.displayName} as paid?`,
                        message: `Only do this once you have their ${money(contest.contestAmount)}.`,
                        confirmLabel: 'Accept as paid',
                      },
                      (reason) => contests.accept(contest.id, person.participantId, true, reason),
                    )
                  }
                >
                  Accept as paid
                </button>
                <button
                  type="button"
                  className="btn btn-small"
                  disabled={!open}
                  onClick={() =>
                    prompt.ask(
                      {
                        title: `Accept ${person.displayName}?`,
                        message: `They must pay by ${when(contest.paymentDeadline)} or they are taken out before the contest starts.`,
                        confirmLabel: 'Accept',
                      },
                      (reason) => contests.accept(contest.id, person.participantId, false, reason),
                    )
                  }
                >
                  Accept, unpaid
                </button>
                <button
                  type="button"
                  className="btn btn-small"
                  disabled={!open}
                  onClick={() =>
                    prompt.ask(
                      {
                        title: `Decline ${person.displayName}?`,
                        confirmLabel: 'Decline',
                        reason: 'optional',
                        danger: true,
                      },
                      (reason) => contests.reject(contest.id, person.participantId, reason),
                    )
                  }
                >
                  Decline
                </button>
              </>
            )}
          />
        </Card>
      )}

      {canManage && unpaid.length > 0 && (
        <Card
          title={`Waiting to pay (${unpaid.length})`}
          subtitle={`Due by ${when(contest.paymentDeadline)}. Anyone unpaid is taken out before the contest locks.`}
        >
          <Table
            rows={unpaid}
            showContact
            actions={(person) => (
              <>
                <button
                  type="button"
                  className="btn btn-primary btn-small"
                  disabled={!open}
                  onClick={() =>
                    prompt.ask(
                      {
                        title: `Mark ${person.displayName} as paid?`,
                        message: `Confirms you have received ${money(contest.contestAmount)}.`,
                        confirmLabel: 'Payment received',
                      },
                      (reason) => contests.confirmPayment(contest.id, person.participantId, reason),
                    )
                  }
                >
                  Payment received
                </button>
                <button
                  type="button"
                  className="btn btn-small"
                  disabled={!open}
                  onClick={() =>
                    prompt.ask(
                      {
                        title: `Remove ${person.displayName}?`,
                        message: 'They have not paid, so there is nothing to return.',
                        confirmLabel: 'Remove',
                        reason: 'optional',
                        danger: true,
                      },
                      (reason) => contests.remove(contest.id, person.participantId, reason),
                    )
                  }
                >
                  Remove
                </button>
              </>
            )}
          />
        </Card>
      )}

      <Card title={`Players (${players.length})`} subtitle="Paid up, and counted into the pool.">
        <Loader state={participants} empty={<Empty>Nobody is in yet.</Empty>}>
          {() =>
            players.length === 0 ? (
              <Empty>Nobody is confirmed yet.</Empty>
            ) : (
              <Table
                rows={players}
                showContact={canManage}
                actions={
                  canManage
                    ? (person) =>
                        person.organizer ? (
                          <span className="muted small">You organize this contest</span>
                        ) : (
                          <button
                            type="button"
                            className="btn btn-small"
                            disabled={!open}
                            onClick={() =>
                              prompt.ask(
                                {
                                  title: `Reverse ${person.displayName}'s place?`,
                                  message:
                                    'Use this once you have handed their entry back. Only possible before the contest starts.',
                                  confirmLabel: 'Reverse',
                                  reason: 'required',
                                  reasonLabel: 'What happened to the money?',
                                  danger: true,
                                },
                                (reason) =>
                                  contests.reverse(contest.id, person.participantId, {
                                    reason: reason ?? '',
                                    target: 'REMOVED',
                                  }),
                              )
                            }
                          >
                            Reverse
                          </button>
                        )
                    : undefined
                }
              />
            )
          }
        </Loader>
      </Card>

      {canManage && gone.length > 0 && (
        <Card title="No longer in the contest">
          <Table rows={gone} showContact={false} />
        </Card>
      )}
    </>
  );
}

function Table({
  rows,
  showContact,
  actions,
}: {
  rows: Participant[];
  showContact: boolean;
  actions?: (person: Participant) => React.ReactNode;
}) {
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>Player</th>
            <th>Status</th>
            <th>Payment</th>
            {showContact && <th>Pay to</th>}
            {actions && <th />}
          </tr>
        </thead>
        <tbody>
          {rows.map((person) => (
            <tr key={person.participantId} className={person.me ? 'is-me' : undefined}>
              <td>
                {person.displayName}
                {person.organizer && <span className="badge badge-info" style={{ marginLeft: 6 }}>Organizer</span>}
                {person.statusReason && <div className="muted small">{person.statusReason}</div>}
              </td>
              <td>
                <Status value={person.status} />
              </td>
              <td>
                <Status value={person.paymentStatus} />
              </td>
              {showContact && (
                <td className="mono small">
                  {person.upiId ?? '—'}
                  {person.phoneNumber && <div className="muted">{person.phoneNumber}</div>}
                </td>
              )}
              {actions && (
                <td>
                  <div className="row gap">{actions(person)}</div>
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
