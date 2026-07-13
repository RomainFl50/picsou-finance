-- V56: Personal per-member preference: whether bank logos are shown on account cards
-- (see docs/features/bank-logos.md). Defaults to on for existing members.

ALTER TABLE family_member ADD COLUMN show_bank_logos BOOLEAN NOT NULL DEFAULT true;
