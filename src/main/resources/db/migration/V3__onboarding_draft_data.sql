-- Keep wizard inputs, including upload IDs, alongside the published relational data.
ALTER TABLE businesses ADD COLUMN onboarding_data jsonb NOT NULL DEFAULT '{}';
