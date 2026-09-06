-- Operator-initiated suspension, which is a different thing from the billing status.
--
-- billing_status already carries SUSPENDED, written by the dunning scheduler when a grace
-- period expires. Two problems with reusing it: nothing anywhere reads it, so it suspends
-- nothing; and an operator suspending an organization for abuse would be overwritten the next
-- time the subscription lifecycle syncs, because that column belongs to the payment state
-- machine. An abuse suspension has to outlive a successful payment.
--
-- Nullable with no default: an existing organization is not suspended, and a rolling deploy
-- keeps inserting rows without these columns until the new code is everywhere.
ALTER TABLE organizations
    ADD COLUMN suspended_at        TIMESTAMPTZ,
    ADD COLUMN suspension_reason   TEXT,
    ADD COLUMN suspended_by        TEXT;

COMMENT ON COLUMN organizations.suspended_at IS
    'When an operator suspended this organization. NULL means active. Independent of '
    'billing_status, which the dunning scheduler owns.';
COMMENT ON COLUMN organizations.suspension_reason IS
    'Why, in the operator''s words. Returned to the tenant in the refusal, so it has to be '
    'something a customer can be shown.';
COMMENT ON COLUMN organizations.suspended_by IS
    'Free text identifying whoever suspended it. The platform-admin credential is one shared '
    'token with no identity of its own, so this is what the operator says it is.';

-- Partial: the answer being looked up is almost always "not suspended", and the index only
-- has to serve the operator listing suspended organizations.
CREATE INDEX idx_organizations_suspended ON organizations (suspended_at)
    WHERE suspended_at IS NOT NULL;
