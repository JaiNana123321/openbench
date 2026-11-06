import { useState, type FormEvent } from 'react';
import { fieldError } from '../../api/client';
import type { Skill } from '../../api/types';
import { ErrorPage } from '../../shell/ErrorPage';
import { Button, EmptyState, Field, Loading, PageHeader, ProblemMessage, StatusBadge, Table, type Column } from '../../ui';
import { useAddSkill, useAllSkills, useRetireSkill } from './queries';

/**
 * /admin/skills: the skill list Members choose their skills from. A retired skill stays with the
 * Members who hold it but is no longer offered.
 */
export function SkillsPage() {
  const skills = useAllSkills();
  const add = useAddSkill();
  const retire = useRetireSkill();
  const [name, setName] = useState('');

  if (skills.isError) return <ErrorPage error={skills.error} />;

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    add.mutate(name, { onSuccess: () => setName('') });
  }

  const columns: Column<Skill>[] = [
    { header: 'Skill', cell: (skill) => skill.name },
    {
      header: 'Status',
      cell: (skill) =>
        skill.retired ? <StatusBadge status="RETIRED" /> : <StatusBadge status="OFFERED" tone="success" />,
    },
    {
      header: 'Action',
      cell: (skill) =>
        !skill.retired && (
          <Button
            variant="secondary"
            busy={retire.isPending && retire.variables === skill.id}
            onClick={() => retire.mutate(skill.id)}
          >
            Retire {skill.name}
          </Button>
        ),
    },
  ];

  return (
    <>
      <PageHeader title="Skill list" subtitle="Members choose their skills from this list, so matching is exact." />
      <section className="section">
        <form className="panel form" onSubmit={handleSubmit} aria-label="Add a skill">
          <Field label="Name" error={fieldError(add.error, 'name')}>
            <input value={name} required onChange={(event) => setName(event.target.value)} />
          </Field>
          <ProblemMessage error={add.error} />
          <div className="actions">
            <Button type="submit" busy={add.isPending}>
              Add skill
            </Button>
          </div>
        </form>
      </section>
      <ProblemMessage error={retire.error} />
      {skills.isPending ? (
        <Loading />
      ) : (
        <Table
          label="Skills"
          columns={columns}
          rows={skills.data}
          rowKey={(skill) => skill.id}
          empty={<EmptyState title="The skill list is empty." />}
        />
      )}
    </>
  );
}
