import { useState, type FormEvent } from 'react';
import { fieldError } from '../../api/client';
import type { Id, LocalDate } from '../../api/types';
import { Button, COMPANY_TIME_ZONE, Field, ProblemMessage, toInstant } from '../../ui';
import { useAddLine, useLendableMembers, useLineTypes, useOfferedSkills } from './queries';

/**
 * Adds a Person line to a Draft: whole days, and either a set of skills and how many People, or one
 * named Person of another Team (quantity 1). The line costs nothing; on submission it gets a
 * Shortlist, or the named Person's Manager is asked to approve.
 */
export function AddPersonLineForm({ requestId }: { requestId: Id }) {
  const types = useLineTypes();
  const skills = useOfferedSkills();
  const lendable = useLendableMembers();
  const add = useAddLine(requestId);
  const [by, setBy] = useState<'skills' | 'person'>('skills');
  const [chosen, setChosen] = useState<Id[]>([]);
  const [member, setMember] = useState('');
  const [quantity, setQuantity] = useState('1');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');

  const personType = (types.data ?? []).find((type) => type.allocationClass === 'MATCHED');
  if (!personType) return null;

  function addSkill(skill: Id) {
    setChosen((current) => (current.includes(skill) ? current : [...current, skill]));
  }

  function removeSkill(skill: Id) {
    setChosen((current) => current.filter((id) => id !== skill));
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!personType) return;
    add.mutate(
      {
        typeId: personType.id,
        quantity: by === 'person' ? 1 : Number(quantity),
        start: toInstant(`${from}T00:00`),
        end: toInstant(`${nextDay(to)}T00:00`),
        ...(by === 'person' ? { namedMemberId: Number(member) } : { skills: chosen }),
      },
      {
        onSuccess: () => {
          setChosen([]);
          setMember('');
          setQuantity('1');
        },
      },
    );
  }

  return (
    <section className="section panel" aria-labelledby="add-person-line-title">
      <h2 id="add-person-line-title" className="section-title">
        Add a Person line
      </h2>
      <form onSubmit={handleSubmit}>
        <fieldset className="choice-group">
          <legend className="field__label">Ask for</legend>
          <label>
            <input type="radio" name="by" checked={by === 'skills'} onChange={() => setBy('skills')} /> People with
            these skills
          </label>
          <label>
            <input type="radio" name="by" checked={by === 'person'} onChange={() => setBy('person')} /> One named
            Person
          </label>
        </fieldset>
        <div className="line-form">
          {by === 'skills' ? (
            <>
              <div>
                <Field label="Skills" error={fieldError(add.error, 'skills')}>
                  <select
                    value=""
                    disabled={skills.isPending}
                    onChange={(event) => {
                      if (event.target.value !== '') addSkill(Number(event.target.value));
                    }}
                  >
                    <option value="">{chosen.length === 0 ? 'Choose a skill' : 'Add another skill'}</option>
                    {skills.data
                      ?.filter((skill) => !chosen.includes(skill.id))
                      .map((skill) => (
                        <option key={skill.id} value={skill.id}>
                          {skill.name}
                        </option>
                      ))}
                  </select>
                </Field>
                {chosen.length > 0 && (
                  <ul className="tags skill-chosen" aria-label="Chosen skills">
                    {chosen.map((id) => {
                      const name = skills.data?.find((skill) => skill.id === id)?.name ?? `Skill ${id}`;
                      return (
                        <li key={id} className="tag">
                          {name}
                          <button
                            type="button"
                            className="tag__remove"
                            aria-label={`Remove ${name}`}
                            onClick={() => removeSkill(id)}
                          >
                            ×
                          </button>
                        </li>
                      );
                    })}
                  </ul>
                )}
              </div>
              <Field label="People" hint="How many, all with every skill." error={fieldError(add.error, 'quantity')}>
                <input
                  type="number"
                  min={1}
                  step={1}
                  value={quantity}
                  required
                  onChange={(event) => setQuantity(event.target.value)}
                />
              </Field>
            </>
          ) : (
            <Field label="Named Person" hint="A Member of another Team.">
              <select value={member} required onChange={(event) => setMember(event.target.value)}>
                <option value="">Choose a Person</option>
                {lendable.data?.map((candidate) => (
                  <option key={candidate.id} value={candidate.id}>
                    {candidate.name} ({candidate.team.name})
                  </option>
                ))}
              </select>
            </Field>
          )}
          <Field label="First day" hint={`Whole days, ${COMPANY_TIME_ZONE} time.`} error={fieldError(add.error, 'start')}>
            <input type="date" value={from} required onChange={(event) => setFrom(event.target.value)} />
          </Field>
          <Field label="Last day" hint="Included." error={fieldError(add.error, 'end')}>
            <input type="date" value={to} required onChange={(event) => setTo(event.target.value)} />
          </Field>
        </div>
        <ProblemMessage error={add.error} />
        <div className="actions">
          <Button type="submit" busy={add.isPending}>
            Add Person line
          </Button>
        </div>
      </form>
    </section>
  );
}

/** The day after a day, as the API writes days. */
function nextDay(day: LocalDate): LocalDate {
  const next = new Date(`${day}T00:00:00Z`);
  next.setUTCDate(next.getUTCDate() + 1);
  return next.toISOString().slice(0, 10);
}
