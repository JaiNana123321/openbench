import { formatEnum } from './format';

export type Tone = 'success' | 'warning' | 'danger' | 'info' | 'neutral';

/** The color of each upper-case state the API sends. A state not listed is neutral. */
const TONES: Record<string, Tone> = {
  ALLOCATED: 'success',
  APPROVED: 'success',
  SUBMITTED: 'info',
  PENDING: 'info',
  HELD: 'info',
  WAITING: 'warning',
  REFUSED: 'danger',
  DECLINED: 'danger',
  EXPIRED: 'danger',
  DRAFT: 'neutral',
  WITHDRAWN: 'neutral',
  RELEASED: 'neutral',
  RETIRED: 'neutral',
};

interface StatusBadgeProps {
  /** An upper-case state as the API sends it, such as `ALLOCATED`; shown as "Allocated". */
  status: string;
  tone?: Tone;
}

/** A state as a colored label. It only shows the state the server sent; it never works one out. */
export function StatusBadge({ status, tone = TONES[status] ?? 'neutral' }: StatusBadgeProps) {
  return <span className={`badge badge--${tone}`}>{formatEnum(status)}</span>;
}
