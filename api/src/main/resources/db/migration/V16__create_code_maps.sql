-- A map between a list of local codes and SNOMED CT, built on one release like a value set.
CREATE TABLE code_map (
    id                   BIGINT        NOT NULL AUTO_INCREMENT,
    name                 VARCHAR(200)  NOT NULL,
    description          VARCHAR(2000) NOT NULL,
    -- TO_SNOMED: local codes are given SNOMED CT targets. Fixed when the map is created.
    direction            VARCHAR(20)   NOT NULL,
    code_system          VARCHAR(100)  NOT NULL,
    branch               VARCHAR(200)  NOT NULL,
    local_system_name    VARCHAR(200)  NOT NULL,
    local_system_uri     VARCHAR(500)  NULL,
    local_system_version VARCHAR(100)  NULL,
    owner_subject        VARCHAR(255)  NOT NULL,
    owner_name           VARCHAR(255)  NOT NULL,
    created_at           DATETIME(6)   NOT NULL,
    updated_at           DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_code_map_owner (owner_subject)
);

CREATE TABLE code_map_row (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    map_id         BIGINT       NOT NULL,
    -- 0 is the working draft, as for value_set_member.
    version_number INT          NOT NULL,
    -- Compared byte for byte: local codes that differ only in case are different codes.
    source_code    VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    source_term    VARCHAR(500) NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    note           VARCHAR(500) NULL,
    -- True once the term has been looked for in SNOMED CT, so it is not looked for twice.
    term_checked   BOOLEAN      NOT NULL DEFAULT FALSE,
    -- Keeps the order the rows were given in.
    position       INT          NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_code_map_row (map_id, version_number, source_code),
    CONSTRAINT fk_code_map_row_map
        FOREIGN KEY (map_id) REFERENCES code_map (id) ON DELETE CASCADE
);

-- The targets of a row. While the row is SUGGESTED they are suggestions nobody has accepted yet.
CREATE TABLE code_map_target (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    row_id       BIGINT       NOT NULL,
    target_code  VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    target_term  VARCHAR(500) NOT NULL,
    -- The fully specified name of a SNOMED CT target; empty for a local code.
    target_fsn   VARCHAR(500) NOT NULL,
    relationship VARCHAR(20)  NOT NULL,
    -- How the target got here: MANUAL, or EXACT_TERM for a term match.
    origin       VARCHAR(20)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_code_map_target (row_id, target_code),
    CONSTRAINT fk_code_map_target_row
        FOREIGN KEY (row_id) REFERENCES code_map_row (id) ON DELETE CASCADE
);
