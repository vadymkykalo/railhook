-- ============================================================
-- V085: a Transformation says which language it is written in
-- ============================================================
--
-- Until now there was one language and the column did not need to exist: a JSON document whose
-- string values may hold dollar-brace $.jsonpath expressions. JavaScript transformations add a
-- second, and the two are not distinguishable by looking at the text — a script and a template
-- are both just TEXT in `template`.
--
-- DEFAULT 'TEMPLATE' rather than a backfill, for two reasons. Every row that exists is a
-- template, so the default *is* the backfill; and the api and worker instances still running
-- during a rolling deploy insert without this column, which the default keeps working. Nobody
-- is migrated: a template that works is not a problem to be fixed.
--
-- The column stays VARCHAR with no CHECK constraint, like every other enum-backed column in
-- this schema (deliveries.status, incoming_sources.provider_type). TransformationKind.fromStored
-- treats an unknown value as TEMPLATE, so a row written by a newer version does not stop an
-- older one from starting.
--
-- Nothing in this file writes the two characters dollar-brace next to each other, deliberately:
-- Flyway reads them as a placeholder it has no value for and refuses to parse the migration at
-- all, which is a startup failure rather than a bad comment.

ALTER TABLE transformations
    ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'TEMPLATE';

COMMENT ON COLUMN transformations.kind IS
    'TEMPLATE (a JSON document with dollar-brace $.jsonpath substitutions) or JAVASCRIPT (a sandboxed handler(webhook) function). Existing rows are TEMPLATE.';

-- The history carries the language too, or a restore is a trap: V083 keeps one row per published
-- template, and putting an old row back into a transformation that has since been rewritten as a
-- script would leave JSON in a row still marked JAVASCRIPT. The next delivery would then fail to
-- compile it, once per attempt, for every event — and the person who pressed Restore would have
-- no reason to connect the two. A version is the text *and* the language it was written in.
ALTER TABLE transformation_versions
    ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'TEMPLATE';

COMMENT ON COLUMN transformation_versions.kind IS
    'The language this version was published in. Restored together with the template, so a restore cannot leave the two disagreeing.';
