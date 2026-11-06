import type { ReactNode } from 'react';
import { humanise } from '../lib/format';

/** The small pieces every screen is built from. Kept together so the look stays consistent. */

export function Card({
  title,
  subtitle,
  actions,
  children,
  className = '',
}: {
  title?: ReactNode;
  subtitle?: ReactNode;
  actions?: ReactNode;
  children?: ReactNode;
  className?: string;
}) {
  return (
    <section className={`card ${className}`}>
      {(title || actions) && (
        <header className="card-head">
          <div>
            {title && <h2>{title}</h2>}
            {subtitle && <p className="muted small">{subtitle}</p>}
          </div>
          {actions && <div className="row gap">{actions}</div>}
        </header>
      )}
      {children}
    </section>
  );
}

/**
 * A status word, coloured by what it means rather than by its exact spelling, so the same green means "done"
 * whether it says CONFIRMED, PAID_OUT or SETTLED.
 */
export function Status({ value }: { value: string | null | undefined }) {
  if (!value) return <span className="muted">—</span>;
  return <span className={`badge badge-${tone(value)}`}>{humanise(value)}</span>;
}

function tone(value: string): 'good' | 'bad' | 'warn' | 'info' | 'muted' {
  switch (value) {
    case 'CONFIRMED':
    case 'PAID':
    case 'PAID_OUT':
    case 'SETTLED':
    case 'COMPLETED':
    case 'WON':
    case 'ACTIVE':
      return 'good';
    case 'REJECTED':
    case 'REMOVED':
    case 'CANCELLED':
    case 'LOST':
    case 'VOID':
    case 'WRITTEN_OFF':
      return 'bad';
    case 'PENDING':
    case 'PENDING_APPROVAL':
    case 'ACCEPTED_UNPAID':
    case 'PENDING_SETTLEMENT':
      return 'warn';
    case 'LOCKED':
    case 'RESULT_DECLARED':
    case 'OPEN':
    case 'SCHEDULED':
      return 'info';
    default:
      return 'muted';
  }
}

export function Empty({ children }: { children: ReactNode }) {
  return <p className="empty">{children}</p>;
}

export function ErrorNote({ children }: { children: ReactNode }) {
  if (!children) return null;
  return (
    <p className="note note-error" role="alert">
      {children}
    </p>
  );
}

export function InfoNote({ children }: { children: ReactNode }) {
  if (!children) return null;
  return <p className="note note-info">{children}</p>;
}

export function Spinner({ label = 'Loading' }: { label?: string }) {
  return (
    <p className="muted" role="status">
      {label}…
    </p>
  );
}

/** Everything a loaded panel needs: the spinner, the error, the empty case, or the content. */
export function Loader<T>({
  state,
  empty,
  children,
}: {
  state: { data: T | null; loading: boolean; error: string | null };
  empty?: ReactNode;
  children: (data: T) => ReactNode;
}) {
  if (state.loading && state.data === null) return <Spinner />;
  if (state.error && state.data === null) return <ErrorNote>{state.error}</ErrorNote>;
  if (state.data === null) return <>{empty ?? <Empty>Nothing here yet.</Empty>}</>;
  if (Array.isArray(state.data) && state.data.length === 0) {
    return <>{empty ?? <Empty>Nothing here yet.</Empty>}</>;
  }
  return (
    <>
      {state.error && <ErrorNote>{state.error}</ErrorNote>}
      {children(state.data)}
    </>
  );
}

export function Field({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: ReactNode;
  children: ReactNode;
}) {
  return (
    <label className="field">
      <span className="field-label">{label}</span>
      {children}
      {hint && <span className="field-hint">{hint}</span>}
    </label>
  );
}

export function Stat({
  label,
  value,
  tone: valueTone,
  hint,
}: {
  label: string;
  value: ReactNode;
  tone?: 'up' | 'down' | 'flat';
  hint?: ReactNode;
}) {
  return (
    <div className="stat">
      <span className="stat-label">{label}</span>
      <strong className={`stat-value ${valueTone ? `tone-${valueTone}` : ''}`}>{value}</strong>
      {hint && <span className="stat-hint">{hint}</span>}
    </div>
  );
}

export function Tabs<T extends string>({
  tabs,
  active,
  onChange,
}: {
  tabs: { id: T; label: string; badge?: number }[];
  active: T;
  onChange: (id: T) => void;
}) {
  return (
    <div className="tabs" role="tablist">
      {tabs.map((tab) => (
        <button
          key={tab.id}
          type="button"
          role="tab"
          aria-selected={tab.id === active}
          className={tab.id === active ? 'tab tab-active' : 'tab'}
          onClick={() => onChange(tab.id)}
        >
          {tab.label}
          {tab.badge !== undefined && tab.badge > 0 && <span className="tab-badge">{tab.badge}</span>}
        </button>
      ))}
    </div>
  );
}

/** The two teams of a contest, with the winner marked once there is one. */
export function Matchup({
  teamA,
  teamB,
  winnerId,
}: {
  teamA: { id: number; shortCode: string };
  teamB: { id: number; shortCode: string };
  winnerId?: number | null;
}) {
  return (
    <span className="matchup">
      <span className={winnerId === teamA.id ? 'team team-won' : 'team'}>{teamA.shortCode}</span>
      <span className="muted small">v</span>
      <span className={winnerId === teamB.id ? 'team team-won' : 'team'}>{teamB.shortCode}</span>
    </span>
  );
}
