import type { RequestLine } from '../../api/types';
import { formatDayRange, formatWindow } from '../../ui';

// How the line tables show a Person line: whom it asks for, how many, and its whole days.

/** "Dave Kim" for a named Person, "Statistics, Java" for skills; null for any other line. */
export function personAsk(line: RequestLine): string | null {
  if (line.allocationClass !== 'MATCHED') return null;
  if (line.namedMemberName) return line.namedMemberName;
  const skills = line.skills ?? [];
  return skills.length === 0 ? 'Anyone' : skills.map((skill) => skill.name).join(', ');
}

/** "2 People" for a Person line; null for any other line. */
export function personCount(line: RequestLine): string | null {
  if (line.allocationClass !== 'MATCHED') return null;
  return `${line.quantity} ${line.quantity === 1 ? 'Person' : 'People'}`;
}

/** A Person line's days; any other line's window, with "weekly ×3" when it recurs. */
export function whenText(line: RequestLine): string {
  if (line.days) return formatDayRange(line.days);
  const window = formatWindow(line.window);
  return line.recurrence ? `${window}, weekly ×${line.recurrence.occurrences}` : window;
}
