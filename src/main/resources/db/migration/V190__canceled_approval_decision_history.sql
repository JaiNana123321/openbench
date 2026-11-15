-- Canceling a previously Approved Candidate must retain who decided and when. An Outstanding
-- approval has no decision to retain; both forms become CANCELED when a Request is reopened.
ALTER TABLE approval DROP CONSTRAINT approval_check;
ALTER TABLE approval ADD CONSTRAINT approval_check CHECK (
    state = 'CANCELED' OR ((state IN ('APPROVED', 'REFUSED')) = (decided_at IS NOT NULL))
);

-- Keep prior selections as history, while allowing a reopened line to select the same Person again.
ALTER TABLE candidate ADD COLUMN retired boolean NOT NULL DEFAULT false;
ALTER TABLE candidate DROP CONSTRAINT candidate_line_id_member_id_key;
CREATE UNIQUE INDEX candidate_current_member ON candidate (line_id, member_id) WHERE NOT retired;
