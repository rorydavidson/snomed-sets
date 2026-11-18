-- An optional SNOMED CT reference set concept that identifies the value set, for example in a
-- share link. Its fully specified name is kept as it was when the identifier was checked, so pages
-- can show it without asking Snowstorm.
ALTER TABLE value_set
    ADD COLUMN sctid      VARCHAR(18)  NULL,
    ADD COLUMN sctid_term VARCHAR(500) NULL,
    ADD UNIQUE INDEX uq_value_set_sctid (sctid);
