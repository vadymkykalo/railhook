-- Which signature headers an endpoint receives.
--
-- Hookflow has always signed with a Stripe-shaped header: X-Signature: t=<millis>,v1=<hex>
-- over "<millis>.<body>". It works, and every existing receiver was built against it. What it
-- is not is something a receiver can verify with a library they already have.
--
-- The Standard Webhooks convention -- webhook-id / webhook-timestamp / webhook-signature over
-- "<id>.<timestamp>.<body>", base64 rather than hex -- has been adopted by OpenAI, Anthropic,
-- Twilio, PagerDuty and Supabase, and has verification libraries in nine languages. Speaking
-- it turns "read our signing documentation" into "add one dependency".
--
-- Defaulted to BOTH rather than LEGACY on purpose. Extra headers cost a receiver nothing --
-- it verifies the one it knows and ignores the rest -- so every existing endpoint keeps
-- working untouched while a new one can reach for a standard library from day one. Nobody
-- has to migrate, and nobody is asked to choose before they know the difference.
--
-- NOT NULL with a default, so a rolling deploy is safe: instances still running the old code
-- insert without the column and get BOTH.

ALTER TABLE endpoints
    ADD COLUMN signature_scheme VARCHAR(20) NOT NULL DEFAULT 'BOTH';

COMMENT ON COLUMN endpoints.signature_scheme IS
    'LEGACY = X-Signature only; STANDARD = the Standard Webhooks headers only; BOTH = each of '
    'them, which is the default because unknown headers are free for a receiver to ignore.';
