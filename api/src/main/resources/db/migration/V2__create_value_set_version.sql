-- A published version freezes the definition of a value set at the moment of publishing.
CREATE TABLE value_set_version (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    value_set_id   BIGINT        NOT NULL,
    version_number INT           NOT NULL,
    ecl            VARCHAR(4000) NOT NULL,
    branch         VARCHAR(200)  NOT NULL,
    concept_count  BIGINT        NOT NULL,
    note           VARCHAR(500)  NOT NULL,
    published_at   DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_value_set_version (value_set_id, version_number),
    CONSTRAINT fk_value_set_version_value_set
        FOREIGN KEY (value_set_id) REFERENCES value_set (id) ON DELETE CASCADE
);
