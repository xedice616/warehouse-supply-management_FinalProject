-- Align the role model with the security specification: ADMIN, WAREHOUSE_MANAGER, USER.
-- V2 seeded six roles; STOREKEEPER, PURCHASER, AUDITOR and VIEWER are retired.
-- Any user holding a retired role is moved to USER (least privilege); an administrator can grant more.

INSERT INTO role (id, code, description)
VALUES (nextval('role_seq'), 'USER', 'Read-only access to permitted products, stock and warehouses');

UPDATE role SET description = 'Full system administration'
WHERE code = 'ADMIN';

UPDATE role SET description = 'Operates assigned warehouses: stock movements, receiving purchase orders, '
                              || 'inventory audits and warehouse reports'
WHERE code = 'WAREHOUSE_MANAGER';

INSERT INTO user_role (user_id, role_id)
SELECT DISTINCT ur.user_id, (SELECT id FROM role WHERE code = 'USER')
FROM user_role ur
JOIN role r ON r.id = ur.role_id
WHERE r.code IN ('STOREKEEPER', 'PURCHASER', 'AUDITOR', 'VIEWER')
ON CONFLICT DO NOTHING;

DELETE FROM user_role
WHERE role_id IN (SELECT id FROM role WHERE code IN ('STOREKEEPER', 'PURCHASER', 'AUDITOR', 'VIEWER'));

DELETE FROM role
WHERE code IN ('STOREKEEPER', 'PURCHASER', 'AUDITOR', 'VIEWER');
