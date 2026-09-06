-- ============================================================
-- V029: Rules Engine — migrate flat conditions to nested tree DSL
-- ============================================================
-- Changes:
--   1. Migrate existing flat conditions array + conditions_operator
--      into a single tree-structured JSONB object:
--      {"type":"group","op":"AND","children":[{"type":"predicate",...}]}
--   2. Drop conditions_operator column (now embedded in tree root)
--   3. Update CHECK constraint

-- Step 1: Migrate existing data from flat array to tree format
UPDATE rules
SET conditions = jsonb_build_object(
    'type', 'group',
    'op', UPPER(conditions_operator),
    'children', (
        SELECT COALESCE(jsonb_agg(
            jsonb_build_object(
                'type', 'predicate',
                'field', elem->>'field',
                'operator', UPPER(COALESCE(elem->>'operator', 'EQ')),
                'value', elem->'value',
                'valueType', CASE
                    WHEN jsonb_typeof(elem->'value') = 'number' THEN '"NUMBER"'
                    WHEN jsonb_typeof(elem->'value') = 'boolean' THEN '"BOOLEAN"'
                    WHEN jsonb_typeof(elem->'value') = 'array' THEN '"ARRAY_STRING"'
                    ELSE '"STRING"'
                END
            )
        ), '[]'::jsonb)
        FROM jsonb_array_elements(conditions) AS elem
    )
)
WHERE jsonb_typeof(conditions) = 'array';

-- Step 2: Set empty arrays to null (no conditions = match all)
UPDATE rules
SET conditions = NULL
WHERE conditions = '{"type":"group","op":"AND","children":[]}'::jsonb
   OR conditions = '[]'::jsonb;

-- Step 3: Drop conditions_operator column and its CHECK constraint
ALTER TABLE rules DROP CONSTRAINT IF EXISTS chk_conditions_operator;
ALTER TABLE rules DROP COLUMN IF EXISTS conditions_operator;

-- Step 4: Allow NULL conditions (= match all events)
ALTER TABLE rules ALTER COLUMN conditions DROP NOT NULL;
ALTER TABLE rules ALTER COLUMN conditions DROP DEFAULT;
