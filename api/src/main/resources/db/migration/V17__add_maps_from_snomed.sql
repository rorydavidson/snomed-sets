-- Maps from SNOMED CT: the rows are concepts, and the targets are chosen from a local list of codes.
CREATE TABLE code_map_local_code (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    map_id         BIGINT       NOT NULL,
    -- 0 is the working draft, as for code_map_row.
    version_number INT          NOT NULL,
    code           VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    term           VARCHAR(500) NOT NULL,
    position       INT          NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_code_map_local_code (map_id, version_number, code),
    CONSTRAINT fk_code_map_local_code_map
        FOREIGN KEY (map_id) REFERENCES code_map (id) ON DELETE CASCADE
);

-- The fully specified name of a SNOMED CT source, which says what kind of thing it is. Empty for a local code.
ALTER TABLE code_map_row
    ADD COLUMN source_fsn VARCHAR(500) NOT NULL DEFAULT '' AFTER source_term;
