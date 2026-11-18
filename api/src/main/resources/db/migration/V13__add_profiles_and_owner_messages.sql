-- What a person chooses to add about themselves. One row per account, made the first time they
-- save their profile. The subject is never sent to a browser; the public id is used instead.
CREATE TABLE user_profile (
    subject            VARCHAR(255) NOT NULL,
    public_id          CHAR(36)     NOT NULL,
    organisation       VARCHAR(120) NULL,
    -- Their verified account email, kept only while they let signed-in people message them.
    contact_email      VARCHAR(320) NULL,
    -- A PNG re-encoded by the application, at most 256 pixels square.
    avatar             MEDIUMBLOB   NULL,
    avatar_updated_at  DATETIME(6)  NULL,
    updated_at         DATETIME(6)  NOT NULL,
    PRIMARY KEY (subject),
    UNIQUE INDEX uq_user_profile_public_id (public_id)
);

-- Whether a value set names its owner's organisation instead of the owner.
ALTER TABLE value_set ADD COLUMN show_organisation BOOLEAN NOT NULL DEFAULT FALSE;

-- Who sent a message about which value set, and when, so sending can be limited. The message
-- itself is never stored. Rows older than a day are removed as new messages are sent.
CREATE TABLE owner_message (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    sender_subject VARCHAR(255) NOT NULL,
    value_set_id   BIGINT       NOT NULL,
    sent_at        DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_owner_message_sender (sender_subject, sent_at)
);
