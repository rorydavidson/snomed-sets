-- Value sets are now built against a code system release branch (for example MAIN/2026-01-01)
-- rather than the live branch, and are moved to a newer release by an upgrade. Each upgrade
-- keeps a report of what it changed, so the changes can be downloaded later.
CREATE TABLE value_set_upgrade (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    value_set_id      BIGINT       NOT NULL,
    -- Null when the value set was on the live branch rather than a release.
    from_version      VARCHAR(10)  NULL,
    to_version        VARCHAR(10)  NOT NULL,
    from_branch       VARCHAR(200) NOT NULL,
    to_branch         VARCHAR(200) NOT NULL,
    -- The version published together with the upgrade, or null if it was saved as a draft.
    published_version INT          NULL,
    upgraded_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_value_set_upgrade_value_set (value_set_id),
    CONSTRAINT fk_value_set_upgrade_value_set
        FOREIGN KEY (value_set_id) REFERENCES value_set (id) ON DELETE CASCADE
);

CREATE TABLE value_set_upgrade_change (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    upgrade_id         BIGINT       NOT NULL,
    change_type        VARCHAR(30)  NOT NULL,
    concept_id         VARCHAR(18)  NOT NULL,
    term               VARCHAR(500) NOT NULL,
    related_concept_id VARCHAR(18)  NULL,
    related_term       VARCHAR(500) NULL,
    -- The historical association or inactivation reason behind the change, where there is one.
    detail             VARCHAR(100) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_value_set_upgrade_change_upgrade
        FOREIGN KEY (upgrade_id) REFERENCES value_set_upgrade (id) ON DELETE CASCADE
);
