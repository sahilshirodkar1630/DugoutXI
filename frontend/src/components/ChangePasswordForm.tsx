import { useState } from 'react';
import { useAuth } from '../auth/useAuth';
import { ErrorNote, Field, InfoNote } from './ui';
import { useAction } from '../hooks/useLoad';

/**
 * Change your password. Used on the profile page, and on its own straight after signing in with a temporary password
 * an admin gave out, where it is the only thing the app shows until it is done.
 *
 * The backend signs every other session out when the password changes; this one carries on with the new token.
 */
export function ChangePasswordForm({ temporary = false, onDone }: { temporary?: boolean; onDone?: () => void }) {
  const { changePassword } = useAuth();
  const { run, busy, error } = useAction();
  const [form, setForm] = useState({ current: '', next: '', again: '' });
  const [mismatch, setMismatch] = useState(false);
  const [done, setDone] = useState(false);

  const set = (key: keyof typeof form) => (event: { target: { value: string } }) => {
    setForm((current) => ({ ...current, [key]: event.target.value }));
    setMismatch(false);
    setDone(false);
  };

  return (
    <form
      className="col"
      onSubmit={async (event) => {
        event.preventDefault();
        if (form.next !== form.again) {
          setMismatch(true);
          return;
        }
        const changed = await run(() => changePassword(form.current, form.next));
        if (changed !== null) {
          setForm({ current: '', next: '', again: '' });
          setDone(true);
          onDone?.();
        }
      }}
    >
      <Field label={temporary ? 'Temporary password' : 'Current password'}>
        <input
          type="password"
          value={form.current}
          onChange={set('current')}
          required
          autoComplete="current-password"
        />
      </Field>
      <Field label="New password" hint="At least 8 characters.">
        <input
          type="password"
          value={form.next}
          onChange={set('next')}
          required
          minLength={8}
          maxLength={72}
          autoComplete="new-password"
        />
      </Field>
      <Field label="New password again">
        <input
          type="password"
          value={form.again}
          onChange={set('again')}
          required
          minLength={8}
          maxLength={72}
          autoComplete="new-password"
        />
      </Field>

      {mismatch && <ErrorNote>The two new passwords do not match.</ErrorNote>}
      <ErrorNote>{error}</ErrorNote>
      {done && !temporary && <InfoNote>Password changed. Any other device you were signed in on is now signed out.</InfoNote>}

      <button type="submit" className="btn btn-primary" disabled={busy}>
        {busy ? 'Saving…' : temporary ? 'Set my password' : 'Change password'}
      </button>
    </form>
  );
}
