-- A value set is defined either by an ECL expression or by a hand-picked list of concepts.
ALTER TABLE value_set
    ADD COLUMN definition_type VARCHAR(20) NOT NULL DEFAULT 'ECL' AFTER description;

-- The concepts of a hand-picked value set. version_number 0 is the list being edited;
-- any other number is the copy frozen when that version was published.
CREATE TABLE value_set_member (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    value_set_id         BIGINT       NOT NULL,
    version_number       INT          NOT NULL,
    concept_id           VARCHAR(18)  NOT NULL,
    preferred_term       VARCHAR(500) NOT NULL,
    fully_specified_name VARCHAR(500) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_value_set_member (value_set_id, version_number, concept_id),
    CONSTRAINT fk_value_set_member_value_set
        FOREIGN KEY (value_set_id) REFERENCES value_set (id) ON DELETE CASCADE
);
