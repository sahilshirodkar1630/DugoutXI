import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { auth } from '../api/endpoints';
import { useAuth } from '../auth/useAuth';
import { ThemeToggle } from '../components/ThemeToggle';
import { Card, ErrorNote, Field, InfoNote } from '../components/ui';
import { useAction } from '../hooks/useLoad';

/**
 * Signing in and signing up, on one screen.
 *
 * Registering signs the new user straight in: the backend returns a token with the new account, so asking them to
 * log in again immediately would be busywork. The password rule shown here is the backend's: at least 8
 * characters, and no more than 72, which is where BCrypt stops reading.
 */
export function SignInPage() {
  const { signIn, register } = useAuth();
  const { run, busy, error } = useAction();
  const [params] = useSearchParams();
  const invite = inviteFor(params.get('next'));
  // Somebody arriving from an invite is most likely new, so they start on the form to make an account.
  const [mode, setMode] = useState<'sign-in' | 'register'>(invite ? 'register' : 'sign-in');
  const [form, setForm] = useState({ name: '', displayName: '', email: '', password: '' });

  const set = (key: keyof typeof form) => (event: { target: { value: string } }) =>
    setForm((current) => ({ ...current, [key]: event.target.value }));

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (mode === 'sign-in') {
      await run(() => signIn(form.email.trim(), form.password));
    } else {
      await run(() =>
        register(
          form.name.trim(),
          form.email.trim(),
          form.password,
          form.displayName.trim() === '' ? undefined : form.displayName.trim(),
        ),
      );
    }
  };

  return (
    <div className="auth-shell">
      <div className="auth-corner">
        <ThemeToggle />
      </div>
      <Card
        className="auth-card"
        title={mode === 'sign-in' ? 'Sign in' : 'Create an account'}
        subtitle="Dugout XI: pick the winner of every match with your friends. Money is settled between you, outside the app."
      >
        {invite && (
          <InfoNote>
            You have been invited to {invite}. Create an account, or sign in if you already have one, and you will go
            straight there.
          </InfoNote>
        )}
        <form className="col" onSubmit={submit}>
          {mode === 'register' && (
            <>
              <Field label="Your name">
                <input value={form.name} onChange={set('name')} required autoComplete="name" />
              </Field>
              <Field label="Display name" hint="What your friends see on the league table. Your name is used if you leave this blank.">
                <input value={form.displayName} onChange={set('displayName')} autoComplete="nickname" />
              </Field>
            </>
          )}

          <Field label="Email">
            <input type="email" value={form.email} onChange={set('email')} required autoComplete="email" />
          </Field>

          <Field
            label="Password"
            hint={mode === 'register' ? 'At least 8 characters.' : undefined}
          >
            <input
              type="password"
              value={form.password}
              onChange={set('password')}
              required
              minLength={mode === 'register' ? 8 : undefined}
              maxLength={72}
              autoComplete={mode === 'register' ? 'new-password' : 'current-password'}
            />
          </Field>

          <ErrorNote>{error}</ErrorNote>

          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? 'Just a moment…' : mode === 'sign-in' ? 'Sign in' : 'Create account'}
          </button>
        </form>

        <p className="muted small">
          {mode === 'sign-in' ? "Don't have an account yet? " : 'Already have an account? '}
          <button
            type="button"
            className="btn btn-quiet btn-small"
            onClick={() => setMode(mode === 'sign-in' ? 'register' : 'sign-in')}
          >
            {mode === 'sign-in' ? 'Create one' : 'Sign in'}
          </button>
        </p>

        {mode === 'sign-in' && <ForgotPassword email={form.email} />}
      </Card>
    </div>
  );
}

/**
 * The app sends no email, so a forgotten password goes to the admins: they get an alert, reset it, and send a
 * temporary password back (on WhatsApp, say). The answer here is the same whether or not the email has an account.
 */
function ForgotPassword({ email }: { email: string }) {
  const [open, setOpen] = useState(false);
  const [address, setAddress] = useState(email);
  const [sent, setSent] = useState(false);
  const { run, busy, error } = useAction();

  if (!open) {
    return (
      <button
        type="button"
        className="btn btn-quiet btn-small"
        onClick={() => {
          setAddress(email);
          setOpen(true);
        }}
      >
        Forgot password?
      </button>
    );
  }

  if (sent) {
    return (
      <InfoNote>
        If that email has an account, the admins have been told. They will reset your password and send you a temporary
        one, which you can change after signing in. Ask your league organizer to nudge them if it takes a while.
      </InfoNote>
    );
  }

  return (
    <form
      className="col"
      onSubmit={async (event) => {
        event.preventDefault();
        // The answer has no body, so success is marked explicitly: run() returns null only on failure.
        const done = await run(async () => {
          await auth.forgotPassword(address.trim());
          return true;
        });
        if (done !== null) setSent(true);
      }}
    >
      <Field label="Your email" hint="We will ask an admin to reset your password.">
        <input type="email" value={address} onChange={(e) => setAddress(e.target.value)} required autoComplete="email" />
      </Field>
      <ErrorNote>{error}</ErrorNote>
      <div className="row gap">
        <button type="submit" className="btn" disabled={busy}>
          {busy ? 'Sending…' : 'Ask for a reset'}
        </button>
        <button type="button" className="btn btn-quiet" onClick={() => setOpen(false)}>
          Cancel
        </button>
      </div>
    </form>
  );
}

/** What an invite link points at, in words, or null when the visitor was not following one. */
function inviteFor(next: string | null): string | null {
  if (!next) return null;
  if (next.startsWith('/join/')) return 'a league';
  const contest = /^\/contests\/(\d+)/.exec(next);
  if (contest) return `contest #${contest[1]}`;
  return null;
}
