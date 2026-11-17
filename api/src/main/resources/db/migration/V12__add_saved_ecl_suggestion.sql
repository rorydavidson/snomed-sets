-- An ECL expression the owner chose to keep alongside a hand-picked value set, for reference.
-- It does not define the set: the hand-picked list does. What it was checked against is kept
-- with it, so the page can say how well it matched and whether the list has changed since.
CREATE TABLE value_set_saved_ecl (
    value_set_id     BIGINT       NOT NULL,
    ecl              TEXT         NOT NULL,
    -- The release branch the expression was checked on.
    branch           VARCHAR(200) NOT NULL,
    exact_match      BOOLEAN      NOT NULL,
    -- Active concepts in the list when it was checked.
    concept_count    INT          NOT NULL,
    missing_count    BIGINT       NOT NULL,
    extra_count      BIGINT       NOT NULL,
    -- Inactive concepts in the list, which no expression can match.
    inactive_count   INT          NOT NULL,
    -- SHA-256 of the list's sorted concept identifiers, to tell when the list has changed.
    list_fingerprint CHAR(64)     NOT NULL,
    saved_at         DATETIME(6)  NOT NULL,
    PRIMARY KEY (value_set_id),
    CONSTRAINT fk_value_set_saved_ecl_value_set
        FOREIGN KEY (value_set_id) REFERENCES value_set (id) ON DELETE CASCADE
);
