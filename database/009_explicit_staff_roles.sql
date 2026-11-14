-- MySQL 8. Apply once with the old backend stopped.
-- Existing employee grants remain in force. The course visitor needs an explicit grant.
ALTER TABLE account_grants
  DROP CHECK ck_account_grants_1,
  ADD CONSTRAINT ck_account_grants_1 CHECK (
    role_code IN ('前台','牙医','财务','运营人员／店长','总部管理','账号管理')
    OR (role_code='只读访客' AND scope_kind='连锁' AND clinic_id IS NULL)
  );

START TRANSACTION;
INSERT INTO account_grants
  (grant_id,account_id,role_code,scope_kind,clinic_id,active,granted_by,granted_at)
SELECT CAST(CONV(CONCAT('4',LEFT(REPLACE(UUID(),'-',''),15)),16,10) AS SIGNED),
       visitor.account_id,'只读访客','连锁',NULL,1,admin.account_id,CURRENT_TIMESTAMP
FROM accounts visitor
JOIN accounts admin ON admin.login_name='dental_admin' AND admin.account_kind='员工' AND admin.active=1
WHERE visitor.login_name='dental_guest' AND visitor.account_kind='员工' AND visitor.active=1
  AND NOT EXISTS (SELECT 1 FROM account_grants g WHERE g.account_id=visitor.account_id AND g.role_code='只读访客' AND g.active=1)
  AND EXISTS (
    SELECT 1 FROM account_grants g WHERE g.account_id=admin.account_id AND g.role_code='账号管理'
      AND g.scope_kind='连锁' AND g.active=1
  );
COMMIT;

-- Review the actual effective assignments before switching the JAR.
SELECT a.login_name,g.role_code,g.scope_kind,g.clinic_id
FROM accounts a JOIN account_grants g ON g.account_id=a.account_id AND g.active=1
WHERE a.active=1 AND a.account_kind='员工'
ORDER BY a.login_name,g.role_code,g.clinic_id;
