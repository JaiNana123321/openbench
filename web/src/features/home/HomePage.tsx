import { Link } from 'react-router';
import type { Role } from '../../api/types';
import { useCurrentMember } from '../../auth/currentMember';
import { ROLE_NAMES } from '../../auth/roles';
import { useNavItems } from '../../shell/navigation';
import { EmptyState, PageHeader } from '../../ui';

/** The home page: who the Member is, and links to every screen their roles allow. */
export function HomePage() {
  const me = useCurrentMember();
  const links = useNavItems().filter((item) => item.to !== '/');
  const roles = (Object.keys(ROLE_NAMES) as Role[]).filter((role) => me.roles.includes(role));

  return (
    <>
      <PageHeader title={`Welcome, ${me.name}`} subtitle={`Member of the ${me.team.name} Team.`} />

      <section className="section panel" aria-labelledby="account-title">
        <h2 id="account-title" className="section-title">
          Your account
        </h2>
        <dl className="details">
          <dt>Name</dt>
          <dd>{me.name}</dd>
          <dt>Username</dt>
          <dd>{me.username}</dd>
          <dt>Team</dt>
          <dd>{me.team.name}</dd>
          <dt>Roles</dt>
          <dd>
            <ul className="tags" aria-label="Roles">
              {roles.map((role) => (
                <li key={role} className="tag">
                  {ROLE_NAMES[role]}
                </li>
              ))}
            </ul>
          </dd>
        </dl>
      </section>

      <section className="section" aria-labelledby="links-title">
        <h2 id="links-title" className="section-title">
          What you can do
        </h2>
        {links.length === 0 ? (
          <EmptyState title="No screens for your roles yet." />
        ) : (
          <ul className="link-grid">
            {links.map((item) => (
              <li key={item.to}>
                <Link to={item.to} className="link-card">
                  <span className="link-card__label">{item.label}</span>
                  <span className="link-card__description">{item.description}</span>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </section>
    </>
  );
}
