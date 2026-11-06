import { contests } from '../../api/endpoints';
import type { Contest } from '../../api/types';
import { Card, Empty, ErrorNote, InfoNote, Loader } from '../../components/ui';
import { useAction, useLoad } from '../../hooks/useLoad';
import { relative, when } from '../../lib/format';

/**
 * Making a pick, and seeing everyone else's once it is too late to copy them.
 *
 * Picks are private until the contest starts: the backend refuses the list before then, and the view it reads from
 * has the same rule built into it, so there is no way to peek. Changing a pick before the start is free and simply
 * replaces the old one.
 *
 * A confirmed player who never picks still has their entry in the pool — they just cannot win — which is why they
 * appear in the list with no team against their name.
 */
export function PredictionPanel({ contest }: { contest: Contest }) {
  const mine = useLoad(() => contests.myPrediction(contest.id), [contest.id]);
  const everyone = useLoad(() => contests.allPredictions(contest.id), [contest.id], {
    skip: contest.status === 'SCHEDULED',
  });
  const { run, busy, error } = useAction();

  const pick = async (teamId: number) => {
    const updated = await run(() => contests.predict(contest.id, teamId));
    if (updated) mine.setData(updated);
  };

  const locked = mine.data?.locked ?? contest.status !== 'SCHEDULED';

  return (
    <>
      <Card
        title="Your pick"
        subtitle={
          locked
            ? 'Picks are closed for this contest.'
            : `Lock in before ${when(contest.startTime)} (${relative(contest.startTime)}).`
        }
      >
        <Loader
          state={mine}
          empty={<Empty>You are not in this contest, so there is nothing to pick.</Empty>}
        >
          {(data) => (
            <>
              <div className="picks">
                {[data.teamA, data.teamB].map((team) => {
                  const chosen = data.selectedTeamId === team.id;
                  const won = contest.winningTeam?.id === team.id;
                  return (
                    <button
                      key={team.id}
                      type="button"
                      className={chosen ? 'pick pick-chosen' : 'pick'}
                      disabled={locked || busy}
                      aria-pressed={chosen}
                      onClick={() => pick(team.id)}
                    >
                      <span>{team.shortCode}</span>
                      <span className="pick-name">{team.name}</span>
                      {chosen && <span className="badge badge-info">Your pick</span>}
                      {won && <span className="badge badge-good">Won</span>}
                    </button>
                  );
                })}
              </div>

              {data.selectedTeamId === null && !locked && (
                <InfoNote>
                  No pick yet. If the contest starts without one, your entry still counts towards the pool but
                  cannot win.
                </InfoNote>
              )}
              {data.selectedTeamId === null && locked && (
                <InfoNote>You never made a pick, so your entry went into the pool as a non-winner.</InfoNote>
              )}
              {data.selectedTeamId !== null && !locked && (
                <p className="muted small" style={{ margin: 0 }}>
                  Picked {data.selectedTeamShortCode} at {when(data.predictedAt)}. You can change it until the
                  contest starts.
                </p>
              )}

              <ErrorNote>{error}</ErrorNote>
            </>
          )}
        </Loader>
      </Card>

      <Card
        title="Everyone's picks"
        subtitle={
          contest.status === 'SCHEDULED'
            ? 'Hidden until the contest starts, so nobody can copy.'
            : 'Players with no team against their name never picked.'
        }
      >
        {contest.status === 'SCHEDULED' ? (
          <Empty>Picks appear once the contest starts.</Empty>
        ) : (
          <Loader state={everyone} empty={<Empty>Nobody picked.</Empty>}>
            {(rows) => (
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>Player</th>
                      <th>Picked</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((row) => (
                      <tr key={row.userId} className={row.me ? 'is-me' : undefined}>
                        <td>
                          {row.displayName}
                          {row.me && <span className="muted small"> (you)</span>}
                        </td>
                        <td>
                          {row.selectedTeamShortCode ? (
                            <span
                              className={
                                contest.winningTeam?.id === row.selectedTeamId ? 'team team-won' : 'team'
                              }
                            >
                              {row.selectedTeamShortCode}
                            </span>
                          ) : (
                            <span className="muted">no pick</span>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Loader>
        )}
      </Card>
    </>
  );
}
