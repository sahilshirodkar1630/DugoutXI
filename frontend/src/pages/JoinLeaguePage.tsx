import { Link, useNavigate, useParams } from 'react-router-dom';
import { leagues } from '../api/endpoints';
import { Card, ErrorNote, InfoNote, Loader, Stat, Status } from '../components/ui';
import { useAction, useLoad } from '../hooks/useLoad';
import { isPast, money, relative, when } from '../lib/format';

/**
 * What somebody sees when they open an invite link (/join/CODE) or type a code in.
 *
 * The league page itself is only for people who are already part of the league, so a newcomer lands here first:
 * they see what the league costs and when it closes, and ask for a place. Once they have asked, the league page
 * opens for them too.
 */
export function JoinLeaguePage() {
  const { code = '' } = useParams();
  const navigate = useNavigate();
  const league = useLoad(() => leagues.byInviteCode(code.trim().toUpperCase()), [code]);
  const { run, busy, error } = useAction();

  return (
    <>
      <div className="page-head">
        <div>
          <Link to="/leagues" className="muted small">
            ← Leagues
          </Link>
          <h1>Join a league</h1>
          <p className="muted small mono">Invite code {code.toUpperCase()}</p>
        </div>
      </div>

      <Loader state={league}>
        {(data) => {
          const alreadyIn = data.myMembershipStatus !== null
            && !['REJECTED', 'CANCELLED'].includes(data.myMembershipStatus);
          const open = data.status === 'OPEN' && !isPast(data.registrationDeadline);
          return (
            <Card
              title={data.name}
              subtitle={`${data.tournamentName} · organized by ${data.organizerDisplayName}`}
              actions={<Status value={data.status} />}
            >
              <div className="grid-stats">
                <Stat
                  label="Buy-in"
                  value={money(data.joiningAmount)}
                  hint={`${money(data.amountPerContest)} × ${data.totalContests} contests, paid up front`}
                />
                <Stat label="Players so far" value={data.confirmedMembers} />
                <Stat
                  label="Joining closes"
                  value={when(data.registrationDeadline)}
                  hint={relative(data.registrationDeadline)}
                />
                <Stat label="Payment due" value={when(data.paymentDeadline)} hint={relative(data.paymentDeadline)} />
              </div>

              <InfoNote>
                You pay the whole buy-in to the organizer up front. Each match uses {money(data.amountPerContest)} of
                it; whoever picks the winner shares that match's pool. When the league ends, the organizer hands back
                whatever your buy-in has turned into.
              </InfoNote>

              <ErrorNote>{error}</ErrorNote>

              <div className="row gap">
                {alreadyIn ? (
                  <Link to={`/leagues/${data.id}`} className="btn btn-primary">
                    Open the league
                  </Link>
                ) : open ? (
                  <button
                    type="button"
                    className="btn btn-primary"
                    disabled={busy}
                    onClick={async () => {
                      const asked = await run(() => leagues.requestToJoin(data.id));
                      if (asked) navigate(`/leagues/${data.id}`);
                    }}
                  >
                    {busy ? 'Asking…' : `Ask to join ${data.name}`}
                  </button>
                ) : (
                  <span className="muted">
                    {data.status === 'UPCOMING'
                      ? `Joining opens ${when(data.registrationStart)}.`
                      : 'Joining is closed for this league.'}
                  </span>
                )}
              </div>
            </Card>
          );
        }}
      </Loader>
    </>
  );
}
