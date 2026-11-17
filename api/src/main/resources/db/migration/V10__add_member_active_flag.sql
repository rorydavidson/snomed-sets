-- A hand-picked list can keep a concept that a newer release made inactive, when the owner
-- chooses to during an upgrade. Such a member is flagged so lists and downloads can say so.
ALTER TABLE value_set_member
    ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;
