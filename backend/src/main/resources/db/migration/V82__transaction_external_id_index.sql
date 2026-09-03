-- Uniqueness for the provider-side transaction id added in V80, which is what
-- makes a Fortuneo re-import idempotent: a row already stored under the same
-- (account, external_id) is updated rather than duplicated.
--
-- Built CONCURRENTLY, because a plain CREATE UNIQUE INDEX takes a lock that
-- blocks every insert, update and delete on `transaction` until the build ends
-- -- on an install with years of imported history that is a visible outage on
-- the busiest table in the schema. CONCURRENTLY cannot run inside a
-- transaction, so this migration is the only non-transactional one; that is
-- configured in the adjacent V82__transaction_external_id_index.sql.conf.
--
-- Failure recovery: a CONCURRENTLY build that is interrupted leaves an INVALID
-- index behind, which IF NOT EXISTS would then silently accept. If this
-- migration fails, drop the leftover before retrying:
--
--   DROP INDEX IF EXISTS ux_transaction_account_external_id;
--   -- then `flyway repair`, or restart the application.
--
-- Duplicates that predate the index make the build fail by design; they must be
-- reconciled by hand rather than dropped silently.
CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS ux_transaction_account_external_id
    ON transaction (account_id, external_id)
    WHERE external_id IS NOT NULL;
