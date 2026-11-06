import { useState } from 'react';
import { Link } from 'react-router-dom';
import { contests, tournaments } from '../../api/endpoints';
import { leagues } from '../../api/endpoints';
import type { League } from '../../api/types';
import { Card, Empty, ErrorNote, Field, InfoNote, Loader, Matchup, Status } from '../../components/ui';
import { useAction, useLoad } from '../../hooks/useLoad';
import { ImportFixtures } from './ImportFixtures';
import { localInputInDays, money, relative, toIsoInstant, toLocalInput, when } from '../../lib/format';

/**
 * The matches in a league.
 *
 * A league contest takes its tournament, amount and deadlines from the league, so creating one only asks for the
 * two teams and the start time. Every confirmed member is entered automatically — nobody opts in per match, which
 * is what makes the season-long table work.
 */
export function LeagueContestsTab({ league, onChanged }: { league: League; onChanged: () => void }) {
  const list = useLoad(() => leagues.contests(league.id), [league.id]);
  const [creating, setCreating] = useState(false);
  const [importing, setImporting] = useState(false);

  const canCreate =
    league.organizer && ['UPCOMING', 'OPEN', 'ACTIVE'].includes(league.status);
  const remaining = league.totalContests - league.contestsCreated;

  return (
    <>
      <div className="row between gap">
        <p className="muted small" style={{ margin: 0 }}>
          {league.contestsCreated} of {league.totalContests} contests created
          {remaining > 0 ? ` · ${remaining} still to add` : ' · the league is full'}
        </p>
        {canCreate && remaining > 0 && (
          <div className="row gap">
            <button
              type="button"
              className="btn btn-small"
              onClick={() => {
                setImporting((open) => !open);
                setCreating(false);
              }}
            >
              {importing ? 'Close import' : 'Import from spreadsheet'}
            </button>
            <button
              type="button"
              className="btn btn-primary btn-small"
              onClick={() => {
                setCreating((open) => !open);
                setImporting(false);
              }}
            >
              {creating ? 'Close' : 'Add a contest'}
            </button>
          </div>
        )}
      </div>

      {importing && (
        <ImportFixtures
          league={league}
          onImported={() => {
            setImporting(false);
            list.reload();
            onChanged();
          }}
        />
      )}

      {creating && (
        <CreateLeagueContest
          league={league}
          onCreated={() => {
            setCreating(false);
            list.reload();
            onChanged();
          }}
        />
      )}

      <Loader state={list} empty={<Empty>No contests yet. The organizer adds them as the fixtures come up.</Empty>}>
        {(rows) => (
          <Card title="Contests">
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>#</th>
                    <th>Match</th>
                    <th>Starts</th>
                    <th className="num">Entry</th>
                    <th className="num">In</th>
                    <th>Status</th>
                    <th>Your pick</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((contest) => (
                    <tr key={contest.id}>
                      <td className="muted">{contest.contestNumber ?? '—'}</td>
                      <td>
                        <Link to={`/contests/${contest.id}`}>
                          <Matchup
                            teamA={contest.teamA}
                            teamB={contest.teamB}
                            winnerId={contest.winningTeam?.id}
                          />
                        </Link>
                        {contest.name && <div className="muted small">{contest.name}</div>}
                      </td>
                      <td>
                        {when(contest.startTime)}
                        <div className="muted small">{relative(contest.startTime)}</div>
                      </td>
                      <td className="num">{money(contest.contestAmount)}</td>
                      <td className="num">{contest.confirmedParticipants}</td>
                      <td>
                        <Status value={contest.status} />
                      </td>
                      <td>
                        {contest.winningTeam ? (
                          <span className="muted small">Won by {contest.winningTeam.shortCode}</span>
                        ) : (
                          <Link to={`/contests/${contest.id}`} className="small">
                            {contest.status === 'SCHEDULED' ? 'Make a pick' : 'See picks'}
                          </Link>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Card>
        )}
      </Loader>
    </>
  );
}

function CreateLeagueContest({ league, onCreated }: { league: League; onCreated: () => void }) {
  const teams = useLoad(() => tournaments.teams(league.tournamentId), [league.tournamentId]);
  const { run, busy, error } = useAction();
  const [form, setForm] = useState({
    teamAId: '',
    teamBId: '',
    startTime: defaultStart(league.paymentDeadline),
    name: '',
    contestNumber: '',
  });

  const set = (key: keyof typeof form) => (event: { target: { value: string } }) =>
    setForm((current) => ({ ...current, [key]: event.target.value }));

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    const created = await run(() =>
      contests.create({
        leagueId: league.id,
        teamAId: Number(form.teamAId),
        teamBId: Number(form.teamBId),
        startTime: toIsoInstant(form.startTime),
        name: form.name.trim() || undefined,
        contestNumber: form.contestNumber ? Number(form.contestNumber) : undefined,
      }),
    );
    if (created) onCreated();
  };

  const options = (teams.data ?? []).filter((team) => team.active);

  return (
    <Card title="Add a contest" subtitle="Entry, tournament and deadlines all come from the league.">
      <form className="col" onSubmit={submit}>
        <fieldset>
          <Field label="Team A">
            <select value={form.teamAId} onChange={set('teamAId')} required>
              <option value="">Choose…</option>
              {options.map((team) => (
                <option key={team.id} value={team.id}>
                  {team.shortCode} — {team.name}
                </option>
              ))}
            </select>
          </Field>

          <Field label="Team B">
            <select value={form.teamBId} onChange={set('teamBId')} required>
              <option value="">Choose…</option>
              {options
                .filter((team) => String(team.id) !== form.teamAId)
                .map((team) => (
                  <option key={team.id} value={team.id}>
                    {team.shortCode} — {team.name}
                  </option>
                ))}
            </select>
          </Field>

          <Field label="Starts" hint="Picks lock at this moment, to the second.">
            <input type="datetime-local" value={form.startTime} onChange={set('startTime')} required />
          </Field>

          <Field label="Match number" hint="Just for display. The lowest free number is used if you leave it blank.">
            <input
              type="number"
              min={1}
              max={league.totalContests}
              value={form.contestNumber}
              onChange={set('contestNumber')}
            />
          </Field>

          <Field label="Name" hint="Optional, for a qualifier or a final.">
            <input value={form.name} onChange={set('name')} maxLength={100} />
          </Field>
        </fieldset>

        <InfoNote>
          Every confirmed member is entered automatically, at {money(league.amountPerContest)} each.
        </InfoNote>
        <ErrorNote>{error}</ErrorNote>

        <div className="row gap">
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? 'Adding…' : 'Add contest'}
          </button>
        </div>
      </form>
    </Card>
  );
}

/** A day after the payment deadline, or two days from now if that is later. */
function defaultStart(paymentDeadline: string): string {
  const afterPayment = new Date(new Date(paymentDeadline).getTime() + 86_400_000);
  const soonest = new Date(Date.now() + 2 * 86_400_000);
  return afterPayment > soonest ? toLocalInput(afterPayment.toISOString()) : localInputInDays(2);
}
