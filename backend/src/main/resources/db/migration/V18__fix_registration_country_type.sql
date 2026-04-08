-- Entity maps registration_country as VARCHAR(2); DB had CHAR(2) (bpchar).
ALTER TABLE legal_entity
    ALTER COLUMN registration_country TYPE VARCHAR(2);
