-- A name the owner can give a version, such as a date, shown instead of "Version 3". The number
-- stays as the version's fixed identity, for links and downloads. Labels are unique within a set.
ALTER TABLE value_set_version
    ADD COLUMN label VARCHAR(40) NULL,
    ADD UNIQUE INDEX uq_value_set_version_label (value_set_id, label);
