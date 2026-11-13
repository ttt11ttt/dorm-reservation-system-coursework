-- Additive migration reviewed for byte-exact identity, null defaults and per-account revocation.
-- Execute once, only after backup and exact schema preflight. No binding/backfill/seed here.
-- 128 bytes is a local acceptance cap: oversized upstream identities fail closed, not truncated.
ALTER TABLE `accounts`
  ADD COLUMN `wechat_app_id` VARBINARY(128) NULL,
  ADD COLUMN `wechat_open_id` VARBINARY(128) NULL,
  ADD COLUMN `wechat_binding_generation` BIGINT NOT NULL DEFAULT 0,
  ADD CONSTRAINT `ck_accounts_wechat_pair` CHECK (
    (`wechat_app_id` IS NULL AND `wechat_open_id` IS NULL)
    OR (`wechat_app_id` IS NOT NULL AND `wechat_open_id` IS NOT NULL
      AND OCTET_LENGTH(`wechat_app_id`) > 0 AND OCTET_LENGTH(`wechat_open_id`) > 0
      AND `account_kind` = '患者')
  ),
  ADD CONSTRAINT `ck_accounts_wechat_generation` CHECK (`wechat_binding_generation` >= 0),
  ADD UNIQUE KEY `uq_accounts_wechat_identity` (`wechat_app_id`, `wechat_open_id`);
