-- Earnings (design Sections 4.7 and 6; SRS Ledger.Earning.Post, Matched.Loan.Form): the key from
-- transaction.loan_id to loan, which V4 left for the increment that forms Loans, and one Earning per
-- Loan. A correction after an early end (issue #8) is a further EARNING row naming the corrected one
-- in corrects_id, so only the original Earning is unique.
ALTER TABLE transaction
    ADD CONSTRAINT transaction_loan_id_fkey FOREIGN KEY (loan_id) REFERENCES loan;

CREATE UNIQUE INDEX transaction_one_earning_per_loan ON transaction (loan_id)
    WHERE kind = 'EARNING' AND corrects_id IS NULL;
