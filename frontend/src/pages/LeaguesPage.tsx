import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { leagues, tournaments } from '../api/endpoints';
import { Card, Empty, ErrorNote, Field, InfoNote, Loader, Status } from '../components/ui';
import { useAction, useLoad } from '../hooks/useLoad';
import { localInputInDays, money, toIsoInstant } from '../lib/format';

/**
 * Every league the user organizes or plays in, plus the two ways to get into a new one: start your own, or join
 * somebody's with their invite code.
 */
export function LeaguesPage() {
  const mine = useLoad(() => leagues.mine(), []);
  const [creating, setCreating] = useState(false);

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Leagues</h1>
          <p className="muted small">A season-long table with your friends.</p>
        </div>
        <button type="button" className="btn btn-primary" onClick={() => setCreating((open) => !open)}>
          {creating ? 'Close' : 'Start a league'}
        </button>
      </div>

      {creating && <CreateLeague onCreated={() => setCreating(false)} />}

      <JoinByInvite />

      <Loader
        state={mine}
        empty={<Empty>You are not in any league yet. Start one, or paste a friend's invite code above.</Empty>}
      >
        {(list) => (
          <div className="grid">
            {list.map((league) => (
              <Link key={league.id} to={`/leagues/${league.id}`} className="card card-link">
                <div className="row between gap">
                  <h2>{league.name}</h2>
                  <Status value={league.status} />
                </div>
                <p className="muted small" style={{ margin: 0 }}>
                  {league.tournamentName} · {league.totalContests} contests · {money(league.amountPerContest)} each
                </p>
                <div className="row between gap small">
                  <span className="muted">
                    {league.confirmedMembers} player{league.confirmedMembers === 1 ? '' : 's'}
                  </span>
                  <span className="row gap">
                    {league.organizer && <span className="badge badge-info">Organizer</span>}
                    {league.myMembershipStatus && league.myMembershipStatus !== 'CONFIRMED' && <Status value={league.myMembershipStatus} />}
                  </span>
                </div>
                <span className="muted small">Buy-in {money(league.joiningAmount)}</span>
              </Link>
            ))}
          </div>
        )}
      </Loader>
    </>
  );
}

/**
 * Creating a league.
 *
 * The buy-in is worth spelling out as the organizer types, because it is the number that surprises people: the
 * whole league is paid up front, so it is the per-contest amount times the number of contests.
 */
function CreateLeague({ onCreated }: { onCreated: () => void }) {
  const navigate = useNavigate();
  const tournamentList = useLoad(() => tournaments.list(), []);
  const { run, busy, error } = useAction();
  const [form, setForm] = useState({
    name: '',
    tournamentId: '',
    amountPerContest: '100',
    totalContests: '10',
    registrationStart: localInputInDays(0, 20),
    registrationDeadline: localInputInDays(5),
    paymentDeadline: localInputInDays(7),
    paymentInstructions: '',
  });

  const set = (key: keyof typeof form) => (event: { target: { value: string } }) =>
    setForm((current) => ({ ...current, [key]: event.target.value }));

  const buyIn = Number(form.amountPerContest || 0) * Number(form.totalContests || 0);

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    const created = await run(() =>
      leagues.create({
        name: form.name.trim(),
        tournamentId: Number(form.tournamentId),
        amountPerContest: Number(form.amountPerContest),
        totalContests: Number(form.totalContests),
        registrationStart: toIsoInstant(form.registrationStart),
        registrationDeadline: toIsoInstant(form.registrationDeadline),
        paymentDeadline: toIsoInstant(form.paymentDeadline),
        paymentInstructions: form.paymentInstructions.trim() || undefined,
      }),
    );
    if (created) {
      onCreated();
      navigate(`/leagues/${created.id}`);
    }
  };

  return (
    <Card title="Start a league" subtitle="You will be its organizer, and you play in it too.">
      <form className="col" onSubmit={submit}>
        <fieldset>
          <Field label="Name">
            <input value={form.name} onChange={set('name')} required maxLength={100} placeholder="Office IPL 2026" />
          </Field>

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

          <Field label="Amount per contest" hint="What one match costs each player.">
            <input
              type="number"
              min={1}
              max={100000}
              step={1}
              value={form.amountPerContest}
              onChange={set('amountPerContest')}
              required
            />
          </Field>

          <Field label="Number of contests" hint="How many matches the league will cover.">
            <input
              type="number"
              min={1}
              max={200}
              value={form.totalContests}
              onChange={set('totalContests')}
              required
            />
          </Field>

          <Field label="Joining opens" hint="Before this, the league is not visible to join.">
            <input type="datetime-local" value={form.registrationStart} onChange={set('registrationStart')} required />
          </Field>

          <Field label="Last day to ask to join">
            <input
              type="datetime-local"
              value={form.registrationDeadline}
              onChange={set('registrationDeadline')}
              required
            />
          </Field>

          <Field label="Last day to pay" hint="Anyone unpaid after this is taken out automatically.">
            <input type="datetime-local" value={form.paymentDeadline} onChange={set('paymentDeadline')} required />
          </Field>

          <Field label="How to pay you" hint="Shown to members once you accept them.">
            <input
              value={form.paymentInstructions}
              onChange={set('paymentInstructions')}
              maxLength={500}
              placeholder="UPI to me@okbank"
            />
          </Field>
        </fieldset>

        <InfoNote>
          Each player pays {money(buyIn)} up front — {money(form.amountPerContest)} × {form.totalContests || 0}{' '}
          contests. Nothing is collected by the app; you take the money yourself and mark people as paid.
        </InfoNote>

        <ErrorNote>{error}</ErrorNote>

        <div className="row gap">
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? 'Creating…' : 'Create league'}
          </button>
        </div>
      </form>
    </Card>
  );
}

/** Joining with an invite code: the join page shows the league first, so the user can see what they are joining. */
function JoinByInvite() {
  const navigate = useNavigate();
  const [code, setCode] = useState('');

  const open = (event: React.FormEvent) => {
    event.preventDefault();
    const cleaned = code.trim().toUpperCase();
    if (cleaned) navigate(`/join/${encodeURIComponent(cleaned)}`);
  };

  return (
    <Card title="Have an invite code?" subtitle="Open the league, then ask the organizer for a place.">
      <form className="row gap" onSubmit={open}>
        <input
          value={code}
          onChange={(event) => setCode(event.target.value)}
          placeholder="ABCD1234"
          className="mono"
          style={{ maxWidth: 220 }}
          required
        />
        <button type="submit" className="btn" disabled={code.trim() === ''}>
          Open league
        </button>
      </form>
    </Card>
  );
}
