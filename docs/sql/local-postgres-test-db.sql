-- Integration-test database, on the same server as the development database.
-- Requires the wms_owner / wms_app roles from local-postgres-setup.sql.
--
-- 1) As postgres, connected to any database:
CREATE DATABASE warehouse_db_test;

-- 2) As postgres, connected to warehouse_db_test (pgAdmin: refresh, right-click warehouse_db_test > Query Tool):
-- CREATE SCHEMA wms AUTHORIZATION wms_owner;
