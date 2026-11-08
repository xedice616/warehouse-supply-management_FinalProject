-- Baseline: shared database infrastructure used by every module.
-- Runs as the schema owner (wms_owner). The application connects as ${app_db_role}.

-- Trigger function for append-only tables (the stock ledger, from stage 4).
-- Any UPDATE or DELETE on a table using it fails, whatever the caller's privileges.
CREATE FUNCTION forbid_modification() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'Table %.% is append-only: % is not allowed', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP;
END
$$;

-- Privileges for the application role on everything the owner creates from now on.
-- Append-only tables later revoke UPDATE and DELETE explicitly.
-- Skipped when the role doesn't exist (e.g. a single-user database).
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${app_db_role}')
       AND '${app_db_role}' <> current_user THEN
        EXECUTE format('GRANT USAGE ON SCHEMA %I TO %I', current_schema(), '${app_db_role}');
        EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA %I GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I',
                       current_schema(), '${app_db_role}');
        EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA %I GRANT USAGE, SELECT ON SEQUENCES TO %I',
                       current_schema(), '${app_db_role}');
    END IF;
END
$$;
