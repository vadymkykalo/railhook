-- Incoming forward attempts recorded only what came back, never what went out: an operator
-- looking at a failed Forward could see the destination's 401 but not the Authorization header
-- or the transformed body that produced it, which is exactly what makes a relay failure
-- diagnosable. delivery_attempts has carried request_headers and request_body since the
-- beginning; these are the Incoming counterparts, named for the columns already on this table.
--
-- Nullable with no backfill: every row written before this migration genuinely has no record of
-- its request, and inventing one would be worse than an empty column.

ALTER TABLE incoming_forward_attempts ADD COLUMN request_headers_json TEXT;
ALTER TABLE incoming_forward_attempts ADD COLUMN request_body_snippet TEXT;

COMMENT ON COLUMN incoming_forward_attempts.request_headers_json IS
    'Headers as sent to the destination, sanitised: auth and signature values are masked because this is shown in the dashboard.';
COMMENT ON COLUMN incoming_forward_attempts.request_body_snippet IS
    'The transformed body actually sent, truncated to the same 10 KiB cap delivery_attempts uses.';
