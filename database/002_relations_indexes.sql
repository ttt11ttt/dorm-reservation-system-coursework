-- MySQL 8.0.16+ / InnoDB. Run only against a dedicated empty schema.
SET NAMES utf8mb4 COLLATE utf8mb4_0900_as_cs;
SET SESSION time_zone = '+00:00';
SET SESSION sql_mode = 'STRICT_TRANS_TABLES,NO_ZERO_DATE,NO_ZERO_IN_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION';

-- Add all FKs after all tables exist; never disable FOREIGN_KEY_CHECKS.
ALTER TABLE `patient_allergies` ADD CONSTRAINT `fk_patient_allergies_patient_id` FOREIGN KEY (`patient_id`) REFERENCES `patients` (`patient_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `patient_allergies` ADD CONSTRAINT `fk_patient_allergies_verified_by` FOREIGN KEY (`verified_by`) REFERENCES `accounts` (`account_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `accounts` ADD CONSTRAINT `fk_accounts_patient_id` FOREIGN KEY (`patient_id`) REFERENCES `patients` (`patient_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `accounts` ADD CONSTRAINT `fk_accounts_dentist_id` FOREIGN KEY (`dentist_id`) REFERENCES `dentists` (`dentist_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `accounts` ADD CONSTRAINT `fk_accounts_bound_by` FOREIGN KEY (`bound_by`) REFERENCES `accounts` (`account_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `account_grants` ADD CONSTRAINT `fk_account_grants_account_id` FOREIGN KEY (`account_id`) REFERENCES `accounts` (`account_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `account_grants` ADD CONSTRAINT `fk_account_grants_clinic_id` FOREIGN KEY (`clinic_id`) REFERENCES `clinics` (`clinic_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `account_grants` ADD CONSTRAINT `fk_account_grants_granted_by` FOREIGN KEY (`granted_by`) REFERENCES `accounts` (`account_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `audit_logs` ADD CONSTRAINT `fk_audit_logs_actor_account_id` FOREIGN KEY (`actor_account_id`) REFERENCES `accounts` (`account_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `booking_restrictions` ADD CONSTRAINT `fk_booking_restrictions_patient_id` FOREIGN KEY (`patient_id`) REFERENCES `patients` (`patient_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `booking_restrictions` ADD CONSTRAINT `fk_booking_restrictions_revoked_by` FOREIGN KEY (`revoked_by`) REFERENCES `accounts` (`account_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `appointment_breaches` ADD CONSTRAINT `fk_appointment_breaches_appointment_id` FOREIGN KEY (`appointment_id`) REFERENCES `appointments` (`appointment_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `appointment_breaches` ADD CONSTRAINT `fk_appointment_breaches_restriction_id` FOREIGN KEY (`restriction_id`) REFERENCES `booking_restrictions` (`restriction_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `appointment_breaches` ADD CONSTRAINT `fk_appointment_breaches_recorded_by` FOREIGN KEY (`recorded_by`) REFERENCES `accounts` (`account_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `appointment_breaches` ADD CONSTRAINT `fk_appointment_breaches_updated_by` FOREIGN KEY (`updated_by`) REFERENCES `accounts` (`account_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `clinic_openings` ADD CONSTRAINT `fk_clinic_openings_clinic_id` FOREIGN KEY (`clinic_id`) REFERENCES `clinics` (`clinic_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `clinic_openings` ADD CONSTRAINT `fk_clinic_openings_updated_by` FOREIGN KEY (`updated_by`) REFERENCES `accounts` (`account_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `clinic_booking_rules` ADD CONSTRAINT `fk_clinic_booking_rules_clinic_id` FOREIGN KEY (`clinic_id`) REFERENCES `clinics` (`clinic_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `clinic_booking_rules` ADD CONSTRAINT `fk_clinic_booking_rules_updated_by` FOREIGN KEY (`updated_by`) REFERENCES `accounts` (`account_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `dentist_qualifications` ADD CONSTRAINT `fk_dentist_qualifications_dentist_id` FOREIGN KEY (`dentist_id`) REFERENCES `dentists` (`dentist_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `dentist_qualifications` ADD CONSTRAINT `fk_dentist_qualifications_qualification_id` FOREIGN KEY (`qualification_id`) REFERENCES `qualifications` (`qualification_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `dentist_qualifications` ADD CONSTRAINT `fk_dentist_qualifications_verified_by` FOREIGN KEY (`verified_by`) REFERENCES `accounts` (`account_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `dentist_schedules` ADD CONSTRAINT `fk_dentist_schedules_dentist_id` FOREIGN KEY (`dentist_id`) REFERENCES `dentists` (`dentist_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `dentist_schedules` ADD CONSTRAINT `fk_dentist_schedules_clinic_id` FOREIGN KEY (`clinic_id`) REFERENCES `clinics` (`clinic_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `dentist_schedules` ADD CONSTRAINT `fk_dentist_schedules_opening_id` FOREIGN KEY (`opening_id`) REFERENCES `clinic_openings` (`opening_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `appointments` ADD CONSTRAINT `fk_appointments_patient_id` FOREIGN KEY (`patient_id`) REFERENCES `patients` (`patient_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `appointments` ADD CONSTRAINT `fk_appointments_dentist_id` FOREIGN KEY (`dentist_id`) REFERENCES `dentists` (`dentist_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `appointments` ADD CONSTRAINT `fk_appointments_clinic_id` FOREIGN KEY (`clinic_id`) REFERENCES `clinics` (`clinic_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `appointments` ADD CONSTRAINT `fk_appointments_followup_plan_id` FOREIGN KEY (`followup_plan_id`) REFERENCES `followup_plans` (`followup_plan_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `appointments` ADD CONSTRAINT `fk_appointments_schedule_id` FOREIGN KEY (`schedule_id`) REFERENCES `dentist_schedules` (`schedule_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `appointments` ADD CONSTRAINT `fk_appointments_previous_appointment_id` FOREIGN KEY (`previous_appointment_id`) REFERENCES `appointments` (`appointment_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `appointments` ADD CONSTRAINT `fk_appointments_checked_by` FOREIGN KEY (`checked_by`) REFERENCES `accounts` (`account_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `appointments` ADD CONSTRAINT `fk_appointments_created_by` FOREIGN KEY (`created_by`) REFERENCES `accounts` (`account_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `treatments` ADD CONSTRAINT `fk_treatments_appointment_id` FOREIGN KEY (`appointment_id`) REFERENCES `appointments` (`appointment_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `treatments` ADD CONSTRAINT `fk_treatments_treatment_type_id` FOREIGN KEY (`treatment_type_id`) REFERENCES `treatment_types` (`treatment_type_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `treatments` ADD CONSTRAINT `fk_treatments_performer_dentist_id` FOREIGN KEY (`performer_dentist_id`) REFERENCES `dentists` (`dentist_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `treatments` ADD CONSTRAINT `fk_treatments_confirmed_by` FOREIGN KEY (`confirmed_by`) REFERENCES `accounts` (`account_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `invoices` ADD CONSTRAINT `fk_invoices_appointment_id` FOREIGN KEY (`appointment_id`) REFERENCES `appointments` (`appointment_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `invoices` ADD CONSTRAINT `fk_invoices_issued_by` FOREIGN KEY (`issued_by`) REFERENCES `accounts` (`account_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `invoice_lines` ADD CONSTRAINT `fk_invoice_lines_invoice_id` FOREIGN KEY (`invoice_id`) REFERENCES `invoices` (`invoice_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `invoice_lines` ADD CONSTRAINT `fk_invoice_lines_treatment_id` FOREIGN KEY (`treatment_id`) REFERENCES `treatments` (`treatment_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `expenses` ADD CONSTRAINT `fk_expenses_clinic_id` FOREIGN KEY (`clinic_id`) REFERENCES `clinics` (`clinic_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `expenses` ADD CONSTRAINT `fk_expenses_adjusts_expense_id` FOREIGN KEY (`adjusts_expense_id`) REFERENCES `expenses` (`expense_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `expenses` ADD CONSTRAINT `fk_expenses_recorded_by` FOREIGN KEY (`recorded_by`) REFERENCES `accounts` (`account_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `expenses` ADD CONSTRAINT `fk_expenses_confirmed_by` FOREIGN KEY (`confirmed_by`) REFERENCES `accounts` (`account_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `expense_allocations` ADD CONSTRAINT `fk_expense_allocations_expense_id` FOREIGN KEY (`expense_id`) REFERENCES `expenses` (`expense_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `expense_allocations` ADD CONSTRAINT `fk_expense_allocations_dentist_id` FOREIGN KEY (`dentist_id`) REFERENCES `dentists` (`dentist_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `expense_allocations` ADD CONSTRAINT `fk_expense_allocations_treatment_id` FOREIGN KEY (`treatment_id`) REFERENCES `treatments` (`treatment_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `followup_plans` ADD CONSTRAINT `fk_followup_plans_patient_id` FOREIGN KEY (`patient_id`) REFERENCES `patients` (`patient_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `followup_plans` ADD CONSTRAINT `fk_followup_plans_source_appointment_id` FOREIGN KEY (`source_appointment_id`) REFERENCES `appointments` (`appointment_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `followup_plans` ADD CONSTRAINT `fk_followup_plans_dentist_id` FOREIGN KEY (`dentist_id`) REFERENCES `dentists` (`dentist_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `followup_plans` ADD CONSTRAINT `fk_followup_plans_clinic_id` FOREIGN KEY (`clinic_id`) REFERENCES `clinics` (`clinic_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `followup_plans` ADD CONSTRAINT `fk_followup_plans_completed_appointment_id` FOREIGN KEY (`completed_appointment_id`) REFERENCES `appointments` (`appointment_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `followup_contacts` ADD CONSTRAINT `fk_followup_contacts_followup_plan_id` FOREIGN KEY (`followup_plan_id`) REFERENCES `followup_plans` (`followup_plan_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `followup_contacts` ADD CONSTRAINT `fk_followup_contacts_responsible_account_id` FOREIGN KEY (`responsible_account_id`) REFERENCES `accounts` (`account_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `reminder_messages` ADD CONSTRAINT `fk_reminder_messages_patient_id` FOREIGN KEY (`patient_id`) REFERENCES `patients` (`patient_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `reminder_messages` ADD CONSTRAINT `fk_reminder_messages_clinic_id` FOREIGN KEY (`clinic_id`) REFERENCES `clinics` (`clinic_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `reminder_messages` ADD CONSTRAINT `fk_reminder_messages_appointment_id` FOREIGN KEY (`appointment_id`) REFERENCES `appointments` (`appointment_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `reminder_messages` ADD CONSTRAINT `fk_reminder_messages_followup_plan_id` FOREIGN KEY (`followup_plan_id`) REFERENCES `followup_plans` (`followup_plan_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `reminder_messages` ADD CONSTRAINT `fk_reminder_messages_handled_by` FOREIGN KEY (`handled_by`) REFERENCES `accounts` (`account_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `dentist_schedules` ADD CONSTRAINT fk_composite_0 FOREIGN KEY (`opening_id`,`clinic_id`) REFERENCES `clinic_openings` (`opening_id`,`clinic_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `appointments` ADD CONSTRAINT fk_composite_1 FOREIGN KEY (`schedule_id`,`dentist_id`,`clinic_id`) REFERENCES `dentist_schedules` (`schedule_id`,`dentist_id`,`clinic_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `appointments` ADD CONSTRAINT fk_composite_2 FOREIGN KEY (`followup_plan_id`,`patient_id`) REFERENCES `followup_plans` (`followup_plan_id`,`patient_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `appointments` ADD CONSTRAINT fk_composite_3 FOREIGN KEY (`previous_appointment_id`,`patient_id`) REFERENCES `appointments` (`appointment_id`,`patient_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `followup_plans` ADD CONSTRAINT fk_composite_4 FOREIGN KEY (`source_appointment_id`,`patient_id`) REFERENCES `appointments` (`appointment_id`,`patient_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `followup_plans` ADD CONSTRAINT fk_composite_5 FOREIGN KEY (`completed_appointment_id`,`followup_plan_id`,`patient_id`) REFERENCES `appointments` (`appointment_id`,`followup_plan_id`,`patient_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `reminder_messages` ADD CONSTRAINT fk_composite_6 FOREIGN KEY (`followup_plan_id`,`patient_id`,`clinic_id`) REFERENCES `followup_plans` (`followup_plan_id`,`patient_id`,`clinic_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `reminder_messages` ADD CONSTRAINT fk_composite_7 FOREIGN KEY (`appointment_id`,`patient_id`) REFERENCES `appointments` (`appointment_id`,`patient_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
CREATE INDEX ix_report_0 ON `patients` (`phone`,`name`,`birth_date`); -- 候选患者查找，非唯一身份判定
CREATE INDEX ix_report_1 ON `appointments` (`dentist_id`,`scheduled_start`,`appointment_status`); -- 牙医日程及冲突候选，非完整区间排斥约束
CREATE INDEX ix_report_2 ON `appointments` (`patient_id`,`scheduled_start`,`appointment_status`); -- 本人预约及冲突查询
CREATE INDEX ix_report_3 ON `appointments` (`clinic_id`,`scheduled_start`,`appointment_status`); -- 门店到诊工作台
CREATE INDEX ix_report_4 ON `dentist_schedules` (`dentist_id`,`start_at`,`end_at`); -- 查询跨店出诊和区间覆盖
CREATE INDEX ix_report_5 ON `expenses` (`occurred_on`,`status`,`kind`); -- 季度费用来源筛选
CREATE INDEX ix_report_6 ON `audit_logs` (`object_type`,`object_id`,`happened_at`); -- 业务历史追溯
CREATE INDEX ix_report_7 ON `treatments` (`appointment_id`,`status`); -- 按就诊读取实际确认治疗
CREATE INDEX ix_report_8 ON `invoices` (`issued_at`,`status`); -- 已发出结算查询，收入按对应就诊完成日期归季
CREATE INDEX ix_report_9 ON `expense_allocations` (`dentist_id`,`expense_id`); -- 牙医季度成本归属
CREATE INDEX ix_report_10 ON `clinic_openings` (`clinic_id`,`start_at`,`end_at`); -- 按实际开放区间覆盖出诊
CREATE INDEX ix_report_11 ON `reminder_messages` (`send_status`,`planned_at`); -- 到期提醒发送及失败处理
CREATE INDEX ix_report_12 ON `appointment_breaches` (`occurred_at`,`valid`,`exempt`,`restriction_id`); -- 近30天未消费有效事件累计，结合预约患者检索
CREATE INDEX ix_report_13 ON `booking_restrictions` (`patient_id`,`status`,`ends_at`); -- 患者自助新增预约限制判定
CREATE INDEX ix_report_14 ON `followup_plans` (`clinic_id`,`status`,`suggested_date`); -- 复诊提醒及逾期跟进
CREATE INDEX ix_report_15 ON `appointments` (`followup_plan_id`,`appointment_status`,`treatment_status`); -- 复诊计划下历史及有效预约现状

-- Functional keys preserve the 250 visible columns; inactive history yields NULL.
CREATE UNIQUE INDEX uq_allergy_active ON patient_allergies (patient_id, (CASE WHEN active = 1 THEN LOWER(TRIM(substance)) ELSE NULL END));
CREATE UNIQUE INDEX uq_grant_active ON account_grants (account_id, role_code, scope_kind, (CASE WHEN active = 1 THEN COALESCE(clinic_id,0) ELSE NULL END));
CREATE UNIQUE INDEX uq_reminder_appointment ON reminder_messages (appointment_id, reminder_kind, channel, planned_at);
CREATE UNIQUE INDEX uq_reminder_followup ON reminder_messages (followup_plan_id, reminder_kind, channel, planned_at);

