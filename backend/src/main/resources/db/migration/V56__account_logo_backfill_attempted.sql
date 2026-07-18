-- V56: Track logo backfill attempts on the account itself, for accounts that never went
-- through the Enable Banking connection flow (Finary import, other sidecars) and therefore
-- have no Requisition to attach a backfill marker to.

ALTER TABLE account ADD COLUMN logo_backfill_attempted_at TIMESTAMPTZ;
