-- MySQL 8.0.16+ / InnoDB. Run only against a dedicated empty schema.
SET NAMES utf8mb4 COLLATE utf8mb4_0900_as_cs;
SET SESSION time_zone = '+00:00';
SET SESSION sql_mode = 'STRICT_TRANS_TABLES,NO_ZERO_DATE,NO_ZERO_IN_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION';

CREATE TABLE `patients` (
  `patient_id` BIGINT NOT NULL COMMENT '患者内部标识',
  `patient_no` VARCHAR(32) NOT NULL COMMENT '跨店统一患者编号，不是登录名',
  `name` VARCHAR(80) NOT NULL COMMENT '姓名',
  `birth_date` DATE NOT NULL COMMENT '出生日期',
  `billing_address` VARCHAR(255) NOT NULL COMMENT '账单地址',
  `phone` VARCHAR(32) NOT NULL COMMENT '联系电话，允许家属共用',
  `email` VARCHAR(254) NULL COMMENT '可选邮箱',
  `emergency_name` VARCHAR(80) NOT NULL COMMENT '紧急联系人姓名',
  `emergency_phone` VARCHAR(32) NOT NULL COMMENT '紧急联系人电话',
  `allergy_declaration` VARCHAR(20) NOT NULL COMMENT '未询问／无已知过敏／已报告过敏',
  `allergy_version` INT NOT NULL DEFAULT 0 COMMENT '过敏申报版本，初始0，变更时递增',
  `active` BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否启用，默认TRUE',
  `created_at` TIMESTAMP NOT NULL COMMENT '建立时间',
  `row_version` INT NOT NULL DEFAULT 0 COMMENT '并发修改版本，初始0',
  PRIMARY KEY (`patient_id`),
  UNIQUE KEY `uq_patients_patient_no` (`patient_no`),
  CONSTRAINT `ck_patients_0` CHECK (allergy_version >= 0),
  CONSTRAINT `ck_patients_1` CHECK (`allergy_declaration` IN ('未询问','无已知过敏','已报告过敏')),
  CONSTRAINT `ck_patients_2` CHECK (`active` IN (0,1)),
  CONSTRAINT `ck_patients_3` CHECK (row_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='患者主档';

CREATE TABLE `patient_allergies` (
  `allergy_id` BIGINT NOT NULL COMMENT '过敏项标识',
  `patient_id` BIGINT NOT NULL COMMENT '所属患者',
  `substance` VARCHAR(100) NOT NULL COMMENT '过敏物质或类别',
  `reaction_note` VARCHAR(255) NULL COMMENT '患者报告的反应及补充说明',
  `active` BOOLEAN NOT NULL DEFAULT TRUE COMMENT '当前有效项',
  `verification_status` VARCHAR(16) NOT NULL COMMENT '患者申报／已核实／已更正',
  `verified_by` BIGINT NULL COMMENT '核实员工账号',
  `verified_at` TIMESTAMP NULL DEFAULT NULL COMMENT '核实时间',
  `updated_at` TIMESTAMP NOT NULL COMMENT '最近变更时间',
  PRIMARY KEY (`allergy_id`),
  CONSTRAINT `ck_patient_allergies_0` CHECK (verification_status <> '已核实' OR (verified_by IS NOT NULL AND verified_at IS NOT NULL)),
  CONSTRAINT `ck_patient_allergies_1` CHECK (CHAR_LENGTH(TRIM(substance)) > 0),
  CONSTRAINT `ck_patient_allergies_2` CHECK (`verification_status` IN ('患者申报','已核实','已更正')),
  CONSTRAINT `ck_patient_allergies_3` CHECK (`active` IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='患者过敏项';

CREATE TABLE `accounts` (
  `account_id` BIGINT NOT NULL COMMENT '认证账号标识',
  `login_name` VARCHAR(64) NOT NULL COMMENT '登录名',
  `account_kind` VARCHAR(16) NOT NULL COMMENT '患者／员工',
  `patient_id` BIGINT NULL COMMENT '患者账号绑定的本人主档',
  `dentist_id` BIGINT NULL COMMENT '牙医员工对应人员，其他员工可空',
  `display_name` VARCHAR(80) NOT NULL COMMENT '显示名',
  `credential_ref` VARCHAR(255) NOT NULL COMMENT '身份服务凭据引用或安全哈希',
  `binding_method` VARCHAR(32) NULL COMMENT '既有患者绑定的核验方式，不保存验证码',
  `bound_by` BIGINT NULL COMMENT '完成绑定的操作者',
  `bound_at` TIMESTAMP NULL DEFAULT NULL COMMENT '绑定完成时间',
  `active` BOOLEAN NOT NULL DEFAULT TRUE COMMENT '启用标记，默认TRUE',
  `created_at` TIMESTAMP NOT NULL COMMENT '开户时间',
  PRIMARY KEY (`account_id`),
  UNIQUE KEY `uq_accounts_login_name` (`login_name`),
  UNIQUE KEY `uq_accounts_patient_id` (`patient_id`),
  UNIQUE KEY `uq_accounts_dentist_id` (`dentist_id`),
  CONSTRAINT `ck_accounts_0` CHECK ((account_kind = '患者' AND patient_id IS NOT NULL AND dentist_id IS NULL AND binding_method IS NOT NULL AND bound_by IS NOT NULL AND bound_at IS NOT NULL) OR (account_kind = '员工' AND patient_id IS NULL)),
  CONSTRAINT `ck_accounts_1` CHECK (`account_kind` IN ('患者','员工')),
  CONSTRAINT `ck_accounts_2` CHECK (`active` IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='认证账号';

CREATE TABLE `account_grants` (
  `grant_id` BIGINT NOT NULL COMMENT '授权标识',
  `account_id` BIGINT NOT NULL COMMENT '被授权员工账号',
  `role_code` VARCHAR(24) NOT NULL COMMENT '前台；牙医；财务；运营人员／店长；总部管理；账号管理',
  `scope_kind` VARCHAR(8) NOT NULL COMMENT '门店／连锁',
  `clinic_id` BIGINT NULL COMMENT '门店范围对应门店，连锁授权为空',
  `active` BOOLEAN NOT NULL DEFAULT TRUE COMMENT '授权是否有效',
  `granted_by` BIGINT NOT NULL COMMENT '授权操作者',
  `granted_at` TIMESTAMP NOT NULL COMMENT '授权时间',
  PRIMARY KEY (`grant_id`),
  CONSTRAINT `ck_account_grants_0` CHECK ((scope_kind = '门店' AND clinic_id IS NOT NULL) OR (scope_kind = '连锁' AND clinic_id IS NULL)),
  CONSTRAINT `ck_account_grants_1` CHECK (`role_code` IN ('前台','牙医','财务','运营人员／店长','总部管理','账号管理')),
  CONSTRAINT `ck_account_grants_2` CHECK (`scope_kind` IN ('门店','连锁')),
  CONSTRAINT `ck_account_grants_3` CHECK (`active` IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='员工角色与范围';

CREATE TABLE `audit_logs` (
  `audit_id` BIGINT NOT NULL COMMENT '审计记录标识',
  `actor_account_id` BIGINT NOT NULL COMMENT '操作账号',
  `action` VARCHAR(32) NOT NULL COMMENT '读取、导出、创建、修改、确认或更正等动作',
  `object_type` VARCHAR(40) NOT NULL COMMENT '业务对象类型',
  `object_id` VARCHAR(80) NOT NULL COMMENT '业务对象定位值，不是通用外键',
  `reason` TEXT NULL COMMENT '变更原因',
  `before_value` TEXT NULL COMMENT '变更前内容',
  `after_value` TEXT NULL COMMENT '变更后内容',
  `happened_at` TIMESTAMP NOT NULL COMMENT '发生时间',
  PRIMARY KEY (`audit_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='操作审计';

CREATE TABLE `booking_restrictions` (
  `restriction_id` BIGINT NOT NULL COMMENT '限制标识',
  `patient_id` BIGINT NOT NULL COMMENT '被限制患者',
  `starts_at` TIMESTAMP NOT NULL COMMENT '限制开始时刻',
  `ends_at` TIMESTAMP NOT NULL COMMENT 'starts_at加14天；到达此时刻恢复自助',
  `status` VARCHAR(16) NOT NULL COMMENT 'active／expired／revoked',
  `reason` TEXT NOT NULL COMMENT '触发或撤销原因',
  `created_at` TIMESTAMP NOT NULL COMMENT '建立时间',
  `revoked_by` BIGINT NULL COMMENT '纠正后撤销操作人',
  `revoked_at` TIMESTAMP NULL DEFAULT NULL COMMENT '撤销时间',
  PRIMARY KEY (`restriction_id`),
  CONSTRAINT `ck_booking_restrictions_0` CHECK (starts_at < ends_at),
  CONSTRAINT `ck_booking_restrictions_1` CHECK (status <> 'revoked' OR (revoked_by IS NOT NULL AND revoked_at IS NOT NULL)),
  CONSTRAINT `ck_booking_restrictions_2` CHECK (`status` IN ('active','expired','revoked'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='自助新增预约限制';

CREATE TABLE `appointment_breaches` (
  `breach_id` BIGINT NOT NULL COMMENT '违约事件标识',
  `appointment_id` BIGINT NOT NULL COMMENT '同一预约至多一条违约事件',
  `breach_kind` VARCHAR(24) NOT NULL COMMENT 'late_cancel／late_reschedule／no_show',
  `occurred_at` TIMESTAMP NOT NULL COMMENT '发生时刻；临近变更取成功提交时，未到诊取原预约结束时',
  `valid` BOOLEAN NOT NULL COMMENT '事件是否有效，纠正误判后为FALSE',
  `exempt` BOOLEAN NOT NULL COMMENT '是否获豁免',
  `responsibility_reason` TEXT NOT NULL COMMENT '患者责任、豁免或纠正依据',
  `restriction_id` BIGINT NULL COMMENT '已用于触发的限制，未消费时为空',
  `recorded_by` BIGINT NOT NULL COMMENT '登记账号；自助取本人，代办或核实取员工',
  `updated_by` BIGINT NOT NULL COMMENT '最近办理账号；初次登记同recorded_by',
  `updated_at` TIMESTAMP NOT NULL COMMENT '最近处理时间',
  PRIMARY KEY (`breach_id`),
  UNIQUE KEY `uq_appointment_breaches_appointment_id` (`appointment_id`),
  CONSTRAINT `ck_appointment_breaches_0` CHECK (`breach_kind` IN ('late_cancel','late_reschedule','no_show')),
  CONSTRAINT `ck_appointment_breaches_1` CHECK (`valid` IN (0,1)),
  CONSTRAINT `ck_appointment_breaches_2` CHECK (`exempt` IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='预约违约事件';

CREATE TABLE `clinics` (
  `clinic_id` BIGINT NOT NULL COMMENT '诊所标识',
  `clinic_code` VARCHAR(32) NOT NULL COMMENT '门店代码',
  `name` VARCHAR(100) NOT NULL COMMENT '门店名称',
  `postal_address` VARCHAR(255) NOT NULL COMMENT '邮政地址',
  `manager_name` VARCHAR(80) NOT NULL COMMENT '主管姓名',
  `phone` VARCHAR(32) NOT NULL COMMENT '联系电话',
  `email` VARCHAR(254) NOT NULL COMMENT '邮箱',
  `area_m2` DECIMAL(10,2) NOT NULL COMMENT '办公面积，平方米',
  `active` BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否营业启用',
  PRIMARY KEY (`clinic_id`),
  UNIQUE KEY `uq_clinics_clinic_code` (`clinic_code`),
  CONSTRAINT `ck_clinics_0` CHECK (area_m2 > 0),
  CONSTRAINT `ck_clinics_1` CHECK (`active` IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='诊所';

CREATE TABLE `clinic_openings` (
  `opening_id` BIGINT NOT NULL COMMENT '开放区间标识',
  `clinic_id` BIGINT NOT NULL COMMENT '所属门店',
  `start_at` TIMESTAMP NOT NULL COMMENT '实际开放开始时间',
  `end_at` TIMESTAMP NOT NULL COMMENT '实际开放结束时间',
  `status` VARCHAR(16) NOT NULL COMMENT '有效／撤销',
  `note` TEXT NULL COMMENT '晚间、周末或临时调整说明',
  `updated_by` BIGINT NOT NULL COMMENT '维护运营人员／店长',
  `updated_at` TIMESTAMP NOT NULL COMMENT '最近维护时间',
  PRIMARY KEY (`opening_id`),
  UNIQUE KEY `uq_clinic_openings_rel0` (`opening_id`,`clinic_id`),
  CONSTRAINT `ck_clinic_openings_0` CHECK (start_at < end_at),
  CONSTRAINT `ck_clinic_openings_1` CHECK (`status` IN ('有效','撤销'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='门店实际开放区间';

CREATE TABLE `clinic_booking_rules` (
  `rule_id` BIGINT NOT NULL COMMENT '配置标识',
  `clinic_id` BIGINT NOT NULL COMMENT '一店一份当前配置',
  `appointment_lead_minutes` INT NULL COMMENT '到诊提醒提前分钟数，由操作员设置',
  `followup_lead_days` INT NULL COMMENT '复诊提醒距建议日期的提前天数，由操作员设置',
  `followup_send_time` TIME NULL COMMENT '复诊提醒发送时分，由门店配置，按北京时间解释',
  `in_app_enabled` BOOLEAN NOT NULL COMMENT '站内提醒是否启用',
  `sms_enabled` BOOLEAN NOT NULL COMMENT '短信提醒是否启用',
  `updated_by` BIGINT NOT NULL COMMENT '配置人员',
  `updated_at` TIMESTAMP NOT NULL COMMENT '最近配置时间',
  PRIMARY KEY (`rule_id`),
  UNIQUE KEY `uq_clinic_booking_rules_clinic_id` (`clinic_id`),
  CONSTRAINT `ck_clinic_booking_rules_0` CHECK (appointment_lead_minutes IS NULL OR appointment_lead_minutes >= 0),
  CONSTRAINT `ck_clinic_booking_rules_1` CHECK (followup_lead_days IS NULL OR followup_lead_days >= 0),
  CONSTRAINT `ck_clinic_booking_rules_2` CHECK ((followup_lead_days IS NULL) = (followup_send_time IS NULL)),
  CONSTRAINT `ck_clinic_booking_rules_3` CHECK (followup_send_time IS NULL OR (followup_send_time >= '00:00:00' AND followup_send_time < '24:00:00')),
  CONSTRAINT `ck_clinic_booking_rules_4` CHECK (`in_app_enabled` IN (0,1)),
  CONSTRAINT `ck_clinic_booking_rules_5` CHECK (`sms_enabled` IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='门店预约与提醒配置';

CREATE TABLE `dentists` (
  `dentist_id` BIGINT NOT NULL COMMENT '牙医标识',
  `employee_no` VARCHAR(32) NOT NULL COMMENT '员工编号',
  `name` VARCHAR(80) NOT NULL COMMENT '姓名',
  `birth_date` DATE NOT NULL COMMENT '出生日期',
  `home_address` VARCHAR(255) NOT NULL COMMENT '家庭住址',
  `phone` VARCHAR(32) NOT NULL COMMENT '电话号码',
  `email` VARCHAR(254) NOT NULL COMMENT '电子邮箱',
  `active` BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否启用',
  PRIMARY KEY (`dentist_id`),
  UNIQUE KEY `uq_dentists_employee_no` (`employee_no`),
  CONSTRAINT `ck_dentists_0` CHECK (`active` IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='牙医';

CREATE TABLE `qualifications` (
  `qualification_id` BIGINT NOT NULL COMMENT '资质类别标识',
  `name` VARCHAR(100) NOT NULL COMMENT '资质名称',
  `active` BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否启用',
  PRIMARY KEY (`qualification_id`),
  UNIQUE KEY `uq_qualifications_name` (`name`),
  CONSTRAINT `ck_qualifications_0` CHECK (`active` IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='专业资质';

CREATE TABLE `dentist_qualifications` (
  `holding_id` BIGINT NOT NULL COMMENT '持证记录标识',
  `dentist_id` BIGINT NOT NULL COMMENT '持证牙医',
  `qualification_id` BIGINT NOT NULL COMMENT '资质类别',
  `certificate_no` VARCHAR(80) NULL COMMENT '证书编号',
  `valid_from` DATE NOT NULL COMMENT '生效日期',
  `valid_until` DATE NULL COMMENT '失效日期，为空表示未设到期日',
  `status` VARCHAR(16) NOT NULL COMMENT '待核实／有效／失效',
  `verified_by` BIGINT NULL COMMENT '核实员工',
  `verified_at` TIMESTAMP NULL DEFAULT NULL COMMENT '核实时间',
  PRIMARY KEY (`holding_id`),
  UNIQUE KEY `uq_dentist_qualifications_rel0` (`dentist_id`,`qualification_id`,`valid_from`),
  CONSTRAINT `ck_dentist_qualifications_0` CHECK (valid_until IS NULL OR valid_until >= valid_from),
  CONSTRAINT `ck_dentist_qualifications_1` CHECK (status <> '有效' OR (verified_by IS NOT NULL AND verified_at IS NOT NULL)),
  CONSTRAINT `ck_dentist_qualifications_2` CHECK (`status` IN ('待核实','有效','失效'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='牙医持有资质';

CREATE TABLE `dentist_schedules` (
  `schedule_id` BIGINT NOT NULL COMMENT '出诊安排标识',
  `dentist_id` BIGINT NOT NULL COMMENT '出诊牙医',
  `clinic_id` BIGINT NOT NULL COMMENT '出诊门店',
  `opening_id` BIGINT NOT NULL COMMENT '完整覆盖该出诊段的同店实际开放区间',
  `start_at` TIMESTAMP NOT NULL COMMENT '开始时间',
  `end_at` TIMESTAMP NOT NULL COMMENT '结束时间',
  `status` VARCHAR(16) NOT NULL COMMENT '有效／撤销',
  `row_version` INT NOT NULL DEFAULT 0 COMMENT '并发修改版本',
  PRIMARY KEY (`schedule_id`),
  UNIQUE KEY `uq_dentist_schedules_rel0` (`schedule_id`,`dentist_id`,`clinic_id`),
  CONSTRAINT `ck_dentist_schedules_0` CHECK (start_at < end_at),
  CONSTRAINT `ck_dentist_schedules_1` CHECK (`status` IN ('有效','撤销')),
  CONSTRAINT `ck_dentist_schedules_2` CHECK (row_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='跨店出诊安排';

CREATE TABLE `appointments` (
  `appointment_id` BIGINT NOT NULL COMMENT '预约及本次就诊标识',
  `appointment_no` VARCHAR(32) NOT NULL COMMENT '对外预约编号',
  `patient_id` BIGINT NOT NULL COMMENT '就诊患者',
  `booking_channel` VARCHAR(8) NOT NULL COMMENT '自助／电话／柜台，电话及柜台由前台代办',
  `dentist_id` BIGINT NOT NULL COMMENT '本次接诊牙医',
  `clinic_id` BIGINT NOT NULL COMMENT '本次就诊门店',
  `followup_plan_id` BIGINT NULL COMMENT '后续预约关联的复诊计划，普通预约为空',
  `schedule_id` BIGINT NOT NULL COMMENT '覆盖本次预约的出诊安排',
  `scheduled_start` TIMESTAMP NOT NULL COMMENT '预约开始时间',
  `scheduled_end` TIMESTAMP NOT NULL COMMENT '预约结束时间',
  `requested_treatments` TEXT NOT NULL COMMENT '期望治疗及主诉需求，不直接计费',
  `special_needs` TEXT NULL COMMENT '本次问题或特殊需求',
  `attended` BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否实际到诊，默认FALSE',
  `arrived_at` TIMESTAMP NULL DEFAULT NULL COMMENT '实际到诊时间',
  `appointment_status` VARCHAR(24) NOT NULL COMMENT '待到诊／已到诊／患者取消／诊所撤销／已改约／待核实／未到诊／误录作废',
  `treatment_status` VARCHAR(16) NOT NULL COMMENT '未开始／进行中／已完成，默认未开始',
  `previous_appointment_id` BIGINT NULL COMMENT '改约指向原预约',
  `cancelled_at` TIMESTAMP NULL DEFAULT NULL COMMENT '患者取消或诊所撤销时间',
  `change_reason` TEXT NULL COMMENT '取消、改约、未到诊核实或更正原因',
  `allergy_checked_version` INT NULL COMMENT '本次核对的患者过敏版本',
  `allergy_snapshot` TEXT NULL COMMENT '本次核对后的过敏逐项及申报结果快照',
  `checked_by` BIGINT NULL COMMENT '诊前核对牙医账号',
  `checked_at` TIMESTAMP NULL DEFAULT NULL COMMENT '诊前核对时间',
  `completed_at` TIMESTAMP NULL DEFAULT NULL COMMENT '诊疗完成时间',
  `completion_note` TEXT NULL COMMENT '牙医完成就诊时填写的处理说明；零金额或无实际治疗时必填，作为零金额结算原因来源',
  `created_by` BIGINT NOT NULL COMMENT '患者自助或前台代约操作者',
  `created_at` TIMESTAMP NOT NULL COMMENT '创建时间',
  `row_version` INT NOT NULL DEFAULT 0 COMMENT '并发修改版本，初始0',
  PRIMARY KEY (`appointment_id`),
  UNIQUE KEY `uq_appointments_appointment_no` (`appointment_no`),
  UNIQUE KEY `uq_appointments_previous_appointment_id` (`previous_appointment_id`),
  UNIQUE KEY `uq_appointments_rel0` (`appointment_id`,`patient_id`),
  UNIQUE KEY `uq_appointments_rel1` (`appointment_id`,`followup_plan_id`,`patient_id`),
  CONSTRAINT `ck_appointments_0` CHECK (scheduled_start < scheduled_end),
  CONSTRAINT `ck_appointments_1` CHECK ((appointment_status = '已到诊' AND attended = TRUE AND arrived_at IS NOT NULL) OR (appointment_status <> '已到诊' AND attended = FALSE AND arrived_at IS NULL)),
  CONSTRAINT `ck_appointments_2` CHECK (treatment_status = '未开始' OR appointment_status = '已到诊'),
  CONSTRAINT `ck_appointments_3` CHECK ((treatment_status = '已完成' AND completed_at IS NOT NULL AND completion_note IS NOT NULL AND CHAR_LENGTH(TRIM(completion_note)) > 0) OR (treatment_status <> '已完成' AND completed_at IS NULL)),
  CONSTRAINT `ck_appointments_4` CHECK ((allergy_checked_version IS NULL AND allergy_snapshot IS NULL AND checked_by IS NULL AND checked_at IS NULL) OR (allergy_checked_version IS NOT NULL AND allergy_checked_version >= 0 AND allergy_snapshot IS NOT NULL AND checked_by IS NOT NULL AND checked_at IS NOT NULL)),
  CONSTRAINT `ck_appointments_5` CHECK (treatment_status = '未开始' OR checked_by IS NOT NULL),
  CONSTRAINT `ck_appointments_6` CHECK (previous_appointment_id IS NULL OR previous_appointment_id <> appointment_id),
  CONSTRAINT `ck_appointments_7` CHECK (appointment_status NOT IN ('患者取消','诊所撤销','已改约','未到诊','误录作废') OR (change_reason IS NOT NULL AND CHAR_LENGTH(TRIM(change_reason)) > 0)),
  CONSTRAINT `ck_appointments_8` CHECK (appointment_status NOT IN ('患者取消','诊所撤销') OR cancelled_at IS NOT NULL),
  CONSTRAINT `ck_appointments_9` CHECK (`booking_channel` IN ('自助','电话','柜台')),
  CONSTRAINT `ck_appointments_10` CHECK (`appointment_status` IN ('待到诊','已到诊','患者取消','诊所撤销','已改约','待核实','未到诊','误录作废')),
  CONSTRAINT `ck_appointments_11` CHECK (`treatment_status` IN ('未开始','进行中','已完成')),
  CONSTRAINT `ck_appointments_12` CHECK (`attended` IN (0,1)),
  CONSTRAINT `ck_appointments_13` CHECK (row_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='预约及就诊';

CREATE TABLE `treatment_types` (
  `treatment_type_id` BIGINT NOT NULL COMMENT '项目标识',
  `treatment_code` VARCHAR(32) NOT NULL COMMENT '标准项目代码',
  `name` VARCHAR(100) NOT NULL COMMENT '治疗名称',
  `unit_name` VARCHAR(24) NOT NULL COMMENT '标准计量单位',
  `standard_unit_price` DECIMAL(12,2) NOT NULL COMMENT '现行标准单位价',
  `allows_fraction` BOOLEAN NOT NULL COMMENT '标准单位是否允许小数',
  `active` BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否启用',
  PRIMARY KEY (`treatment_type_id`),
  UNIQUE KEY `uq_treatment_types_treatment_code` (`treatment_code`),
  CONSTRAINT `ck_treatment_types_0` CHECK (standard_unit_price >= 0),
  CONSTRAINT `ck_treatment_types_1` CHECK (`allows_fraction` IN (0,1)),
  CONSTRAINT `ck_treatment_types_2` CHECK (`active` IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='治疗项目目录';

CREATE TABLE `treatments` (
  `treatment_id` BIGINT NOT NULL COMMENT '实际治疗标识',
  `appointment_id` BIGINT NOT NULL COMMENT '所属本次就诊',
  `treatment_type_id` BIGINT NOT NULL COMMENT '实施的项目',
  `created_at` TIMESTAMP NOT NULL COMMENT '首次建立时间',
  `performer_dentist_id` BIGINT NOT NULL COMMENT '本次执行牙医及收入归属',
  `name_snapshot` VARCHAR(100) NOT NULL COMMENT '治疗名称快照',
  `unit_snapshot` VARCHAR(24) NOT NULL COMMENT '单位名称快照',
  `units` DECIMAL(10,2) NOT NULL COMMENT '实际标准单位数',
  `unit_price` DECIMAL(12,2) NOT NULL COMMENT '本次标准单位价格快照',
  `performed_at` TIMESTAMP NOT NULL COMMENT '实际实施时间',
  `status` VARCHAR(16) NOT NULL COMMENT '草稿／确认／作废',
  `confirmed_by` BIGINT NULL COMMENT '签署牙医账号',
  `confirmed_at` TIMESTAMP NULL DEFAULT NULL COMMENT '签署确认时间',
  `note` TEXT NULL COMMENT '实际治疗说明',
  PRIMARY KEY (`treatment_id`),
  CONSTRAINT `ck_treatments_0` CHECK (units > 0),
  CONSTRAINT `ck_treatments_1` CHECK (unit_price >= 0),
  CONSTRAINT `ck_treatments_2` CHECK (status <> '确认' OR (confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL)),
  CONSTRAINT `ck_treatments_3` CHECK (`status` IN ('草稿','确认','作废'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='实际治疗';

CREATE TABLE `invoices` (
  `invoice_id` BIGINT NOT NULL COMMENT '结算单标识',
  `invoice_no` VARCHAR(32) NOT NULL COMMENT '唯一结算单号',
  `appointment_id` BIGINT NOT NULL COMMENT '对应本次就诊',
  `created_at` TIMESTAMP NOT NULL COMMENT '首次生成时间',
  `status` VARCHAR(16) NOT NULL COMMENT '草稿／发出／作废',
  `total_amount` DECIMAL(14,2) NOT NULL COMMENT '本单总额，等于明细小计之和',
  `zero_amount_reason` TEXT NULL COMMENT '零金额原因，开单时完整复制牙医completion_note，不截断',
  `issued_by` BIGINT NULL COMMENT '发出员工账号',
  `issued_at` TIMESTAMP NULL DEFAULT NULL COMMENT '发出时间',
  PRIMARY KEY (`invoice_id`),
  UNIQUE KEY `uq_invoices_invoice_no` (`invoice_no`),
  UNIQUE KEY `uq_invoices_appointment_id` (`appointment_id`),
  CONSTRAINT `ck_invoices_0` CHECK (total_amount >= 0),
  CONSTRAINT `ck_invoices_1` CHECK (status <> '发出' OR (issued_by IS NOT NULL AND issued_at IS NOT NULL)),
  CONSTRAINT `ck_invoices_2` CHECK (status <> '发出' OR total_amount <> 0 OR (zero_amount_reason IS NOT NULL AND CHAR_LENGTH(TRIM(zero_amount_reason)) > 0)),
  CONSTRAINT `ck_invoices_3` CHECK (`status` IN ('草稿','发出','作废'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='就诊结算单';

CREATE TABLE `invoice_lines` (
  `invoice_line_id` BIGINT NOT NULL COMMENT '结算明细标识',
  `invoice_id` BIGINT NOT NULL COMMENT '所属结算单',
  `line_no` INT NOT NULL COMMENT '结算单内行号',
  `treatment_id` BIGINT NOT NULL COMMENT '来源实际治疗',
  `name_snapshot` VARCHAR(100) NOT NULL COMMENT '项目名称快照',
  `unit_snapshot` VARCHAR(24) NOT NULL COMMENT '计量单位快照',
  `units_snapshot` DECIMAL(10,2) NOT NULL COMMENT '单位数量快照',
  `unit_price_snapshot` DECIMAL(12,2) NOT NULL COMMENT '单价快照',
  `subtotal` DECIMAL(14,2) NOT NULL COMMENT '行小计',
  PRIMARY KEY (`invoice_line_id`),
  UNIQUE KEY `uq_invoice_lines_rel0` (`invoice_id`,`line_no`),
  UNIQUE KEY `uq_invoice_lines_rel1` (`invoice_id`,`treatment_id`),
  UNIQUE KEY `uq_invoice_lines_rel2` (`treatment_id`),
  CONSTRAINT `ck_invoice_lines_0` CHECK (line_no > 0),
  CONSTRAINT `ck_invoice_lines_1` CHECK (units_snapshot > 0),
  CONSTRAINT `ck_invoice_lines_2` CHECK (unit_price_snapshot >= 0),
  CONSTRAINT `ck_invoice_lines_3` CHECK (subtotal = ROUND(units_snapshot * unit_price_snapshot,2))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='结算明细';

CREATE TABLE `expenses` (
  `expense_id` BIGINT NOT NULL COMMENT '费用来源标识',
  `document_no` VARCHAR(64) NOT NULL COMMENT '来源凭证号',
  `line_no` INT NOT NULL COMMENT '凭证行号',
  `kind` VARCHAR(16) NOT NULL COMMENT '医疗用品／运营费',
  `clinic_id` BIGINT NOT NULL COMMENT '费用发生门店',
  `occurred_on` DATE NOT NULL COMMENT '费用发生日期',
  `amount` DECIMAL(14,2) NOT NULL COMMENT '原费用或更正差额',
  `status` VARCHAR(16) NOT NULL COMMENT '待分摊／待确认／确认／作废',
  `adjusts_expense_id` BIGINT NULL COMMENT '差额更正指向原费用',
  `reason` TEXT NULL COMMENT '业务依据或更正原因',
  `recorded_by` BIGINT NOT NULL COMMENT '登记员工',
  `recorded_at` TIMESTAMP NOT NULL COMMENT '登记时间',
  `confirmed_by` BIGINT NULL COMMENT '确认员工',
  `confirmed_at` TIMESTAMP NULL DEFAULT NULL COMMENT '确认时间',
  PRIMARY KEY (`expense_id`),
  UNIQUE KEY `uq_expenses_rel0` (`document_no`,`line_no`),
  CONSTRAINT `ck_expenses_0` CHECK (line_no > 0),
  CONSTRAINT `ck_expenses_1` CHECK ((adjusts_expense_id IS NULL AND amount > 0) OR (adjusts_expense_id IS NOT NULL AND adjusts_expense_id <> expense_id AND amount <> 0 AND reason IS NOT NULL AND CHAR_LENGTH(TRIM(reason)) > 0)),
  CONSTRAINT `ck_expenses_2` CHECK (status <> '确认' OR (confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL)),
  CONSTRAINT `ck_expenses_3` CHECK (`kind` IN ('医疗用品','运营费')),
  CONSTRAINT `ck_expenses_4` CHECK (`status` IN ('待分摊','待确认','确认','作废'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='费用来源';

CREATE TABLE `expense_allocations` (
  `allocation_id` BIGINT NOT NULL COMMENT '分摊标识',
  `expense_id` BIGINT NOT NULL COMMENT '费用来源',
  `dentist_id` BIGINT NOT NULL COMMENT '费用归属牙医',
  `treatment_id` BIGINT NULL COMMENT '可直接对应的实际治疗，不能直接归属时为空',
  `amount` DECIMAL(14,2) NOT NULL COMMENT '本次归属金额',
  `allocation_basis` VARCHAR(255) NOT NULL COMMENT '直接归属或门店分配依据',
  PRIMARY KEY (`allocation_id`),
  CONSTRAINT `ck_expense_allocations_0` CHECK (amount <> 0),
  CONSTRAINT `ck_expense_allocations_1` CHECK (CHAR_LENGTH(TRIM(allocation_basis)) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='费用归属分摊';

CREATE TABLE `followup_plans` (
  `followup_plan_id` BIGINT NOT NULL COMMENT '复诊计划标识',
  `patient_id` BIGINT NOT NULL COMMENT '计划患者',
  `source_appointment_id` BIGINT NOT NULL COMMENT '提出复诊建议的来源就诊',
  `dentist_id` BIGINT NOT NULL COMMENT '提出建议的牙医',
  `clinic_id` BIGINT NOT NULL COMMENT '负责跟进门店',
  `suggested_date` DATE NOT NULL COMMENT '牙医建议复诊日期',
  `recommendation` TEXT NOT NULL COMMENT '复诊事由及建议',
  `status` VARCHAR(16) NOT NULL COMMENT '待复诊／已完成／已取消',
  `completed_appointment_id` BIGINT NULL COMMENT '实际完成的复诊就诊',
  `completed_at` TIMESTAMP NULL DEFAULT NULL COMMENT '复诊完成时刻',
  `cancel_reason` TEXT NULL COMMENT '取消计划原因',
  `created_at` TIMESTAMP NOT NULL COMMENT '建立时间',
  `updated_at` TIMESTAMP NOT NULL COMMENT '最近维护时间',
  PRIMARY KEY (`followup_plan_id`),
  UNIQUE KEY `uq_followup_plans_rel0` (`followup_plan_id`,`patient_id`),
  UNIQUE KEY `uq_followup_plans_rel1` (`followup_plan_id`,`patient_id`,`clinic_id`),
  CONSTRAINT `ck_followup_plans_0` CHECK (completed_appointment_id IS NULL OR completed_appointment_id <> source_appointment_id),
  CONSTRAINT `ck_followup_plans_1` CHECK ((status = '已完成' AND completed_appointment_id IS NOT NULL AND completed_at IS NOT NULL) OR (status <> '已完成' AND completed_appointment_id IS NULL AND completed_at IS NULL)),
  CONSTRAINT `ck_followup_plans_2` CHECK (status <> '已取消' OR (cancel_reason IS NOT NULL AND CHAR_LENGTH(TRIM(cancel_reason)) > 0)),
  CONSTRAINT `ck_followup_plans_3` CHECK (`status` IN ('待复诊','已完成','已取消'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='复诊计划';

CREATE TABLE `followup_contacts` (
  `contact_id` BIGINT NOT NULL COMMENT '联系记录标识',
  `followup_plan_id` BIGINT NOT NULL COMMENT '所跟进复诊计划',
  `responsible_account_id` BIGINT NOT NULL COMMENT '实际跟进责任人',
  `contacted_at` TIMESTAMP NOT NULL COMMENT '联系时刻',
  `contact_channel` VARCHAR(8) NOT NULL COMMENT '电话／柜台／站内／短信',
  `result` VARCHAR(24) NOT NULL COMMENT '未接通／已提醒／已约待诊／需调整／暂不复诊／其他',
  `note` TEXT NULL COMMENT '沟通内容及后续安排',
  `next_contact_at` TIMESTAMP NULL DEFAULT NULL COMMENT '约定下一次联系时间',
  PRIMARY KEY (`contact_id`),
  CONSTRAINT `ck_followup_contacts_0` CHECK (`contact_channel` IN ('电话','柜台','站内','短信')),
  CONSTRAINT `ck_followup_contacts_1` CHECK (`result` IN ('未接通','已提醒','已约待诊','需调整','暂不复诊','其他'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='复诊跟进联系';

CREATE TABLE `reminder_messages` (
  `message_id` BIGINT NOT NULL COMMENT '提醒记录标识',
  `patient_id` BIGINT NOT NULL COMMENT '提醒患者',
  `clinic_id` BIGINT NOT NULL COMMENT '负责提醒的门店',
  `appointment_id` BIGINT NULL COMMENT '关联预约，到诊提醒填写',
  `followup_plan_id` BIGINT NULL COMMENT '关联复诊计划，复诊提醒填写',
  `reminder_kind` VARCHAR(16) NOT NULL COMMENT '到诊提醒／复诊提醒',
  `planned_at` TIMESTAMP NOT NULL COMMENT '计划发送时刻',
  `channel` VARCHAR(8) NOT NULL COMMENT '站内／短信',
  `recipient_snapshot` VARCHAR(100) NULL COMMENT '短信目标号码或站内患者编号',
  `content` TEXT NOT NULL COMMENT '实际提醒内容',
  `send_status` VARCHAR(16) NOT NULL COMMENT '待发送／发送中／已发送／失败／已取消',
  `sent_at` TIMESTAMP NULL DEFAULT NULL COMMENT '实际发送时刻',
  `send_result` TEXT NULL COMMENT '业务发送结果或失败原因',
  `handled_by` BIGINT NULL COMMENT '处理失败的前台账号',
  `handled_at` TIMESTAMP NULL DEFAULT NULL COMMENT '失败处理时间',
  `handling_note` TEXT NULL COMMENT '重发或人工联系结果',
  PRIMARY KEY (`message_id`),
  CONSTRAINT `ck_reminder_messages_0` CHECK ((reminder_kind = '到诊提醒' AND appointment_id IS NOT NULL AND followup_plan_id IS NULL) OR (reminder_kind = '复诊提醒' AND appointment_id IS NULL AND followup_plan_id IS NOT NULL)),
  CONSTRAINT `ck_reminder_messages_1` CHECK (send_status <> '已发送' OR sent_at IS NOT NULL),
  CONSTRAINT `ck_reminder_messages_2` CHECK ((handled_by IS NULL AND handled_at IS NULL AND handling_note IS NULL) OR (handled_by IS NOT NULL AND handled_at IS NOT NULL AND handling_note IS NOT NULL)),
  CONSTRAINT `ck_reminder_messages_3` CHECK (`reminder_kind` IN ('到诊提醒','复诊提醒')),
  CONSTRAINT `ck_reminder_messages_4` CHECK (`channel` IN ('站内','短信')),
  CONSTRAINT `ck_reminder_messages_5` CHECK (`send_status` IN ('待发送','发送中','已发送','失败','已取消'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='提醒发送记录';

