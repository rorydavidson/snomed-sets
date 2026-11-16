CREATE TABLE value_set (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    name          VARCHAR(200)  NOT NULL,
    description   VARCHAR(2000) NOT NULL,
    ecl           VARCHAR(4000) NOT NULL,
    branch        VARCHAR(200)  NOT NULL,
    status        VARCHAR(20)   NOT NULL,
    visibility    VARCHAR(20)   NOT NULL,
    owner_subject VARCHAR(255)  NOT NULL,
    owner_name    VARCHAR(255)  NOT NULL,
    created_at    DATETIME(6)   NOT NULL,
    updated_at    DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_value_set_visibility (visibility),
    INDEX idx_value_set_owner (owner_subject)
);
