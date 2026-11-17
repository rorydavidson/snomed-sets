-- A published value set, or one of its versions, is never deleted. It is deprecated instead:
-- kept and still readable, but marked as not to be used, with the reason.
ALTER TABLE value_set
    ADD COLUMN deprecated_at      DATETIME(6)  NULL,
    ADD COLUMN deprecation_reason VARCHAR(500) NULL;

ALTER TABLE value_set_version
    ADD COLUMN deprecated_at      DATETIME(6)  NULL,
    ADD COLUMN deprecation_reason VARCHAR(500) NULL;
