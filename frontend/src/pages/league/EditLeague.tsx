import { useState } from 'react';
import { leagues } from '../../api/endpoints';
import type { League } from '../../api/types';
import { Card, ErrorNote, Field, InfoNote } from '../../components/ui';
import { useAction } from '../../hooks/useLoad';
import { money, toIsoInstant, toLocalInput } from '../../lib/format';

/**
 * Editing a league while it is still UPCOMING or OPEN: mostly for moving deadlines, which the backend asks for by
 * name ("extend the payment deadline first").
 *
 * The amount and the number of contests are shown but locked once anyone other than the organizer has been
 * accepted, or a contest exists, because members were told what they would pay. The backend enforces the same rule;
 * this only avoids offering an edit that would be refused. The version the organizer loaded travels with the save,
 * so a change made in another tab is not silently overwritten.
 */
export function EditLeague({
  league,
  onSaved,
  onClose,
}: {
  league: League;
  onSaved: () => void;
  onClose: () => void;
}) {
  const { run, busy, error } = useAction();
  const moneyLocked = league.contestsCreated > 0 || league.confirmedMembers > 1;
  const [form, setForm] = useState({
    name: league.name,
    amountPerContest: String(Number(league.amountPerContest)),
    totalContests: String(league.totalContests),
    registrationStart: toLocalInput(league.registrationStart),
    registrationDeadline: toLocalInput(league.registrationDeadline),
    paymentDeadline: toLocalInput(league.paymentDeadline),
    paymentInstructions: league.paymentInstructions ?? '',
  });

  const set = (key: keyof typeof form) => (event: { target: { value: string } }) =>
    setForm((current) => ({ ...current, [key]: event.target.value }));

  const buyIn = Number(form.amountPerContest || 0) * Number(form.totalContests || 0);

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    // Unchanged instants are sent back exactly as the backend gave them, so an untouched field never counts as moved.
    const keep = (local: string, original: string) =>
      local === toLocalInput(original) ? original : toIsoInstant(local);
    const saved = await run(() =>
      leagues.update(league.id, {
        version: league.version,
        name: form.name.trim(),
        tournamentId: league.tournamentId,
        amountPerContest: Number(form.amountPerContest),
        totalContests: Number(form.totalContests),
        registrationStart: keep(form.registrationStart, league.registrationStart),
        registrationDeadline: keep(form.registrationDeadline, league.registrationDeadline),
        paymentDeadline: keep(form.paymentDeadline, league.paymentDeadline),
        paymentInstructions: form.paymentInstructions.trim() || undefined,
      }),
    );
    if (saved) {
      onSaved();
      onClose();
    }
  };

  return (
    <Card title="Edit the league" subtitle="Changes are recorded, and members see the new dates straight away.">
      <form className="col" onSubmit={submit}>
        <fieldset>
          <Field label="Name">
            <input value={form.name} onChange={set('name')} required maxLength={100} />
          </Field>
          <Field
            label="Amount per contest"
            hint={moneyLocked ? 'Locked: members have been accepted or contests created.' : undefined}
          >
            <input
              type="number"
              min={1}
              max={100000}
              value={form.amountPerContest}
              onChange={set('amountPerContest')}
              disabled={moneyLocked}
              required
            />
          </Field>
          <Field label="Number of contests" hint={moneyLocked ? 'Locked for the same reason.' : undefined}>
            <input
              type="number"
              min={1}
              max={1000}
              value={form.totalContests}
              onChange={set('totalContests')}
              disabled={moneyLocked}
              required
            />
          </Field>
          <Field label="Joining opens" hint={league.status === 'OPEN' ? 'Already open, so this cannot move.' : undefined}>
            <input
              type="datetime-local"
              value={form.registrationStart}
              onChange={set('registrationStart')}
              disabled={league.status === 'OPEN'}
              required
            />
          </Field>
          <Field label="Last day to ask to join">
            <input
              type="datetime-local"
              value={form.registrationDeadline}
              onChange={set('registrationDeadline')}
              required
            />
          </Field>
          <Field label="Last day to pay" hint="Must be before the first contest starts.">
            <input type="datetime-local" value={form.paymentDeadline} onChange={set('paymentDeadline')} required />
          </Field>
          <Field label="How to pay you">
            <input value={form.paymentInstructions} onChange={set('paymentInstructions')} maxLength={500} />
          </Field>
        </fieldset>

        {!moneyLocked && (
          <InfoNote>
            Each player pays {money(buyIn)} up front — {money(form.amountPerContest)} × {form.totalContests || 0}{' '}
            contests.
          </InfoNote>
        )}

        <ErrorNote>{error}</ErrorNote>

        <div className="row gap">
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? 'Saving…' : 'Save changes'}
          </button>
          <button type="button" className="btn" onClick={onClose}>
            Cancel
          </button>
        </div>
      </form>
    </Card>
  );
}
