import { Link, NavLink, Outlet } from 'react-router';
import { useCurrentMember } from '../auth/currentMember';
import { hasAnyRole } from '../auth/roles';
import { useSignOut } from '../auth/session';
import { Button } from '../ui';
import type { NavItem } from './feature';
import { NavItemsContext } from './navigation';

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
