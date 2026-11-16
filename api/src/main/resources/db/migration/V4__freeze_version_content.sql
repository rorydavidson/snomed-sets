-- From now on publishing stores the concept list of every version, ECL sets included.
-- Versions published before this have no stored list and are still expanded live.
ALTER TABLE value_set_version
    ADD COLUMN content_frozen BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE value_set_version
SET content_frozen = TRUE
WHERE EXISTS (SELECT 1
              FROM value_set_member
              WHERE value_set_member.value_set_id = value_set_version.value_set_id
                AND value_set_member.version_number = value_set_version.version_number);
