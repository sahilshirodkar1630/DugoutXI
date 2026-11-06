/**
 * Formatting helpers.
 *
 * Money arrives from the backend as a decimal string, never a number: the backend works in BigDecimal, and
 * parsing a rupee figure into a JavaScript float is how rounding errors get into a ledger. These helpers format
 * the string for display and only ever parse one for comparison, never for arithmetic that is shown to the user.
 */

const rupees = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

export function money(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === '') {
    return '—';
  }
  const asNumber = typeof value === 'number' ? value : Number(value);
  if (Number.isNaN(asNumber)) {
    return String(value);
  }
  return rupees.format(asNumber);
}

/** Money with an explicit sign, for a profit or loss where the direction is the point. */
export function signedMoney(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === '') {
    return '—';
  }
  const asNumber = typeof value === 'number' ? value : Number(value);
  if (Number.isNaN(asNumber)) {
    return String(value);
  }
  const formatted = rupees.format(Math.abs(asNumber));
  if (asNumber > 0) return `+${formatted}`;
  if (asNumber < 0) return `−${formatted}`;
  return formatted;
}

export function sign(value: string | number | null | undefined): 'up' | 'down' | 'flat' {
  const asNumber = typeof value === 'number' ? value : Number(value ?? 0);
  if (Number.isNaN(asNumber) || asNumber === 0) return 'flat';
  return asNumber > 0 ? 'up' : 'down';
}

const dateTime = new Intl.DateTimeFormat('en-IN', {
  day: 'numeric',
  month: 'short',
  hour: 'numeric',
  minute: '2-digit',
});

const dateOnly = new Intl.DateTimeFormat('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });

export function when(iso: string | null | undefined): string {
  if (!iso) return '—';
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? '—' : dateTime.format(date);
}

export function onDate(iso: string | null | undefined): string {
  if (!iso) return '—';
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? '—' : dateOnly.format(date);
}

/** "in 2 days", "in 3 hours", "4 minutes ago". Used for deadlines and start times. */
export function relative(iso: string | null | undefined): string {
  if (!iso) return '';
  const target = new Date(iso).getTime();
  if (Number.isNaN(target)) return '';
  const diffSeconds = Math.round((target - Date.now()) / 1000);
  const absolute = Math.abs(diffSeconds);

  const units: [Intl.RelativeTimeFormatUnit, number][] = [
    ['minute', 60],
    ['hour', 3600],
    ['day', 86400],
    ['week', 604800],
  ];
  const formatter = new Intl.RelativeTimeFormat('en', { numeric: 'auto' });

  if (absolute < 60) return formatter.format(diffSeconds, 'second');
  for (let i = units.length - 1; i >= 0; i -= 1) {
    const [unit, seconds] = units[i];
    if (absolute >= seconds) {
      return formatter.format(Math.round(diffSeconds / seconds), unit);
    }
  }
  return formatter.format(Math.round(diffSeconds / 60), 'minute');
}

export function isPast(iso: string | null | undefined): boolean {
  if (!iso) return false;
  const time = new Date(iso).getTime();
  return !Number.isNaN(time) && time <= Date.now();
}

/** Turns SOMETHING_LIKE_THIS into "Something like this" for statuses and notification types. */
export function humanise(value: string | null | undefined): string {
  if (!value) return '—';
  const words = value.replace(/_/g, ' ').toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

/**
 * The value a datetime-local input wants: the local time with no zone, which is also what the user means when
 * they type a start time. Sending it on needs toIsoInstant.
 */
export function toLocalInput(iso?: string | null): string {
  const date = iso ? new Date(iso) : new Date();
  if (Number.isNaN(date.getTime())) return '';
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(
    date.getMinutes(),
  )}`;
}

/** The backend wants an instant, so a local datetime-local value is converted through the browser's own zone. */
export function toIsoInstant(localValue: string): string {
  return new Date(localValue).toISOString();
}

/** A datetime-local value a few days out, for sensible form defaults. */
export function localInputInDays(days: number, atHour = 19): string {
  const date = new Date();
  date.setDate(date.getDate() + days);
  date.setHours(atHour, 30, 0, 0);
  return toLocalInput(date.toISOString());
}
