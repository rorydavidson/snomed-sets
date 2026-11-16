-- The code system a value set is built against. Chosen when the set is created and fixed after that.
ALTER TABLE value_set
    ADD COLUMN code_system VARCHAR(100) NOT NULL DEFAULT 'SNOMEDCT' AFTER definition_type;
