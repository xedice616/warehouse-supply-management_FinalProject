-- Run once as the postgres superuser, inside the database the application will use
-- (pgAdmin: right-click the database > Query Tool > F5).
-- Passwords must match WMS_DB_OWNER_PASSWORD and WMS_DB_APP_PASSWORD in .env. Tables are created by Flyway.

CREATE ROLE wms_owner LOGIN PASSWORD 'CHANGE_ME_OWNER_PASSWORD';
CREATE ROLE wms_app LOGIN PASSWORD 'CHANGE_ME_APP_PASSWORD';

CREATE SCHEMA wms AUTHORIZATION wms_owner;

ALTER ROLE wms_owner SET search_path = wms;
ALTER ROLE wms_app SET search_path = wms;
