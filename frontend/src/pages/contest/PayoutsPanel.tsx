import { contests } from '../../api/endpoints';
import type { Contest } from '../../api/types';
import { Card, Empty, Loader, Status } from '../../components/ui';
import { useLoad } from '../../hooks/useLoad';
import { usePrompt } from '../../hooks/usePrompt';
import { money, when } from '../../lib/format';

/**
 * The money for a one-off contest.
 *
 * The app never moves a rupee. A payout is a record of what the organizer owes somebody, which they tick off once
 * they have actually sent it; the contest is settled only when nothing is still pending. The organizer's own share
 * is marked paid from the start, because there is nothing for them to send themselves.
 *
 * A player sees only their own line. The organizer sees everyone's, with the UPI id they need in order to pay.
 */
export function PayoutsPanel({ contest, onChanged }: { contest: Contest; onChanged: () => void }) {
  const payouts = useLoad(() => contests.payouts(contest.id), [contest.id]);
  const prompt = usePrompt(() => {
    payouts.reload();
    onChanged();
  });

  return (
    <>
      {prompt.element}
      <Card
        title="Money"
        subtitle={
          contest.organizer
            ? 'Send each amount, then tick it off. The contest is settled once nothing is pending.'
            : 'What the organizer owes you for this contest.'
        }
      >
        <Loader
          state={payouts}
          empty={<Empty>Nothing to pay out. Either the result is not in yet, or nobody was owed anything.</Empty>}
        >
          {(rows) => (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Player</th>
                    <th>Kind</th>
                    <th className="num">Amount</th>
                    <th>Status</th>
                    {contest.organizer && <th>Pay to</th>}
                    {contest.organizer && <th />}
                  </tr>
                </thead>
                <tbody>
                  {rows.map((payout) => (
                    <tr key={payout.id} className={payout.me ? 'is-me' : undefined}>
                      <td>
                        {payout.displayName}
                        {payout.me && <span className="muted small"> (you)</span>}
                      </td>
                      <td>
                        <Status value={payout.type} />
                      </td>
                      <td className="num">{money(payout.amount)}</td>
                      <td>
                        <Status value={payout.status} />
                        {payout.paidOutAt && <div className="muted small">{when(payout.paidOutAt)}</div>}
                      </td>
                      {contest.organizer && <td className="mono small">{payout.upiId ?? '—'}</td>}
                      {contest.organizer && (
                        <td>
                          {payout.status === 'PENDING' && (
                            <button
                              type="button"
                              className="btn btn-primary btn-small"
                              onClick={() =>
                                prompt.ask(
                                  {
                                    title: `Paid ${payout.displayName}?`,
                                    message: `Confirms you have sent them ${money(payout.amount)}.`,
                                    confirmLabel: 'Mark as paid',
                                  },
                                  () => contests.setPayoutStatus(contest.id, payout.id, 'PAID_OUT'),
                                )
                              }
                            >
                              Mark as paid
                            </button>
                          )}
                          {payout.status === 'PAID_OUT' && !payout.me && (
                            <button
                              type="button"
                              className="btn btn-small"
                              onClick={() =>
                                prompt.ask(
                                  {
                                    title: `Undo paying ${payout.displayName}?`,
                                    message:
                                      'Use this if the transfer never actually went through. The contest reopens until it is settled again.',
                                    confirmLabel: 'Back to pending',
                                    reason: 'required',
                                    reasonLabel: 'Why is this being undone?',
                                    danger: true,
                                  },
                                  (reason) =>
                                    contests.setPayoutStatus(contest.id, payout.id, 'PENDING', reason),
                                )
                              }
                            >
                              Undo
                            </button>
                          )}
                          {payout.status === 'VOID' && <span className="muted small">Replaced by a correction</span>}
                        </td>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Loader>
      </Card>
    </>
  );
}
