import { useState, type FormEvent } from 'react';
import type { LocalDate, Loan } from '../../api/types';
import { Button, Field, formatDay, formatDayRange, ProblemMessage, todayInCompanyZone } from '../../ui';
import { useEndLoan } from './queries';

/**
 * Ends a Loan early (Release.Loan.Prorate), for the borrowing Manager. The Manager picks the new last
 * day, from the Loan's first day or today, whichever is later, to the day before its last day, then
 * confirms. The Person is free from the day after; the lending Team's Earning is corrected for the
 * days no longer lent; the borrowing Team gets no Refund and no Charge.
 */
export function EndLoanForm({ loan, onClose }: { loan: Loan; onClose: () => void }) {
  const [newEnd, setNewEnd] = useState<LocalDate>('');
  const [confirming, setConfirming] = useState(false);
  const end = useEndLoan();
  const { earliest, latest } = newEndRange(loan);

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!confirming) {
      setConfirming(true);
      return;
    }
    end.mutate({ loanId: loan.id, body: { newEnd } });
  }

  if (end.isSuccess) {
    return (
      <div className="panel section" aria-labelledby={`end-loan-${loan.id}`}>
        <h4 id={`end-loan-${loan.id}`}>Loan ended early</h4>
        <p role="status">
          The Loan of {end.data.memberName} now covers {formatDayRange(end.data.days)}, {end.data.daysLent}{' '}
          {end.data.daysLent === 1 ? 'person-day' : 'person-days'} lent. {end.data.lendingTeam.name}’s Earning is
          corrected for the days no longer lent.
        </p>
        <div className="actions">
          <Button variant="secondary" onClick={onClose}>
            Close
          </Button>
        </div>
      </div>
    );
  }

  return (
    <div className="panel section" aria-labelledby={`end-loan-${loan.id}`}>
      <h4 id={`end-loan-${loan.id}`}>End the Loan of {loan.memberName} early</h4>
      <p>
        Lent by {loan.lendingTeam.name} for {formatDayRange(loan.days)}.
      </p>
      <form className="form" onSubmit={handleSubmit}>
        <Field
          label="New last day"
          hint={`From ${formatDay(earliest)} to ${formatDay(latest)}. ${loan.memberName} is free from the day after.`}
        >
          <input
            type="date"
            min={earliest}
            max={latest}
            value={newEnd}
            disabled={confirming}
            onChange={(event) => setNewEnd(event.target.value)}
          />
        </Field>
        {confirming && (
          <p role="status">
            End the Loan on {formatDay(newEnd)}? {loan.lendingTeam.name}’s Earning is corrected for the days no longer
            lent. Your Team gets no Refund and no Charge.
          </p>
        )}
        <ProblemMessage error={end.error} />
        <div className="actions">
          <Button
            type="submit"
            variant={confirming ? 'danger' : 'primary'}
            busy={end.isPending}
            disabled={newEnd < earliest || newEnd > latest}
          >
            {confirming ? 'End Loan early' : 'Continue'}
          </Button>
          <Button variant="secondary" onClick={confirming ? () => setConfirming(false) : onClose}>
            {confirming ? 'Back' : 'Cancel'}
          </Button>
        </div>
      </form>
    </div>
  );
}

/** Whether the Loan can still end early: some day from its first day or today lies before its last day. */
export function canEndEarly(loan: Loan): boolean {
  const { earliest, latest } = newEndRange(loan);
  return earliest <= latest;
}

/** The new last days the server accepts: on or after the first day and today, before the last day. */
function newEndRange(loan: Loan): { earliest: LocalDate; latest: LocalDate } {
  const today = todayInCompanyZone();
  return {
    earliest: loan.days.from > today ? loan.days.from : today,
    latest: dayBefore(loan.days.to),
  };
}

function dayBefore(day: LocalDate): LocalDate {
  const date = new Date(`${day}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() - 1);
  return date.toISOString().slice(0, 10);
}
