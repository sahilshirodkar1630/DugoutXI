import { useState } from 'react';
import { dashboard, users } from '../api/endpoints';
import type { PeriodStats, StatsRange } from '../api/types';
import { useAuth } from '../auth/useAuth';
import { ChangePasswordForm } from '../components/ChangePasswordForm';
import { Card, Empty, ErrorNote, Field, InfoNote, Loader, Stat, Tabs } from '../components/ui';
import { useAction, useLoad } from '../hooks/useLoad';
import { money, onDate, sign, signedMoney } from '../lib/format';

const RANGES: { id: StatsRange; label: string }[] = [
  { id: 'ONE_MONTH', label: '1 month' },
  { id: 'THREE_MONTHS', label: '3 months' },
  { id: 'SIX_MONTHS', label: '6 months' },
  { id: 'ONE_YEAR', label: '1 year' },
  { id: 'ALL', label: 'All time' },
];

/**
 * The player's own page: their record over a chosen period, their details, their password, and an Excel copy of
 * everything they have played.
 *
 * The UPI id and phone number matter more than they look: they are what an organizer is shown when they owe
 * somebody money. Nobody else can see them — the backend only fills those fields in for the organizer of a league
 * or contest the user is in, and for the user themselves.
 */
export function ProfilePage() {
  const { user, signOut } = useAuth();

  return (
    <>
      <div className="page-head">
        <div>
          <h1>{user?.displayName ?? 'Your profile'}</h1>
          <p className="muted small">{user?.email}</p>
        </div>
        <button type="button" className="btn" onClick={signOut}>
          Sign out
        </button>
      </div>

      <StatsCard />
      <RecordsCard />
      <DetailsCard />
      <PasswordCard />

      <Card title="Account">
        <p className="muted small" style={{ margin: 0 }}>
          Role: {user?.role === 'ADMIN' ? 'Administrator' : 'Player'}. Your email address cannot be changed here.
        </p>
      </Card>
    </>
  );
}

function StatsCard() {
  const [range, setRange] = useState<StatsRange>('ONE_MONTH');
  const stats = useLoad(() => dashboard.periodStats(range), [range]);

  return (
    <Card title="Your record" subtitle="Contests are counted by the date they were played.">
      <Tabs<StatsRange> active={range} onChange={setRange} tabs={RANGES} />
      <Loader state={stats}>{(data) => <StatsBody stats={data} />}</Loader>
    </Card>
  );
}

function StatsBody({ stats }: { stats: PeriodStats }) {
  if (stats.contestsPlayed === 0 && stats.contestsRefunded === 0 && stats.contestsPending === 0) {
    return <Empty>No contests in this period yet.</Empty>;
  }
  const streak =
    stats.currentStreak > 0
      ? `${stats.currentStreak} won in a row`
      : stats.currentStreak < 0
        ? `${-stats.currentStreak} lost in a row`
        : undefined;

  return (
    <>
      <div className="grid-stats">
        <Stat
          label="Profit / loss"
          value={signedMoney(stats.netPl)}
          tone={sign(stats.netPl)}
          hint={`${stats.returnPercentage}% on money put in`}
        />
        <Stat label="Played" value={stats.contestsPlayed} hint={streak} />
        <Stat label="Won" value={stats.contestsWon} hint={`${stats.winPercentage}% of played`} />
        <Stat label="Lost" value={stats.contestsLost} />
        <Stat label="Money put in" value={money(stats.amountInvested)} hint="Entries in decided contests" />
        <Stat label="Money won" value={money(stats.amountWon)} />
        <Stat label="Biggest win" value={signedMoney(stats.biggestWin)} tone={sign(stats.biggestWin)} />
        <Stat label="Best streak" value={stats.bestStreak} hint="Wins in a row" />
        <Stat
          label="Leagues"
          value={stats.leaguesPlayed}
          hint={`${stats.leagueContestsPlayed} matches, ${signedMoney(stats.leagueNetPl)}`}
        />
        <Stat
          label="One-off contests"
          value={stats.oneOffContestsPlayed}
          hint={signedMoney(stats.oneOffNetPl)}
        />
        <Stat label="Refunded" value={stats.contestsRefunded} hint="Not counted as played" />
        <Stat label="Waiting for a result" value={stats.contestsPending} />
      </div>

      {stats.months.length > 1 && (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Month</th>
                <th className="num">Played</th>
                <th className="num">Won</th>
                <th className="num">Put in</th>
                <th className="num">Profit / loss</th>
              </tr>
            </thead>
            <tbody>
              {[...stats.months].reverse().map((m) => (
                <tr key={m.month}>
                  <td>{monthName(m.month)}</td>
                  <td className="num">{m.played}</td>
                  <td className="num">{m.won}</td>
                  <td className="num">{money(m.invested)}</td>
                  <td className={`num tone-${sign(m.netPl)}`}>{signedMoney(m.netPl)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {stats.from && <p className="muted small" style={{ margin: 0 }}>Since {onDate(stats.from)}.</p>}
    </>
  );
}

/** "2026-04" → "April 2026". */
function monthName(month: string): string {
  const [year, number] = month.split('-').map(Number);
  return new Date(year, number - 1, 1).toLocaleDateString('en-IN', { month: 'long', year: 'numeric' });
}

/** An Excel copy of everything, so a player has their own record whatever happens to the app. */
function RecordsCard() {
  const { run, busy, error } = useAction();
  return (
    <Card
      title="Keep your own copy"
      subtitle="Every contest you played, your picks, payouts and league balances, plus the contests you organise, in one Excel file."
    >
      <ErrorNote>{error}</ErrorNote>
      <div className="row gap">
        <button type="button" className="btn" disabled={busy} onClick={() => run(() => users.exportMine())}>
          {busy ? 'Preparing…' : 'Download Excel'}
        </button>
      </div>
    </Card>
  );
}

function DetailsCard() {
  const { user, replaceUser } = useAuth();
  const { run, busy, error } = useAction();
  const [saved, setSaved] = useState(false);
  const [form, setForm] = useState({
    name: user?.name ?? '',
    displayName: user?.displayName ?? '',
    phoneNumber: user?.phoneNumber ?? '',
    upiId: user?.upiId ?? '',
  });

  const set = (key: keyof typeof form) => (event: { target: { value: string } }) => {
    setSaved(false);
    setForm((current) => ({ ...current, [key]: event.target.value }));
  };

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    // A field left out is left alone by the backend. The UPI id is validated against a name@bank shape, so an
    // empty box is sent as "leave it" rather than as an empty string the backend would reject.
    const updated = await run(() =>
      users.updateProfile({
        name: form.name.trim(),
        displayName: form.displayName.trim() || undefined,
        phoneNumber: form.phoneNumber.trim(),
        upiId: form.upiId.trim() || undefined,
      }),
    );
    if (updated) {
      replaceUser(updated);
      setSaved(true);
    }
  };

  return (
    <Card title="Your details">
      <form className="col" onSubmit={submit}>
        <fieldset>
          <Field label="Name">
            <input value={form.name} onChange={set('name')} required />
          </Field>
          <Field label="Display name" hint="How you appear on league tables.">
            <input value={form.displayName} onChange={set('displayName')} />
          </Field>
          <Field label="Phone number" hint="Only the organizer of a league you are in can see this.">
            <input value={form.phoneNumber} onChange={set('phoneNumber')} inputMode="tel" />
          </Field>
          <Field label="UPI id" hint="Where winnings should be sent. Only your organizer sees it.">
            <input value={form.upiId} onChange={set('upiId')} placeholder="you@okbank" />
          </Field>
        </fieldset>

        <ErrorNote>{error}</ErrorNote>
        {saved && <InfoNote>Saved.</InfoNote>}

        <div className="row gap">
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? 'Saving…' : 'Save'}
          </button>
        </div>
      </form>
    </Card>
  );
}

function PasswordCard() {
  const [open, setOpen] = useState(false);
  return (
    <Card title="Password" subtitle="Changing it signs you out on every other device.">
      {open ? (
        <ChangePasswordForm />
      ) : (
        <div className="row gap">
          <button type="button" className="btn" onClick={() => setOpen(true)}>
            Change password
          </button>
        </div>
      )}
    </Card>
  );
}
