#!/bin/sh
# Runs once, the first time the PostgreSQL container initializes its data directory.
# Creates the two least-privilege roles and the wms schema; Flyway (run by the app) creates the tables and grants.
# Role passwords come from the environment so no secret is baked into the image or this script.
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<SQL
CREATE ROLE wms_owner LOGIN PASSWORD '${WMS_DB_OWNER_PASSWORD}';
CREATE ROLE wms_app   LOGIN PASSWORD '${WMS_DB_APP_PASSWORD}';
CREATE SCHEMA wms AUTHORIZATION wms_owner;
ALTER ROLE wms_owner SET search_path = wms;
ALTER ROLE wms_app   SET search_path = wms;
SQL
