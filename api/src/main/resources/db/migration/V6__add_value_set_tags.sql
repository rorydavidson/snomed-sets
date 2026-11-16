-- Free-text labels on a value set, stored in lower case so "Asthma" and "asthma" are one tag.
CREATE TABLE value_set_tag (
    value_set_id BIGINT      NOT NULL,
    tag          VARCHAR(40) NOT NULL,
    PRIMARY KEY (value_set_id, tag),
    INDEX idx_value_set_tag_tag (tag),
    CONSTRAINT fk_value_set_tag_value_set
        FOREIGN KEY (value_set_id) REFERENCES value_set (id) ON DELETE CASCADE
);
