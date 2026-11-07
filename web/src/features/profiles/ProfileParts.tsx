import { useState, type FormEvent } from 'react';
import type { DayRange, Id, MemberProfile, Skill } from '../../api/types';
import { Button, Field, Loading, ProblemMessage } from '../../ui';
import { useSkills, useUpdateAvailability, useUpdateSkills } from './queries';

/** Name, email, and Team. The Team is read-only: only an Administrator moves a Member. */
export function ProfileDetails({ profile }: { profile: MemberProfile }) {
  return (
    <dl className="details panel" aria-label="Profile">
      <dt>Name</dt>
      <dd>{profile.name}</dd>
      <dt>Email</dt>
      <dd>{profile.email}</dd>
      <dt>Team</dt>
      <dd>{profile.team.name}</dd>
    </dl>
  );
}

/** The skills a Member holds, as tags. */
export function SkillTags({ skills }: { skills: readonly Skill[] }) {
  if (skills.length === 0) return <p className="field__hint">No skills.</p>;
  return (
    <ul className="tags" aria-label="Skills">
      {skills.map((skill) => (
        <li key={skill.id} className="tag">
          {skill.name}
          {skill.retired && ' (retired)'}
        </li>
      ))}
    </ul>
  );
}

/**
 * The caller's skills, chosen from the skill list with no free text. The offered skills are shown,
 * plus any retired skill the Member still holds.
 */
export function SkillPicker({ held }: { held: readonly Skill[] }) {
  const offered = useSkills();
  const save = useUpdateSkills();
  const [chosen, setChosen] = useState<ReadonlySet<Id>>(() => new Set(held.map((skill) => skill.id)));

  if (offered.isError) return <ProblemMessage error={offered.error} />;
  if (offered.isPending) return <Loading />;

  const retiredHeld = held.filter((skill) => skill.retired);
  const choices = [...offered.data, ...retiredHeld.filter((skill) => !offered.data.some((o) => o.id === skill.id))];

  function toggle(id: Id) {
    setChosen((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    save.mutate(choices.filter((skill) => chosen.has(skill.id)).map((skill) => skill.id));
  }

  return (
    <form className="panel form" onSubmit={handleSubmit} aria-label="Skills">
      {choices.length === 0 ? (
        <p className="field__hint">The skill list is empty.</p>
      ) : (
        <fieldset className="tags">
          <legend className="visually-hidden">Skills from the list</legend>
          {choices.map((skill) => (
            <label key={skill.id} className="checkbox">
              <input type="checkbox" checked={chosen.has(skill.id)} onChange={() => toggle(skill.id)} />
              {skill.name}
              {skill.retired && ' (retired)'}
            </label>
          ))}
        </fieldset>
      )}
      <ProblemMessage error={save.error} />
      <div className="actions">
        <Button type="submit" busy={save.isPending}>
          Save skills
        </Button>
        {save.isSuccess && <span role="status">Saved.</span>}
      </div>
    </form>
  );
}

interface RangeRow {
  key: number;
  from: string;
  to: string;
}

/**
 * Edits a Member's availability as date ranges and saves the whole list, which replaces the saved
 * one. The server refuses a range that ends before it starts and merges ranges that overlap or touch.
 */
export function AvailabilityEditor({ memberId, ranges }: { memberId: Id | 'me'; ranges: readonly DayRange[] }) {
  const save = useUpdateAvailability(memberId);
  const [nextKey, setNextKey] = useState(ranges.length);
  const [rows, setRows] = useState<RangeRow[]>(() => ranges.map((range, key) => ({ key, ...range })));

  function change(key: number, field: 'from' | 'to', value: string) {
    setRows((current) => current.map((row) => (row.key === key ? { ...row, [field]: value } : row)));
  }

  function add() {
    setRows((current) => [...current, { key: nextKey, from: '', to: '' }]);
    setNextKey(nextKey + 1);
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    save.mutate(rows.map(({ from, to }) => ({ from, to })), {
      onSuccess: (profile) => {
        setRows(profile.availability.map((range, key) => ({ key: nextKey + key, ...range })));
        setNextKey(nextKey + profile.availability.length);
      },
    });
  }

  return (
    <form className="panel form" onSubmit={handleSubmit} aria-label="Availability">
      {rows.length === 0 && <p className="field__hint">No availability ranges.</p>}
      {rows.map((row, index) => (
        <fieldset key={row.key} className="attribute-row">
          <legend className="visually-hidden">Range {index + 1}</legend>
          <Field label="From">
            <input type="date" required value={row.from} onChange={(e) => change(row.key, 'from', e.target.value)} />
          </Field>
          <Field label="To">
            <input type="date" required value={row.to} onChange={(e) => change(row.key, 'to', e.target.value)} />
          </Field>
          <span />
          <Button
            variant="secondary"
            onClick={() => setRows((current) => current.filter((other) => other.key !== row.key))}
          >
            Remove
          </Button>
        </fieldset>
      ))}
      <ProblemMessage error={save.error} />
      <div className="actions">
        <Button variant="secondary" onClick={add}>
          Add range
        </Button>
        <Button type="submit" busy={save.isPending}>
          Save availability
        </Button>
        {save.isSuccess && <span role="status">Saved.</span>}
      </div>
    </form>
  );
}
