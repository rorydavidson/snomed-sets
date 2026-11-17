-- The sample value sets loaded by the dev profile belong to nobody who can sign in, so they
-- could never be deleted. Remove them once from every existing database. A new development
-- database still gets them, because the seed runs after this.
DELETE FROM value_set WHERE owner_subject = 'sample-data';
