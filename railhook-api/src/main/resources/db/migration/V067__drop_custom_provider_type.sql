-- Fold the CUSTOM provider type into GENERIC.
--
-- ProviderType carried both. GENERIC is documented in the product as "any other
-- provider" and has the whole HMAC_GENERIC verification mode behind it; CUSTOM meant
-- the same thing, had no verifier, and had no label of its own in the dashboard or in
-- either locale file — the only thing choosing it could change was the word on the
-- source's badge. Two names for one idea, and the second one explained nowhere.
--
-- Nothing about verification changes for the sources being moved. provider_type only
-- selects a preset when verification_mode is PROVIDER, and PROVIDER mode with CUSTOM
-- has been refused at write time since the verifier check moved there, so every row
-- touched here is verifying through HMAC_GENERIC or not at all. Both keep working
-- exactly as before; the badge now reads GENERIC.
--
-- Written before the enum value is gone from the running code rather than after: an
-- instance still on the old image can insert CUSTOM, and one on the new image cannot
-- read it back. The window is a rolling deploy long, and the sweep below is the thing
-- that closes it.

UPDATE incoming_sources SET provider_type = 'GENERIC' WHERE provider_type = 'CUSTOM';

COMMENT ON COLUMN incoming_sources.provider_type IS
    'GENERIC, GITHUB, GITLAB, STRIPE, SHOPIFY, SLACK, TWILIO. Every value but GENERIC has a '
    'built-in verifier and can be used with verification_mode = PROVIDER; GENERIC is the label '
    'for a provider with no preset, verified through HMAC_GENERIC.';
