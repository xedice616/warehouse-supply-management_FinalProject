#!/bin/sh
# Database bootstrap for local development and tests. The postgres image runs this once,
# as the superuser, when the data volume is empty. Passwords come from the environment
# (docker compose reads them from .env); nothing is hardcoded.
#
#   owner role (WMS_DB_OWNER_USER): owns the wms schema; Flyway runs DDL as this role
#   app role   (WMS_DB_APP_USER):   the application at runtime; data access only
#
# In production a DBA provisions the same two roles with managed secrets.

if [ -z "$WMS_DB_OWNER_PASSWORD" ] || [ -z "$WMS_DB_APP_PASSWORD" ]; then
    echo "01-roles.sh: WMS_DB_OWNER_PASSWORD and WMS_DB_APP_PASSWORD must be set" >&2
    exit 1
fi

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     -v owner_user="${WMS_DB_OWNER_USER:-wms_owner}" \
     -v owner_password="$WMS_DB_OWNER_PASSWORD" \
     -v app_user="${WMS_DB_APP_USER:-wms_app}" \
     -v app_password="$WMS_DB_APP_PASSWORD" <<'EOSQL'
CREATE ROLE :"owner_user" LOGIN PASSWORD :'owner_password';
CREATE ROLE :"app_user" LOGIN PASSWORD :'app_password';

CREATE SCHEMA wms AUTHORIZATION :"owner_user";

ALTER ROLE :"owner_user" SET search_path = wms;
ALTER ROLE :"app_user" SET search_path = wms;
EOSQL
