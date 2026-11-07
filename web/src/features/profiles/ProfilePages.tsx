import { Link, useParams } from 'react-router';
import type { MemberSummary } from '../../api/types';
import { useCurrentMember } from '../../auth/currentMember';
import { ErrorPage } from '../../shell/ErrorPage';
import { EmptyState, Loading, PageHeader, StatusBadge, Table, type Column } from '../../ui';
import { AvailabilityEditor, ProfileDetails, SkillPicker, SkillTags } from './ProfileParts';
import { useProfile, useRoster } from './queries';

/** /profile: the caller's own profile; they choose their skills and edit their availability. */
export function MyProfilePage() {
  const profile = useProfile('me');
  if (profile.isError) return <ErrorPage error={profile.error} />;
  if (profile.isPending) return <Loading />;
  return (
    <>
      <PageHeader title="My profile" subtitle="Your skills and the days you can be lent to another Team." />
      <section className="section">
        <ProfileDetails profile={profile.data} />
      </section>
      <section className="section" aria-labelledby="skills-title">
        <h2 id="skills-title" className="section-title">
          Skills
        </h2>
        <SkillPicker held={profile.data.skills} />
      </section>
      <section className="section" aria-labelledby="availability-title">
        <h2 id="availability-title" className="section-title">
          Availability
        </h2>
        <AvailabilityEditor memberId="me" ranges={profile.data.availability} />
      </section>
    </>
  );
}

const rosterColumns: Column<MemberSummary>[] = [
  { header: 'Name', cell: (member) => <Link to={`/team/members/${member.id}`}>{member.name}</Link> },
  { header: 'Username', cell: (member) => member.username },
  {
    header: 'Status',
    cell: (member) =>
      member.active ? <StatusBadge status="ACTIVE" tone="success" /> : <StatusBadge status="DEACTIVATED" />,
  },
];

/** /team: the Manager's Team, each Member linking to their profile. */
export function TeamPage() {
  const me = useCurrentMember();
  const roster = useRoster(me.team.id);
  if (roster.isError) return <ErrorPage error={roster.error} />;
  return (
    <>
      <PageHeader title="Team" subtitle={me.team.name} />
      {roster.isPending ? (
        <Loading />
      ) : (
        <Table
          label="Team Members"
          columns={rosterColumns}
          rows={roster.data.content}
          rowKey={(member) => member.id}
          empty={<EmptyState title="The Team has no Members." />}
        />
      )}
    </>
  );
}

/** /team/members/:id: a Member of the Manager's Team; the Manager edits their availability. */
export function TeamMemberPage() {
  const id = Number(useParams().id);
  const profile = useProfile(id);
  if (profile.isError) return <ErrorPage error={profile.error} />;
  if (profile.isPending) return <Loading />;
  return (
    <>
      <PageHeader
        title={profile.data.name}
        actions={
          <Link to="/team" className="button button--secondary">
            Team
          </Link>
        }
      />
      <section className="section">
        <ProfileDetails profile={profile.data} />
      </section>
      <section className="section" aria-labelledby="skills-title">
        <h2 id="skills-title" className="section-title">
          Skills
        </h2>
        <SkillTags skills={profile.data.skills} />
      </section>
      <section className="section" aria-labelledby="availability-title">
        <h2 id="availability-title" className="section-title">
          Availability
        </h2>
        <AvailabilityEditor key={profile.data.id} memberId={profile.data.id} ranges={profile.data.availability} />
      </section>
    </>
  );
}
