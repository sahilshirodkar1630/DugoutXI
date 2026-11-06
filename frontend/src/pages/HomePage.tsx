import { Link } from 'react-router-dom';
import { dashboard } from '../api/endpoints';
import type { PendingItem } from '../api/types';
import { Card, Empty, Loader, Stat, Status } from '../components/ui';
import { useLoad } from '../hooks/useLoad';
import { money, sign, signedMoney } from '../lib/format';

/**
 * The home screen: how the user is doing, what money is open in either direction, and anything waiting on them.
 *
 * The money is deliberately split three ways, because they mean different things. A payout is cash an organizer
 * owes for one standalone contest. A settlement is a whole league netted off at the end. And "waiting on you" is
 * work, not money: a result to enter, or members to chase.
 */
export function HomePage() {
  const summary = useLoad(() => dashboard.mySummary(), []);

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Your season</h1>
          <p className="muted small">Everything that is open, and how it is going.</p>
        </div>
      </div>

      <Loader state={summary}>
        {(data) => (
          <>
            <Card title="Record">
              <div className="grid-stats">
                <Stat
                  label="Net profit"
                  value={signedMoney(data.stats.netPl)}
                  tone={sign(data.stats.netPl)}
                  hint="Everything won, less every entry spent"
                />
                <Stat label="Contests played" value={data.stats.contestsPlayed} />
                <Stat
                  label="Won"
                  value={data.stats.contestsWon}
                  hint={`${data.stats.winPercentage}% of those played`}
                />
                <Stat label="Lost" value={data.stats.contestsLost} />
                <Stat
                  label="Refunded"
                  value={data.stats.contestsRefunded}
                  hint="Not counted as played"
                />
                <Stat label="Leagues joined" value={data.stats.leaguesJoined} />
              </div>
            </Card>

            {data.awaitingMyAction.length > 0 && (
              <Card title="Waiting on you" subtitle="You are the organizer, so this is yours to do.">
                <ul className="col" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
                  {data.awaitingMyAction.map((item) => (
                    <li key={`${item.kind}-${item.referenceId}`} className="row between gap">
                      <span>
                        <Link to={linkFor(item)}>{item.label}</Link>{' '}
                        <span className="muted small">
                          {item.kind === 'RESULT_DUE'
                            ? '— enter the result'
                            : `— ${item.amount} member(s) still to settle`}
                        </span>
                      </span>
                      <Status value={item.status} />
                    </li>
                  ))}
                </ul>
              </Card>
            )}

            <div className="grid">
              <Card
                title="Money owed to you"
                subtitle="Winnings and refunds from one-off contests, which the organizer sends you."
              >
                <Stat label="Total" value={money(data.pendingPayoutTotal)} tone={sign(data.pendingPayoutTotal)} />
                <PendingList items={data.pendingPayouts} emptyText="Nothing outstanding." />
              </Card>

              <Card
                title="Leagues to settle"
                subtitle="A finished league: the organizer hands back what is left of your buy-in."
              >
                <Stat
                  label="Coming back to you"
                  value={money(data.pendingSettlementTotal)}
                  tone={sign(data.pendingSettlementTotal)}
                />
                <PendingList items={data.pendingSettlements} emptyText="No leagues waiting to settle." />
              </Card>
            </div>
          </>
        )}
      </Loader>
    </>
  );
}

function PendingList({ items, emptyText }: { items: PendingItem[]; emptyText: string }) {
  if (items.length === 0) {
    return <Empty>{emptyText}</Empty>;
  }
  return (
    <ul className="col" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
      {items.map((item) => (
        <li key={`${item.kind}-${item.referenceId}-${item.amount}`} className="row between gap">
          <Link to={linkFor(item)}>{item.label}</Link>
          <span className={`num ${item.owedToMe ? 'tone-up' : 'tone-down'}`}>
            {item.owedToMe ? 'owed ' : 'you owe '}
            {money(item.amount)}
          </span>
        </li>
      ))}
    </ul>
  );
}

function linkFor(item: PendingItem): string {
  return item.kind === 'PAYOUT' || item.kind === 'RESULT_DUE'
    ? `/contests/${item.referenceId}`
    : `/leagues/${item.referenceId}`;
}
