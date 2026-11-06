import { useState } from 'react';
import { dashboard, users } from '../api/endpoints';
import type { PeriodStats, StatsBreakdown, StatsRange } from '../api/types';
import { useAuth } from '../auth/useAuth';
import { ChangePasswordForm } from '../components/ChangePasswordForm';
import { Card, Empty, ErrorNote, Field, InfoNote, Loader } from '../components/ui';
import { useAction, useLoad } from '../hooks/useLoad';
import { money, onDate, sign, signedMoney } from '../lib/format';

const RANGES: { id: StatsRange; short: string; long: string }[] = [
  { id: 'ONE_MONTH', short: '1M', long: 'the last month' },
  { id: 'THREE_MONTHS', short: '3M', long: 'the last 3 months' },
  { id: 'SIX_MONTHS', short: '6M', long: 'the last 6 months' },
  { id: 'ONE_YEAR', short: '1Y', long: 'the last year' },
  { id: 'ALL', short: 'All', long: 'all time' },
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
        <button type="button" className="btn btn-small" onClick={signOut}>
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
  const [range, setRange] = useState<StatsRange>('ALL');
  const stats = useLoad(() => dashboard.periodStats(range), [range]);

  return (
    <Card
      title="Your record"
      actions={
        <div className="segmented" role="radiogroup" aria-label="Period">
          {RANGES.map((r) => (
            <button
              key={r.id}
              type="button"
              role="radio"
              aria-checked={range === r.id}
              aria-label={r.long}
              className={range === r.id ? 'segmented-on' : undefined}
              onClick={() => setRange(r.id)}
            >
              {r.short}
            </button>
          ))}
        </div>
      }
    >
      <Loader state={stats}>
        {(data) => <StatsBody stats={data} period={RANGES.find((r) => r.id === range)?.long ?? ''} />}
      </Loader>
    </Card>
  );
}

/**
 * The record, most important first: one big profit or loss figure, a bar of wins against losses, then three short
 * rows (how you play, the money, where you play). Contests count by the date they were played.
 */
function StatsBody({ stats, period }: { stats: PeriodStats; period: string }) {
  if (stats.contestsPlayed === 0 && stats.contestsRefunded === 0 && stats.contestsPending === 0) {
    return <Empty>No contests in {period} yet.</Empty>;
  }
  const decided = stats.contestsWon + stats.contestsLost + stats.contestsRefunded;
  const share = (n: number) => (decided === 0 ? 0 : (n * 100) / decided);
  const streak =
    stats.currentStreak > 0
      ? `On a run: ${stats.currentStreak} won in a row`
      : stats.currentStreak < 0
        ? `${-stats.currentStreak} lost in a row`
        : null;

  return (
    <div className="record">
      <div className="record-hero">
        <span className="record-label">Profit / loss over {period}</span>
        <strong className={`record-total tone-${sign(stats.netPl)}`}>{signedMoney(stats.netPl)}</strong>
        <span className="muted small">
          {stats.contestsPlayed > 0
            ? `${stats.returnPercentage}% on ${money(stats.amountInvested)} put in`
            : 'Nothing decided yet'}
        </span>
      </div>

      {decided > 0 && (
        <div className="record-bar-wrap">
          <div className="record-bar" aria-hidden="true">
            <span className="record-bar-won" style={{ width: `${share(stats.contestsWon)}%` }} />
            <span className="record-bar-lost" style={{ width: `${share(stats.contestsLost)}%` }} />
            <span className="record-bar-refunded" style={{ width: `${share(stats.contestsRefunded)}%` }} />
          </div>
          <div className="record-legend small">
            <span>
              <i className="dot-won" /> {stats.contestsWon} won
            </span>
            <span>
              <i className="dot-lost" /> {stats.contestsLost} lost
            </span>
            {stats.contestsRefunded > 0 && (
              <span>
                <i className="dot-refunded" /> {stats.contestsRefunded} refunded
              </span>
            )}
          </div>
        </div>
      )}

      {streak && <p className={`record-streak tone-${stats.currentStreak > 0 ? 'up' : 'down'}`}>{streak}</p>}

      <dl className="figures">
        <Figure label="Played" value={stats.contestsPlayed} />
        <Figure label="Win rate" value={`${stats.winPercentage}%`} />
        <Figure label="Best streak" value={stats.bestStreak} />
      </dl>

      <dl className="figures">
        <Figure label="Put in" value={money(stats.amountInvested)} />
        <Figure label="Won back" value={money(stats.amountWon)} />
        <Figure label="Biggest win" value={signedMoney(stats.biggestWin)} tone={sign(stats.biggestWin)} />
      </dl>

      <KindBlock
        title="Leagues"
        kind={stats.leagues}
        note={
          stats.leaguesPlayed > 0
            ? `In ${stats.leaguesPlayed} league${stats.leaguesPlayed === 1 ? '' : 's'}. Paid up front and settled when each league ends.`
            : undefined
        }
        empty="No league matches in this period."
      />
      <KindBlock
        title="One-off contests"
        kind={stats.oneOff}
        note="Each one settles as soon as its result is in."
        empty="No one-off contests in this period."
      />

      {stats.months.length > 1 && (
        <div className="table-wrap keep-table">
          <table>
            <thead>
              <tr>
                <th>Month</th>
                <th className="num">Played</th>
                <th className="num">Won</th>
                <th className="num hide-phone">Put in</th>
                <th className="num">Profit / loss</th>
              </tr>
            </thead>
            <tbody>
              {[...stats.months].reverse().map((m) => (
                <tr key={m.month}>
                  <td>{monthName(m.month)}</td>
                  <td className="num">{m.played}</td>
                  <td className="num">{m.won}</td>
                  <td className="num hide-phone">{money(m.invested)}</td>
                  <td className={`num tone-${sign(m.netPl)}`}>{signedMoney(m.netPl)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {stats.from && <p className="muted small" style={{ margin: 0 }}>Since {onDate(stats.from)}.</p>}
    </div>
  );
}

/** One kind of game on its own: its profit or loss as the heading, then how it went. */
function KindBlock({
  title,
  kind,
  note,
  empty,
}: {
  title: string;
  kind: StatsBreakdown;
  note?: string;
  empty: string;
}) {
  const nothing = kind.played === 0 && kind.pending === 0 && kind.refunded === 0;
  return (
    <section className="kind">
      <div className="kind-head">
        <h3>{title}</h3>
        {!nothing && <strong className={`tone-${sign(kind.netPl)}`}>{signedMoney(kind.netPl)}</strong>}
      </div>
      {nothing ? (
        <p className="muted small" style={{ margin: 0 }}>
          {empty}
        </p>
      ) : (
        <>
          <dl className="figures figures-tight">
            <Figure label="Played" value={kind.played} />
            <Figure label="Won" value={kind.won} hint={kind.played > 0 ? `${kind.winPercentage}%` : undefined} />
            <Figure label="Lost" value={kind.lost} />
          </dl>
          {kind.played > 0 && (
            <p className="kind-money small">
              <span>
                Put in <strong>{money(kind.invested)}</strong>
              </span>
              <span>
                Won back <strong>{money(kind.amountWon)}</strong>
              </span>
            </p>
          )}
          <p className="muted small" style={{ margin: 0 }}>
            {[
              kind.pending > 0 ? `${kind.pending} waiting for a result` : null,
              kind.refunded > 0 ? `${kind.refunded} refunded` : null,
              note ?? null,
            ]
              .filter(Boolean)
              .join(' · ')}
          </p>
        </>
      )}
    </section>
  );
}

function Figure({
  label,
  value,
  hint,
  tone,
}: {
  label: string;
  value: React.ReactNode;
  hint?: string;
  tone?: 'up' | 'down' | 'flat';
}) {
  return (
    <div className="figure">
      <dt>{label}</dt>
      <dd className={tone ? `tone-${tone}` : undefined}>{value}</dd>
      {hint && <dd className="figure-hint">{hint}</dd>}
    </div>
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
