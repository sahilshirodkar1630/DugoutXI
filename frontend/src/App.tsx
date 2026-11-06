import { useEffect, useState } from 'react';
import { Navigate, Route, Routes, useLocation, useSearchParams } from 'react-router-dom';
import { Layout } from './components/Layout';
import { ChangePasswordForm } from './components/ChangePasswordForm';
import { Card, Spinner } from './components/ui';
import { consumeSignedOut } from './auth/authState';
import { useAuth } from './auth/useAuth';
import { AboutPage } from './pages/AboutPage';
import { AdminPage } from './pages/AdminPage';
import { ContestPage } from './pages/ContestPage';
import { ContestsPage } from './pages/ContestsPage';
import { HomePage } from './pages/HomePage';
import { JoinLeaguePage } from './pages/JoinLeaguePage';
import { LeaguePage } from './pages/LeaguePage';
import { LeaguesPage } from './pages/LeaguesPage';
import { NotificationsPage } from './pages/NotificationsPage';
import { ProfilePage } from './pages/ProfilePage';
import { SignInPage } from './pages/SignInPage';

/**
 * The routes.
 *
 * Nothing is decided until the stored token has been checked, otherwise a refresh on a deep link would bounce the
 * user to the sign-in page for a moment before letting them back in. The admin route is guarded in the backend
 * too, so hiding it here is a convenience rather than the actual protection.
 */
export default function App() {
  const { user, loading } = useAuth();

  if (loading) {
    return (
      <div className="auth-shell">
        <div className="col" style={{ alignItems: 'center', maxWidth: 360, textAlign: 'center' }}>
          <Spinner label="Signing you in" />
          <WakingHint />
        </div>
      </div>
    );
  }

  if (!user) {
    return (
      <Routes>
        <Route path="/sign-in" element={<SignInPage />} />
        <Route path="/about" element={<AboutPage standalone />} />
        <Route path="*" element={<ToSignIn />} />
      </Routes>
    );
  }

  // Signed in with a temporary password from an admin: nothing else until they choose their own.
  if (user.mustChangePassword) {
    return <ChooseOwnPassword />;
  }

  return (
    <Routes>
      <Route path="/sign-in" element={<BackFromSignIn />} />
      <Route element={<Layout />}>
        <Route path="/" element={<HomePage />} />
        <Route path="/leagues" element={<LeaguesPage />} />
        <Route path="/leagues/:leagueId" element={<LeaguePage />} />
        <Route path="/join/:code" element={<JoinLeaguePage />} />
        <Route path="/contests" element={<ContestsPage />} />
        <Route path="/contests/:contestId" element={<ContestPage />} />
        <Route path="/notifications" element={<NotificationsPage />} />
        <Route path="/profile" element={<ProfilePage />} />
        <Route path="/about" element={<AboutPage />} />
        <Route path="/admin" element={user.role === 'ADMIN' ? <AdminPage /> : <Navigate to="/" replace />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}

function ChooseOwnPassword() {
  const { signOut } = useAuth();
  return (
    <div className="auth-shell">
      <Card
        className="auth-card"
        title="Choose your own password"
        subtitle="An admin reset your password. Type the temporary one they gave you, then pick a new one only you know."
      >
        <ChangePasswordForm temporary />
        <button type="button" className="btn btn-quiet btn-small" onClick={signOut}>
          Sign out instead
        </button>
      </Card>
    </div>
  );
}

/** Sends a signed-out visitor to sign in, remembering where they were going (an invite link, say). */
function ToSignIn() {
  const location = useLocation();
  const [afterSignOut] = useState(consumeSignedOut);
  const next = location.pathname + location.search;
  const remember = next !== '/' && !afterSignOut;
  return <Navigate to={remember ? '/sign-in?next=' + encodeURIComponent(next) : '/sign-in'} replace />;
}

/** After signing in, carries on to where the visitor was going. Only same-app paths are followed. */
function BackFromSignIn() {
  const [params] = useSearchParams();
  const next = params.get('next');
  const safe = next && next.startsWith('/') && !next.startsWith('//') ? next : '/';
  return <Navigate to={safe} replace />;
}

/** Shown when signing in takes more than a few seconds, which almost always means the server is waking up. */
function WakingHint() {
  const [show, setShow] = useState(false);
  useEffect(() => {
    const timer = window.setTimeout(() => setShow(true), 4000);
    return () => window.clearTimeout(timer);
  }, []);
  if (!show) return null;
  return (
    <p className="muted small" style={{ margin: 0 }}>
      Waking the server… After a quiet spell this can take up to a minute. Keep this page open.
    </p>
  );
}
