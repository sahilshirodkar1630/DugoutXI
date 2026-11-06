import { leagues } from '../../api/endpoints';
import { Card, Empty, Loader, Stat, Status } from '../../components/ui';
import { useLoad } from '../../hooks/useLoad';
import type { LeagueBoard } from '../../api/types';
import { money, sign, signedMoney } from '../../lib/format';
import { shareOnWhatsApp } from '../../lib/share';

/**
 * The league table.
 *
 * Net profit is what the ranking is on, because that is the only number that compares players fairly: the running
 * balance includes the buy-in everyone paid equally. Nothing here is stored — the whole table is worked out from
 * the contests played so far, so it cannot drift out of step with the results.
 */
export function BoardTab({ leagueId }: { leagueId: number }) {
  const board = useLoad(() => leagues.board(leagueId), [leagueId]);

  return (
    <Loader state={board}>
      {(data) => (
        <>
          <Card title="Where it stands">
            <div className="grid-stats">
              <Stat
                label="Contests played"
                value={`${data.contestsPlayed} of ${data.contestsPlanned}`}
                hint={`${money(data.amountPerContest)} each`}
              />
              <Stat label="Buy-in" value={money(data.joiningAmount)} />
              <Stat
                label="Your net"
                value={data.me ? signedMoney(data.me.netPl) : '—'}
                tone={data.me ? sign(data.me.netPl) : undefined}
                hint={data.me ? `Balance ${money(data.me.runningBalance)}` : 'Only paid members appear'}
              />
              {Number(data.totalLeftover) > 0 && (
                <Stat
                  label="Unsplit paise"
                  value={money(data.totalLeftover)}
                  hint="Left over where a pool would not divide evenly"
                />
              )}
            </div>
          </Card>

          <Card
            title="Table"
            subtitle="Ranked by net profit, which ignores the buy-in everyone paid equally."
            actions={
              data.rows.length > 0 && (
                <button type="button" className="btn btn-small" onClick={() => shareOnWhatsApp(tableMessage(data))}>
                  Share on WhatsApp
                </button>
              )
            }
          >
            {data.rows.length === 0 ? (
              <Empty>Nobody has played a contest yet.</Empty>
            ) : (
              // On a phone this stays a table (rank, player, W, L, net) rather than a stack of cards: a leaderboard is
              // read top to bottom at a glance. The other columns are in the Excel export and on a wider screen.
              <div className="table-wrap keep-table">
                <table>
                  <thead>
                    <tr>
                      <th>#</th>
                      <th>Player</th>
                      <th className="num">W</th>
                      <th className="num">L</th>
                      <th className="num hide-phone">Ref</th>
                      <th className="num hide-phone">Won</th>
                      <th className="num hide-phone">Spent</th>
                      <th className="num">Net</th>
                      <th className="num hide-phone">Balance</th>
                      <th className="hide-phone">Settled</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.rows.map((row) => (
                      <tr key={row.userId} className={row.me ? 'is-me' : undefined}>
                        <td className="rank">{row.rank}</td>
                        <td>
                          {row.displayName}
                          {row.me && <span className="muted small"> (you)</span>}
                        </td>
                        <td className="num">{row.wonCount}</td>
                        <td className="num">{row.lostCount}</td>
                        <td className="num hide-phone">{row.refundedCount}</td>
                        <td className="num hide-phone">{money(row.winnings)}</td>
                        <td className="num hide-phone">{money(row.entriesSpent)}</td>
                        <td className={`num tone-${sign(row.netPl)}`}>{signedMoney(row.netPl)}</td>
                        <td className="num hide-phone">{money(row.runningBalance)}</td>
                        <td className="hide-phone">{row.settlementStatus ? <Status value={row.settlementStatus} /> : <span className="muted">—</span>}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Card>
        </>
      )}
    </Loader>
  );
}

/** The table as a WhatsApp message: medals for the top three, then everyone with their net. */
function tableMessage(board: LeagueBoard): string {
  const medals = ['🥇', '🥈', '🥉'];
  const lines = board.rows.map(
    (row) =>
      `${medals[row.rank - 1] ?? `${row.rank}.`} ${row.displayName}  ${signedMoney(row.netPl)}  ` +
      `(${row.wonCount}W ${row.lostCount}L)`,
  );
  return [
    `🏏 ${board.leagueName} — table after ${board.contestsPlayed} of ${board.contestsPlanned} matches`,
    '',
    ...lines,
    '',
    `Full table on Dugout XI: ${window.location.origin}/leagues/${board.leagueId}`,
  ].join('\n');
}
