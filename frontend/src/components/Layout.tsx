import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Link, NavLink, Outlet, useLocation } from 'react-router-dom';
import { notifications } from '../api/endpoints';
import { useAuth } from '../auth/useAuth';
import { labelTables } from '../lib/tableLabels';
import { BrandMark } from './BrandMark';
import { ThemeToggle } from './ThemeToggle';

/**
 * The frame every signed-in screen sits in.
 *
 * The unread count is polled rather than pushed: there is no socket in this version, so the bell refreshes every
 * half minute and again whenever the route changes, which is when someone is most likely to have just done
 * something that produced a notification.
 *
 * On a phone the sections move to a tab bar along the bottom, where a thumb can reach them, and the top bar keeps
 * only the name of the app and a link to your profile (signing out is on the profile page there).
 */
export function Layout() {
  const { user, signOut } = useAuth();

  const location = useLocation();
  const [unread, setUnread] = useState(0);
  const main = useRef<HTMLElement>(null);

  useEffect(() => {
    let cancelled = false;
    const refresh = () => {
      notifications
        .unreadCount()
        .then(({ unread: count }) => {
          if (!cancelled) setUnread(count);
        })
        .catch(() => {
          // A failed count is not worth bothering the user about; the next tick will try again.
        });
    };
    refresh();
    const timer = window.setInterval(refresh, 30_000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [location.pathname]);

  // Tables become stacked cards on a phone; each cell needs its column's name to say what it is.
  useEffect(() => {
    const root = main.current;
    if (!root) return;
    labelTables(root);
    const observer = new MutationObserver(() => labelTables(root));
    observer.observe(root, { childList: true, subtree: true });
    return () => observer.disconnect();
  }, []);

  const badge = unread > 0 && <span className="bell-count">{unread > 99 ? '99+' : unread}</span>;

  return (
    <div className="app">
      <header className="topbar">
        <div className="topbar-inner">
          <Link to="/" className="brand">
            <BrandMark />
            Dugout XI
          </Link>
          <nav className="nav">
            <NavLink to="/" end>
              Home
            </NavLink>
            <NavLink to="/leagues">Leagues</NavLink>
            <NavLink to="/contests">Contests</NavLink>
            {user?.role === 'ADMIN' && <NavLink to="/admin">Admin</NavLink>}
          </nav>
          <div className="row gap topbar-end">
            <ThemeToggle />
            <Link
              to="/notifications"
              className="btn btn-quiet bell hide-phone"
              aria-label={`Notifications, ${unread} unread`}
            >
              Alerts
              {badge}
            </Link>
            <Link to="/profile" className="btn btn-quiet profile-link">
              {user?.displayName ?? 'Profile'}
            </Link>
            <button type="button" className="btn btn-small hide-phone" onClick={signOut}>
              Sign out
            </button>
          </div>
        </div>
      </header>

      <main className="page" ref={main}>
        <Outlet />
      </main>

      <nav className="tabbar" aria-label="Sections">
        <TabLink to="/" end label="Home" icon={<path d="M3 11l9-7 9 7v9a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z" />} />
        <TabLink
          to="/leagues"
          label="Leagues"
          icon={<path d="M7 4h10v4a5 5 0 0 1-10 0zM5 5H3v2a3 3 0 0 0 3 3M19 5h2v2a3 3 0 0 1-3 3M12 13v4M8 20h8" />}
        />
        <TabLink
          to="/contests"
          label="Contests"
          icon={<path d="M4 7h16v10H4zM9 7v10M15 7v10M4 12h5M15 12h5" />}
        />
        <TabLink
          to="/notifications"
          label="Alerts"
          badge={badge}
          ariaLabel={`Alerts, ${unread} unread`}
          icon={<path d="M6 16V11a6 6 0 0 1 12 0v5l2 2H4zM10 21h4" />}
        />
        {user?.role === 'ADMIN' && (
          <TabLink
            to="/admin"
            label="Admin"
            icon={<path d="M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z" />}
          />
        )}
      </nav>
    </div>
  );
}

function TabLink({
  to,
  label,
  icon,
  end,
  badge,
  ariaLabel,
}: {
  to: string;
  label: string;
  icon: ReactNode;
  end?: boolean;
  badge?: ReactNode;
  ariaLabel?: string;
}) {
  return (
    <NavLink to={to} end={end} className="tabbar-item" aria-label={ariaLabel}>
      <span className="tabbar-icon">
        <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="1.8"
          strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          {icon}
        </svg>
        {badge}
      </span>
      <span>{label}</span>
    </NavLink>
  );
}
