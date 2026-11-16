-- Sample data for development. Loaded only when the dev profile is active.
DELETE FROM value_set WHERE owner_subject = 'sample-data';

INSERT INTO value_set (name, description, ecl, branch, status, visibility, owner_subject, owner_name, created_at, updated_at) VALUES
('Asthma diagnoses', 'All asthma disorders, for registry inclusion.', '<< 195967001 |Asthma|', 'MAIN', 'PUBLISHED', 'COMMUNITY', 'sample-data', 'Sample data', NOW(6), NOW(6)),
('Type 2 diabetes mellitus', 'Type 2 diabetes and its subtypes, for cohort identification.', '<< 44054006 |Diabetes mellitus type 2|', 'MAIN', 'PUBLISHED', 'COMMUNITY', 'sample-data', 'Sample data', NOW(6), NOW(6)),
('Fracture of femur', 'All femoral fractures, for trauma audit reporting.', '<< 71620000 |Fracture of femur|', 'MAIN', 'PUBLISHED', 'COMMUNITY', 'sample-data', 'Sample data', NOW(6), NOW(6)),
('Hypertension excluding pregnancy-related', 'Hypertensive disorders with pregnancy-induced hypertension removed.', '<< 38341003 |Hypertensive disorder| MINUS << 48194001 |Pregnancy-induced hypertension|', 'MAIN', 'DRAFT', 'PRIVATE', 'sample-data', 'Sample data', NOW(6), NOW(6));
