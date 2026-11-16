-- The language of the preferred term stored with each concept, as reported by Snowstorm.
ALTER TABLE value_set_member
    ADD COLUMN preferred_term_language VARCHAR(10) NOT NULL DEFAULT 'en' AFTER preferred_term;
