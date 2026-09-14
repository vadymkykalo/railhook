-- Email addresses name one account whatever case they are typed in.
--
-- users.email was UNIQUE byte for byte, and registration and sign-in matched it exactly, while
-- Google sign-in and email change looked it up ignoring case and expected a single row. Anyone
-- could therefore register Victim@corp.com beside victim@corp.com, after which the owner's Google
-- sign-in failed on two results and an email change touching the address answered 500. The
-- application now stores addresses trimmed and lower-cased; this brings existing rows into line
-- and makes the database refuse a case variant from here on.
--
-- Accounts whose addresses already differ only in case cannot be fixed here: each is someone's
-- organizations, sessions and history, and nothing in SQL can tell which one is the real owner or
-- whether they are the same person. The migration stops, names the accounts by id, and leaves
-- every row untouched, so an operator can resolve them and start again.
--
-- users is small, so the plain index build's lock is not a concern.
DO $$
DECLARE
    duplicates TEXT;
BEGIN
    SELECT string_agg(ids, '; ')
      INTO duplicates
      FROM (SELECT '[' || string_agg(id::text, ', ' ORDER BY created_at) || ']' AS ids
              FROM users
             GROUP BY lower(btrim(email))
            HAVING count(*) > 1) groups;

    IF duplicates IS NOT NULL THEN
        RAISE EXCEPTION 'V076 stopped: some accounts have email addresses that differ only in case or surrounding spaces, so they cannot all keep their address. Nothing was changed. For each group of user ids below, change the address of, or remove, all but one account, then start the API again: %', duplicates;
    END IF;
END $$;

UPDATE users SET email = lower(btrim(email)) WHERE email <> lower(btrim(email));

CREATE UNIQUE INDEX uq_users_email_lower ON users (lower(email));

COMMENT ON INDEX uq_users_email_lower IS
    'An address names one account regardless of case. The application stores addresses lower-cased; this is what refuses a case variant written by anything else.';
