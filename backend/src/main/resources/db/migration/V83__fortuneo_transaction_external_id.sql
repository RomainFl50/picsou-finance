-- Stable provider-side transaction identifier, used to import a full Fortuneo
-- history idempotently instead of replacing a rolling 90-day window.
-- Nullable: manual rows and every connector that has no provider id keep NULL,
-- and the partial unique index only constrains the rows that do carry one.
ALTER TABLE transaction ADD COLUMN IF NOT EXISTS external_id VARCHAR(100);

CREATE UNIQUE INDEX IF NOT EXISTS ux_transaction_account_external_id
    ON transaction (account_id, external_id)
    WHERE external_id IS NOT NULL;
