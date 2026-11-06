import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { contests, tournaments } from '../api/endpoints';
import { Card, Empty, ErrorNote, Field, InfoNote, Loader, Matchup, Status } from '../components/ui';
import { useAction, useLoad } from '../hooks/useLoad';
import { localInputInDays, money, relative, toIsoInstant, when } from '../lib/format';

/**
 * One-off contests: a single match with its own entry amount, outside any league.
 *
 * These settle on their own as soon as the result is in, through payouts, rather than waiting for a season to
 * finish. Anyone with the link can ask to join, so the only way in is for the organizer to share the contest.
 */
export function ContestsPage() {
  const mine = useLoad(() => contests.mine(), []);
  const [creating, setCreating] = useState(false);

  return (
    <>
      <div className="page-head">
        <div>
          <h1>One-off contests</h1>
          <p className="muted small">A single match, settled as soon as the result is in.</p>
        </div>
        <button type="button" className="btn btn-primary" onClick={() => setCreating((open) => !open)}>
          {creating ? 'Close' : 'Create a contest'}
        </button>
      </div>

      {creating && <CreateContest onCreated={() => setCreating(false)} />}

      <JoinByLink />

      <Loader
        state={mine}
        empty={<Empty>You have no one-off contests. Create one, or open a link somebody shared with you.</Empty>}
      >
        {(rows) => (
          <Card title="Your contests">
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Match</th>
                    <th>Starts</th>
                    <th className="num">Entry</th>
                    <th className="num">In</th>
                    <th>Status</th>
                    <th>You</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((contest) => (
                    <tr key={contest.id}>
                      <td>
                        <Link to={`/contests/${contest.id}`}>
                          <Matchup teamA={contest.teamA} teamB={contest.teamB} winnerId={contest.winningTeam?.id} />
                        </Link>
                        <div className="muted small">
                          #{contest.id}
                          {contest.name ? ` · ${contest.name}` : ''}
                        </div>
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
                        <Status value={contest.myParticipantStatus} />
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

/**
 * Creating a one-off contest.
 *
 * Unlike a league contest this one carries its own money and deadlines, so all of it is asked for here. The
 * organizer is entered automatically and counts as paid, because they are the one holding the pot.
 */
function CreateContest({ onCreated }: { onCreated: () => void }) {
  const navigate = useNavigate();
  const tournamentList = useLoad(() => tournaments.list(), []);
  const { run, busy, error } = useAction();
  const [form, setForm] = useState({
    tournamentId: '',
    teamAId: '',
    teamBId: '',
    startTime: localInputInDays(2),
    amount: '100',
    registrationDeadline: localInputInDays(1),
    paymentDeadline: localInputInDays(1, 22),
    paymentInstructions: '',
    name: '',
  });

  const teams = useLoad(
    () => tournaments.teams(Number(form.tournamentId)),
    [form.tournamentId],
    { skip: form.tournamentId === '' },
  );

  const set = (key: keyof typeof form) => (event: { target: { value: string } }) =>
    setForm((current) => ({ ...current, [key]: event.target.value }));

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    const created = await run(() =>
      contests.create({
        tournamentId: Number(form.tournamentId),
        teamAId: Number(form.teamAId),
        teamBId: Number(form.teamBId),
        startTime: toIsoInstant(form.startTime),
        amount: Number(form.amount),
        registrationDeadline: toIsoInstant(form.registrationDeadline),
        paymentDeadline: toIsoInstant(form.paymentDeadline),
        paymentInstructions: form.paymentInstructions.trim() || undefined,
        name: form.name.trim() || undefined,
      }),
    );
    if (created) {
      onCreated();
      navigate(`/contests/${created.id}`);
    }
  };

  const options = (teams.data ?? []).filter((team) => team.active);

  return (
    <Card title="Create a one-off contest" subtitle="You organize it, and you play in it too.">
      <form className="col" onSubmit={submit}>
        <fieldset>
          <Field label="Tournament">
            <select value={form.tournamentId} onChange={set('tournamentId')} required>
              <option value="">Choose a tournament…</option>
              {(tournamentList.data ?? [])
                .filter((tournament) => tournament.active)
                .map((tournament) => (
                  <option key={tournament.id} value={tournament.id}>
                    {tournament.name}
                  </option>
                ))}
            </select>
          </Field>

          <Field label="Entry amount" hint="What each player puts in.">
            <input type="number" min={1} max={100000} value={form.amount} onChange={set('amount')} required />
          </Field>

          <Field label="Team A">
            <select value={form.teamAId} onChange={set('teamAId')} required disabled={options.length === 0}>
              <option value="">Choose…</option>
              {options.map((team) => (
                <option key={team.id} value={team.id}>
                  {team.shortCode} — {team.name}
                </option>
              ))}
            </select>
          </Field>

          <Field label="Team B">
            <select value={form.teamBId} onChange={set('teamBId')} required disabled={options.length === 0}>
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

          <Field label="Starts" hint="Picks lock at this moment.">
            <input type="datetime-local" value={form.startTime} onChange={set('startTime')} required />
          </Field>

          <Field label="Last time to ask to join">
            <input
              type="datetime-local"
              value={form.registrationDeadline}
              onChange={set('registrationDeadline')}
              required
            />
          </Field>

          <Field label="Last time to pay" hint="Anyone unpaid after this is taken out before the contest locks.">
            <input type="datetime-local" value={form.paymentDeadline} onChange={set('paymentDeadline')} required />
          </Field>

          <Field label="How to pay you">
            <input
              value={form.paymentInstructions}
              onChange={set('paymentInstructions')}
              maxLength={500}
              placeholder="UPI to me@okbank"
            />
          </Field>

          <Field label="Name" hint="Optional.">
            <input value={form.name} onChange={set('name')} maxLength={100} />
          </Field>
        </fieldset>

        <InfoNote>
          The pool is {money(form.amount)} times the number of players. Whoever picks the winner shares it equally;
          if nobody does, or everybody does, every entry is refunded.
        </InfoNote>
        <ErrorNote>{error}</ErrorNote>

        <div className="row gap">
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? 'Creating…' : 'Create contest'}
          </button>
        </div>
      </form>
    </Card>
  );
}

/** There is no public directory of contests, so joining one means opening it by its number. */
function JoinByLink() {
  const navigate = useNavigate();
  const [id, setId] = useState('');

  return (
    <Card title="Somebody shared a contest with you?" subtitle="Open it by its number, then ask for a place.">
      <form
        className="row gap"
        onSubmit={(event) => {
          event.preventDefault();
          if (id.trim() !== '') navigate(`/contests/${id.trim()}`);
        }}
      >
        <input
          value={id}
          onChange={(event) => setId(event.target.value)}
          inputMode="numeric"
          placeholder="Contest number"
          style={{ maxWidth: 200 }}
          required
        />
        <button type="submit" className="btn">
          Open contest
        </button>
      </form>
    </Card>
  );
}
