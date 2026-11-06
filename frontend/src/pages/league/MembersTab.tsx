import { leagues } from '../../api/endpoints';
import type { League, Member } from '../../api/types';
import { Card, Empty, Loader, Status } from '../../components/ui';
import { useLoad } from '../../hooks/useLoad';
import { usePrompt } from '../../hooks/usePrompt';
import { money, when } from '../../lib/format';

/**
 * The members, and the organizer's side of letting people in.
 *
 * The flow has two steps on purpose, because the money moves outside the app: accepting somebody says "you have a
 * place", and confirming their payment says "the money has arrived". Only the second one makes them a player, and
 * only players are counted into a pool.
 *
 * Reversing is the undo for a confirmed member, and it always asks for a reason: somebody's money has been handed
 * back, and everyone affected can see the note afterwards.
 */
export function MembersTab({ league, onChanged }: { league: League; onChanged: () => void }) {
  const members = useLoad(() => leagues.members(league.id), [league.id]);
  const prompt = usePrompt(() => {
    members.reload();
    onChanged();
  });

  const canManage = league.organizer;
  const open = league.status === 'OPEN';

  const waiting = (members.data ?? []).filter((m) => m.status === 'PENDING_APPROVAL');
  const unpaid = (members.data ?? []).filter((m) => m.status === 'ACCEPTED_UNPAID');
  const players = (members.data ?? []).filter((m) => m.status === 'CONFIRMED');
  const gone = (members.data ?? []).filter((m) =>
    ['REJECTED', 'REMOVED', 'CANCELLED'].includes(m.status),
  );

  return (
    <>
      {prompt.element}

      {canManage && waiting.length > 0 && (
        <Card
          title={`Asking to join (${waiting.length})`}
          subtitle="Accept as paid if you already have their money, otherwise accept and they pay by the deadline."
        >
          <MemberTable
            members={waiting}
            showContact={canManage}
            actions={(member) => (
              <>
                <button
                  type="button"
                  className="btn btn-primary btn-small"
                  disabled={!open}
                  onClick={() =>
                    prompt.ask(
                      {
                        title: `Accept ${member.displayName} as paid?`,
                        message: `This confirms their place straight away, so only do it once you have their ${money(
                          league.joiningAmount,
                        )}.`,
                        confirmLabel: 'Accept as paid',
                      },
                      (reason) => leagues.accept(league.id, member.memberId, true, reason),
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
                        title: `Accept ${member.displayName}?`,
                        message: `They will have until ${when(
                          league.paymentDeadline,
                        )} to pay, and are taken out automatically if they do not.`,
                        confirmLabel: 'Accept',
                      },
                      (reason) => leagues.accept(league.id, member.memberId, false, reason),
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
                        title: `Decline ${member.displayName}?`,
                        message: 'They can ask again later if you change your mind.',
                        confirmLabel: 'Decline',
                        reason: 'optional',
                        danger: true,
                      },
                      (reason) => leagues.reject(league.id, member.memberId, reason),
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
          subtitle={`Payment is due by ${when(league.paymentDeadline)}. Anyone still unpaid after that is removed automatically.`}
        >
          <MemberTable
            members={unpaid}
            showContact={canManage}
            actions={(member) => (
              <>
                <button
                  type="button"
                  className="btn btn-primary btn-small"
                  disabled={!open}
                  onClick={() =>
                    prompt.ask(
                      {
                        title: `Mark ${member.displayName} as paid?`,
                        message: `Confirms you have received ${money(league.joiningAmount)} from them.`,
                        confirmLabel: 'Payment received',
                      },
                      (reason) => leagues.confirmPayment(league.id, member.memberId, reason),
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
                        title: `Remove ${member.displayName}?`,
                        message: 'They have not paid, so there is nothing to return.',
                        confirmLabel: 'Remove',
                        reason: 'optional',
                        danger: true,
                      },
                      (reason) => leagues.remove(league.id, member.memberId, reason),
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

      <Card title={`Players (${players.length})`} subtitle="Paid up and in every contest of this league.">
        <Loader state={members} empty={<Empty>Nobody has joined yet.</Empty>}>
          {() =>
            players.length === 0 ? (
              <Empty>Nobody is confirmed yet.</Empty>
            ) : (
              <MemberTable
                members={players}
                showContact={canManage}
                actions={
                  canManage
                    ? (member) =>
                        member.organizer ? (
                          <span className="muted small">You organize this league</span>
                        ) : (
                          <button
                            type="button"
                            className="btn btn-small"
                            disabled={!['OPEN', 'ACTIVE'].includes(league.status)}
                            onClick={() =>
                              prompt.ask(
                                {
                                  title: `Reverse ${member.displayName}'s place?`,
                                  message:
                                    league.status === 'OPEN'
                                      ? 'Use this once you have handed their joining amount back. They go back to unpaid and are taken out of every contest that has not started.'
                                      : 'The league has started, so only an admin can do this and the member can only be removed.',
                                  confirmLabel: 'Reverse',
                                  reason: 'required',
                                  reasonLabel: 'What happened to the money?',
                                  danger: true,
                                },
                                (reason) =>
                                  leagues.reverse(league.id, member.memberId, {
                                    reason: reason ?? '',
                                    target: league.status === 'OPEN' ? 'ACCEPTED_UNPAID' : 'REMOVED',
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
        <Card title="No longer in the league" subtitle="Declined, removed or withdrawn.">
          <MemberTable members={gone} showContact={false} />
        </Card>
      )}
    </>
  );
}

function MemberTable({
  members,
  showContact,
  actions,
}: {
  members: Member[];
  showContact: boolean;
  actions?: (member: Member) => React.ReactNode;
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
            <th>Asked</th>
            {actions && <th />}
          </tr>
        </thead>
        <tbody>
          {members.map((member) => (
            <tr key={member.memberId}>
              <td>
                {member.displayName}
                {member.organizer && <span className="badge badge-info" style={{ marginLeft: 6 }}>Organizer</span>}
                {member.statusReason && <div className="muted small">{member.statusReason}</div>}
              </td>
              <td>
                <Status value={member.status} />
              </td>
              <td>
                <Status value={member.paymentStatus} />
              </td>
              {showContact && (
                <td className="mono small">
                  {member.upiId ?? '—'}
                  {member.phoneNumber && <div className="muted">{member.phoneNumber}</div>}
                </td>
              )}
              <td className="muted small">{when(member.requestedAt)}</td>
              {actions && <td><div className="row gap">{actions(member)}</div></td>}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
