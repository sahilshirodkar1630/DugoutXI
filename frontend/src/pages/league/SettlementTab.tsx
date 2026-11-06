import { leagues } from '../../api/endpoints';
import type { League, SettlementRow } from '../../api/types';
import { Card, Empty, ErrorNote, InfoNote, Loader, Stat, Status } from '../../components/ui';
import { useLoad } from '../../hooks/useLoad';
import { usePrompt } from '../../hooks/usePrompt';
import { money, signedMoney, when } from '../../lib/format';
import { useAuth } from '../../auth/useAuth';

/**
 * Settling up at the end of a league.
 *
 * Everyone paid the whole season up front, so the organizer holds the pot. Each member's final balance is what they
 * put in, plus everything they won, minus every entry they spent, and that is exactly what the organizer hands back
 * to them. Nobody owes the organizer anything. The organizer's own row needs no transfer, and neither does a member
 * whose balance is zero, so those are settled from the start.
 *
 * The add-up check is shown rather than hidden, because it is the whole guarantee: the balances must come to what
 * was paid in, less the paise no pool could divide. The backend refuses to complete a league while it fails.
 */
export function SettlementTab({ league, onChanged }: { league: League; onChanged: () => void }) {
  const { user } = useAuth();
  const summary = useLoad(() => leagues.settlementSummary(league.id), [league.id]);
  const prompt = usePrompt(() => {
    summary.reload();
    onChanged();
  });

  const canManage = league.organizer || user?.role === 'ADMIN';

  if (!['PENDING_SETTLEMENT', 'COMPLETED', 'CANCELLED'].includes(league.status)) {
    return (
      <Card title="Not yet">
        <Empty>
          This league is {league.status.toLowerCase().replace('_', ' ')}. Balances are worked out once it ends.
        </Empty>
      </Card>
    );
  }

  return (
    <>
      {prompt.element}

      <Loader state={summary} empty={<Empty>There is nothing to settle in this league.</Empty>}>
        {(data) => (
          <>
            <Card title="The reckoning">
              <div className="grid-stats">
                <Stat label="Players" value={data.memberCount} />
                <Stat label="Paid in" value={money(data.totalPaidIn)} />
                <Stat label="Balances" value={money(data.totalFinalBalance)} />
                {Number(data.totalLeftover) > 0 && (
                  <Stat label="Unsplit paise" value={money(data.totalLeftover)} />
                )}
                <Stat label="Still to settle" value={data.pendingCount} />
                <Stat label="Settled" value={data.settledCount} />
                {data.writtenOffCount > 0 && <Stat label="Written off" value={data.writtenOffCount} />}
              </div>

              {data.balanced ? (
                <InfoNote>
                  The figures add up: the balances come to exactly what was paid in, less {money(data.totalLeftover)}{' '}
                  of paise no pool could divide evenly.
                </InfoNote>
              ) : (
                <ErrorNote>
                  The figures do not add up. The league cannot be completed until this is looked into — please
                  report it.
                </ErrorNote>
              )}
            </Card>

            {canManage && league.status === 'PENDING_SETTLEMENT' && (
              <Card
                title="Finish the league"
                subtitle="Once everyone is ticked off, close it. An admin can force it closed if somebody never pays."
              >
                <div className="row gap">
                  <button
                    type="button"
                    className="btn btn-primary"
                    disabled={data.pendingCount > 0 || !data.balanced}
                    onClick={() =>
                      prompt.ask(
                        {
                          title: `Close ${league.name}?`,
                          message: 'Everybody has settled, so the league is done.',
                          confirmLabel: 'Close the league',
                          reason: 'optional',
                        },
                        (reason) => leagues.complete(league.id, reason),
                      )
                    }
                  >
                    Close the league
                  </button>
                  {data.pendingCount > 0 && (
                    <span className="muted small">
                      {data.pendingCount} player{data.pendingCount === 1 ? '' : 's'} still to settle.
                    </span>
                  )}
                  {user?.role === 'ADMIN' && data.pendingCount > 0 && (
                    <button
                      type="button"
                      className="btn btn-danger"
                      onClick={() =>
                        prompt.ask(
                          {
                            title: 'Force the league closed?',
                            message: `Whatever is still outstanding is written off and the league is closed. ${data.pendingCount} balance(s) will be written off.`,
                            confirmLabel: 'Write off and close',
                            reason: 'required',
                            reasonLabel: 'Why is this being forced?',
                            danger: true,
                          },
                          (reason) => leagues.forceComplete(league.id, reason ?? ''),
                        )
                      }
                    >
                      Force close (admin)
                    </button>
                  )}
                </div>
              </Card>
            )}

            {data.owedMoney.length > 0 && (
              <Card
                title="Money to hand back"
                subtitle="Send each player their final balance, then tick them off."
              >
                <RowTable rows={data.owedMoney} league={league} canManage={canManage} prompt={prompt} />
              </Card>
            )}

            <Card title="Everyone" subtitle="Including the organizer, and anyone with nothing left to hand back.">
              <RowTable rows={data.rows} league={league} canManage={canManage} prompt={prompt} />
            </Card>
          </>
        )}
      </Loader>
    </>
  );
}

function RowTable({
  rows,
  league,
  canManage,
  prompt,
}: {
  rows: SettlementRow[];
  league: League;
  canManage: boolean;
  prompt: ReturnType<typeof usePrompt>;
}) {
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>Player</th>
            <th className="num">Paid in</th>
            <th className="num">Net</th>
            <th className="num">To hand back</th>
            <th>Status</th>
            {canManage && <th>Pay to</th>}
            {canManage && <th />}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => {
            return (
              <tr key={row.rowId} className={row.me ? 'is-me' : undefined}>
                <td>
                  {row.displayName}
                  {row.organizer && <span className="badge badge-info" style={{ marginLeft: 6 }}>Organizer</span>}
                  {row.writtenOffReason && <div className="muted small">{row.writtenOffReason}</div>}
                </td>
                <td className="num">{money(row.joiningAmountPaid)}</td>
                <td className="num">{signedMoney(row.netPl)}</td>
                <td className="num">
                  <strong>{row.organizer ? '—' : money(row.finalBalance)}</strong>
                </td>
                <td>
                  <Status value={row.status} />
                  {row.settledAt && <div className="muted small">{when(row.settledAt)}</div>}
                </td>
                {canManage && <td className="mono small">{row.upiId ?? '—'}</td>}
                {canManage && (
                  <td>
                    {row.status === 'PENDING' && league.status !== 'COMPLETED' && (
                      <button
                        type="button"
                        className="btn btn-primary btn-small"
                        onClick={() =>
                          prompt.ask(
                            {
                              title: `Settled with ${row.displayName}?`,
                              message: `Confirms you have handed ${money(row.finalBalance)} back to them.`,
                              confirmLabel: 'Mark settled',
                              reason: 'optional',
                            },
                            (reason) => leagues.setSettlementStatus(league.id, row.rowId, 'SETTLED', reason),
                          )
                        }
                      >
                        Mark settled
                      </button>
                    )}
                    {row.status === 'SETTLED' && !row.organizer && league.status !== 'COMPLETED' && (
                      <button
                        type="button"
                        className="btn btn-small"
                        onClick={() =>
                          prompt.ask(
                            {
                              title: `Undo settling with ${row.displayName}?`,
                              message: 'Use this if the money never actually arrived.',
                              confirmLabel: 'Back to pending',
                              reason: 'required',
                              reasonLabel: 'Why is this being undone?',
                              danger: true,
                            },
                            (reason) => leagues.setSettlementStatus(league.id, row.rowId, 'PENDING', reason),
                          )
                        }
                      >
                        Undo
                      </button>
                    )}
                  </td>
                )}
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
