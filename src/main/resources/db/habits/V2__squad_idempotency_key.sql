-- A squad can be created with an optional Idempotency-Key, so a create replayed after a cell failover
-- makes the squad once. squads already records its creator (created_by), so the key is kept there and
-- is unique per creator; squads made without one have none, and any number of NULLs are allowed, so
-- existing rows are untouched.

ALTER TABLE squads ADD COLUMN idempotency_key text;
ALTER TABLE squads ADD CONSTRAINT squads_created_by_idempotency_key_key UNIQUE (created_by, idempotency_key);
