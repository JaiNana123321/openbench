import { Link, NavLink, Outlet } from 'react-router';
import { useCurrentMember } from '../auth/currentMember';
import { hasAnyRole } from '../auth/roles';
import { useSignOut } from '../auth/session';
import { Button } from '../ui';
import type { NavItem } from './feature';
import { NavItemsContext } from './navigation';
import { useUnreadNotifications } from './unreadNotifications';

/** The frame of every signed-in page: the header with the menu and the Member, then the page. */
export function AppShell({ nav }: { nav: readonly NavItem[] }) {
  const me = useCurrentMember();
  const signOut = useSignOut();
  const items = nav.filter((item) => hasAnyRole(me, item.roles));

  return (
    <NavItemsContext value={items}>
      <header className="app-header">
        <div className="app-header__inner">
          <Link to="/" className="brand">
            OpenBench
          </Link>
          <nav className="main-nav" aria-label="Main">
            {items.map((item) => (
              <NavLink key={item.to} to={item.to} end={item.to === '/'}>
                {item.label}
              </NavLink>
            ))}
          </nav>
          <div className="session">
            <UnreadLink />
            <div className="session__who">
              <span className="session__name">{me.name}</span>
              <span className="session__team">{me.team.name}</span>
            </div>
            <Button variant="secondary" busy={signOut.isPending} onClick={() => signOut.mutate()}>
              Sign out
            </Button>
          </div>
        </div>
      </header>
      <main className="app-main">
        <Outlet />
      </main>
    </NavItemsContext>
  );
}

/** The header's link to the notifications page, with the number of unread notices once known. */
function UnreadLink() {
  const unread = useUnreadNotifications();
  const count = unread.data?.totalElements ?? 0;
  return (
    <NavLink
      to="/notifications"
      className="notices-link"
      aria-label={count === 0 ? 'Notifications' : `Notifications, ${count} unread`}
    >
      Notifications
      {count > 0 && (
        <span className="notices-count" aria-hidden="true">
          {count}
        </span>
      )}
    </NavLink>
  );
}
