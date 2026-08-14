-- V47: Prevent duplicate active accounts with the same external_account_id + member_id.
-- Without this, concurrent TR sync calls (background + manual) could both find no existing account
-- and both INSERT a new row for the same externalId, producing x2 on the dashboard.

-- Step 1: Remove duplicates, keeping the row with the highest id per
-- (external_account_id, member_id, provider). Provider is part of the key because an
-- Enable Banking external id is the bank's own opaque string: two institutions are free
-- to hand out the same one, and collapsing them would destroy one bank's account outright
-- (see V77's merge logic, which keys on the same triple for the same reason).
DELETE FROM account
WHERE deleted_at IS NULL
  AND external_account_id IS NOT NULL
  AND is_manual = false
  AND id NOT IN (
    SELECT DISTINCT ON (external_account_id, member_id, provider) id
    FROM account
    WHERE deleted_at IS NULL AND external_account_id IS NOT NULL AND is_manual = false
    ORDER BY external_account_id, member_id, provider, id DESC
  );

-- Step 2: Partial unique index — only constrains active, non-manual accounts.
-- Manual accounts are excluded: external_account_id is free text on manual rows
-- (see V75), so two manual accounts may legitimately share one.
-- Soft-deleted tombstones (deleted_at IS NOT NULL) are excluded so the same
-- externalId can be reused when the user reconnects after an explicit deletion.
-- Provider is included so two banks issuing the same opaque id don't collide.
CREATE UNIQUE INDEX account_external_unique_active
  ON account(external_account_id, member_id, provider)
  WHERE deleted_at IS NULL AND external_account_id IS NOT NULL AND is_manual = false;
