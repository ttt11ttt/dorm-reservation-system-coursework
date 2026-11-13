import { hasRoleAtClinic } from './followup-core.js'

export const REMINDER_RULE_SNAPSHOT_FIELDS = [
  'ruleId', 'clinicId', 'appointmentLeadMinutes', 'followupLeadDays', 'followupSendTime',
  'inAppEnabled', 'smsEnabled', 'updatedBy', 'updatedAt', 'mutationCount',
]

export function canReadReminderAdmin(identity, clinicId) {
  return ['前台', '运营人员／店长'].some((role) => hasRoleAtClinic(identity, role, clinicId))
}

export function canManageReminderRules(identity, clinicId) {
  return hasRoleAtClinic(identity, '运营人员／店长', clinicId)
}

export function preserveReminderRuleSnapshot(snapshot) {
  if (!snapshot || typeof snapshot !== 'object') throw new Error('缺少服务端提醒规则快照。')
  if (Object.keys(snapshot).some((field) => !REMINDER_RULE_SNAPSHOT_FIELDS.includes(field))) throw new Error('服务端提醒规则快照包含未知字段，不能静默丢弃。')
  const result = {}
  for (const field of REMINDER_RULE_SNAPSHOT_FIELDS) {
    if (!Object.hasOwn(snapshot, field)) throw new Error(`提醒规则快照缺少字段 ${field}。`)
    const value = snapshot[field]
    if (['appointmentLeadMinutes', 'followupLeadDays'].includes(field) && value !== null && (!Number.isInteger(value) || value < 0)) throw new Error(`提醒规则快照字段 ${field} 必须是非负整数或null。`)
    if (['inAppEnabled', 'smsEnabled'].includes(field) && typeof value !== 'boolean') throw new Error(`提醒规则快照字段 ${field} 必须是boolean。`)
    if (['ruleId', 'clinicId', 'updatedBy', 'updatedAt', 'mutationCount'].includes(field) && typeof value !== 'string') throw new Error(`提醒规则快照字段 ${field} 必须按服务端原字符串保留。`)
    if (field === 'followupSendTime' && value !== null && typeof value !== 'string') throw new Error('提醒规则快照发送时间必须是原字符串或null。')
    result[field] = value
  }
  return result
}

function optionalNonNegativeInteger(value, name) {
  if (value == null || String(value).trim() === '') return null
  const number = Number(value)
  if (!Number.isSafeInteger(number) || number < 0 || number > 2147483647) throw new Error(`${name}必须为空或0至2147483647的整数。`)
  return number
}

function normalizedTime(value) {
  const text = String(value ?? '').trim()
  if (!text) return null
  if (/^\d{2}:\d{2}$/.test(text)) {
    const [hours, minutes] = text.split(':').map(Number)
    if (hours < 24 && minutes < 60) return `${text}:00`
  }
  if (/^\d{2}:\d{2}:\d{2}$/.test(text)) {
    const [hours, minutes, seconds] = text.split(':').map(Number)
    if (hours < 24 && minutes < 60 && seconds < 60) return text
  }
  throw new Error('复诊发送时间请填写有效北京时间（HH:mm或HH:mm:ss）。')
}

export function makeReminderRulePayload({ ruleId, expected, appointmentLeadMinutes, followupLeadDays, followupSendTime, inAppEnabled, smsEnabled, reason }) {
  if (!/^\d+$/.test(String(ruleId)) || BigInt(ruleId) <= 0n || BigInt(ruleId) > 0x7fffffffffffffffn) throw new Error('规则ID必须是正63位以内十进制字符串。')
  if (expected !== null) expected = preserveReminderRuleSnapshot(expected)
  if (typeof inAppEnabled !== 'boolean' || typeof smsEnabled !== 'boolean') throw new Error('请明确选择站内与短信渠道开关。')
  const days = optionalNonNegativeInteger(followupLeadDays, '复诊提前天数')
  const time = normalizedTime(followupSendTime)
  if ((days === null) !== (time === null)) throw new Error('复诊提前天数与发送时间必须同时填写或同时留空。')
  const trimmedReason = String(reason ?? '').trim()
  if (!trimmedReason || trimmedReason.length > 1000) throw new Error('规则变更原因必填，且不超过1000字符。')
  return {
    ruleId: String(ruleId), expected,
    appointmentLeadMinutes: optionalNonNegativeInteger(appointmentLeadMinutes, '到诊提前分钟'),
    followupLeadDays: days, followupSendTime: time,
    inAppEnabled, smsEnabled, reason: trimmedReason,
  }
}

export function reminderScope(accountId, clinicId, entityId, action) {
  return { namespace: 'adc.reminder.pending', accountId: String(accountId), sourceClinicId: String(clinicId), appointmentId: String(entityId), action }
}

export function reminderMigrationScope(accountId, clinicId, action) {
  return reminderScope(accountId, clinicId, `clinic:${clinicId}`, action)
}

export function makeReminderMigrationPreviewRequest(migrationId, rulePayload) {
  const id = String(migrationId)
  if (!/^\d+$/.test(id) || BigInt(id) <= 0n || BigInt(id) > 0x7fffffffffffffffn) throw new Error('迁移编号必须是正63位以内十进制字符串。')
  if (!rulePayload || !rulePayload.expected || String(rulePayload.ruleId) !== String(rulePayload.expected.ruleId)) throw new Error('整店迁移只接受现有规则及匹配的完整expected快照；新建规则请使用原保存入口。')
  return { migrationId: id, ...rulePayload }
}

export function reminderMigrationEndpoint(clinicId, migrationId = null) {
  const root = `/operations/clinics/${encodeURIComponent(String(clinicId))}/reminder-rule-migrations`
  if (migrationId == null) return `${root}/preview`
  const id = String(migrationId)
  if (!/^\d+$/.test(id) || BigInt(id) <= 0n || BigInt(id) > 0x7fffffffffffffffn) throw new Error('迁移编号必须是正63位以内十进制字符串。')
  return `${root}/${encodeURIComponent(id)}`
}

export function makeReminderMigrationCommitPayload(previewDigest) {
  const digest = String(previewDigest ?? '')
  if (!/^[0-9a-f]{64}$/.test(digest)) throw new Error('提交只接受原预览返回的64位小写previewDigest。')
  return { previewDigest: digest }
}

export function reminderMigrationFingerprint(migrationId, rulePayload) {
  return JSON.stringify(stable(makeReminderMigrationPreviewRequest(migrationId, rulePayload)))
}

export function isReminderMigrationPreviewExpired(preview, now = Date.now()) {
  return !preview || !Number.isFinite(Date.parse(String(preview.expiresAt ?? ''))) || Date.parse(String(preview.expiresAt)) <= Number(now)
}

export function describeReminderMigrationError(code) {
  if (code === 'MIGRATION_SCOPE_TOO_LARGE' || code === 'MIGRATION_AUDIT_TOO_LARGE') return '服务端拒绝本次整店迁移：业务、历史消息、资源或审计超出有界上限，未执行部分迁移。请核对返回范围并重新预览。'
  if (code === 'MIGRATION_PREVIEW_EXPIRED') return '原预览已超过5分钟有效期，未按旧digest提交；请读取当前配置并用新migrationId重新预览。'
  if (code === 'MIGRATION_PREVIEW_STALE') return '预览依赖或动作计划已变化，服务端没有应用部分变更；请读取当前配置并用新migrationId重新预览。'
  if (code === 'MIGRATION_RECONCILIATION_REQUIRED') return '服务端发现待核实历史或阻断项，整店事务未提交；请先逐项核实，不能略过阻断。'
  if (code === 'IDEMPOTENCY_CONFLICT') return '原migrationId已绑定其他操作者或请求内容；原身份已保留待GET核对，不能换号重建或覆盖。'
  if (code === 'RETRY_TRANSACTION') return '服务端范围在锁定前发生变化；未显示为成功。请按原migrationId查询，再决定是否重新预览。'
  if (code === 'REMINDER_MIGRATION_CONFIRMATION_UNVERIFIED') return '迁移响应未确认原账号、门店、migrationId和previewDigest；原请求已保留，请先按原身份GET核对。'
  return ''
}

const MIGRATION_REQUEST_FIELDS = ['ruleId', 'expected', 'appointmentLeadMinutes', 'followupLeadDays', 'followupSendTime', 'inAppEnabled', 'smsEnabled', 'reason']
function stable(value) {
  if (Array.isArray(value)) return value.map(stable)
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map((key) => [key, stable(value[key])]))
  return value
}
function migrationCount(value, name) {
  const count = Number(value)
  if (!Number.isSafeInteger(count) || count < 0) throw new Error(`迁移预览${name}无效，未显示为可提交。`)
  return count
}

export function parseReminderMigrationResponse(raw) {
  if (!raw || typeof raw !== 'object' || !raw.preview || typeof raw.preview !== 'object') throw new Error('迁移查询响应缺少原预览，未显示为成功。')
  const preview = raw.preview
  const summary = preview.summary
  if (!summary || typeof summary !== 'object' || !Array.isArray(summary.issues) || !Array.isArray(summary.actions)) throw new Error('迁移预览摘要格式无效，未显示为可提交。')
  if (!/^[0-9a-f]{64}$/.test(String(preview.previewDigest ?? ''))) throw new Error('迁移预览摘要缺少有效previewDigest。')
  if (!Number.isFinite(Date.parse(String(preview.createdAt ?? ''))) || !Number.isFinite(Date.parse(String(preview.expiresAt ?? '')))) throw new Error('迁移预览缺少有效创建或到期时间。')
  if (typeof summary.actionsTruncated !== 'boolean' || typeof summary.commitAllowed !== 'boolean') throw new Error('迁移预览缺少明确截断或提交许可状态。')
  const issues = summary.issues.map((item) => {
    if (!item || typeof item !== 'object' || !Array.isArray(item.issues) || typeof item.blocking !== 'boolean') throw new Error('迁移缺项/阻断摘要格式无效。')
    return { businessType: String(item.businessType ?? ''), businessId: String(item.businessId ?? ''), issues: item.issues.map(String), blocking: item.blocking }
  })
  const actions = summary.actions.map((item) => {
    if (!item || typeof item !== 'object') throw new Error('迁移动作摘要格式无效。')
    return {
      businessType: String(item.businessType ?? ''), businessId: String(item.businessId ?? ''),
      operation: String(item.operation ?? ''), messageId: item.messageId == null ? null : String(item.messageId),
      channel: String(item.channel ?? ''), status: String(item.status ?? ''), plannedAt: String(item.plannedAt ?? ''), timeMode: String(item.timeMode ?? ''),
    }
  })
  if (actions.length > 100) throw new Error('迁移预览返回动作超过服务端展示上限。')
  const actionCount = migrationCount(summary.actionCount, '动作数')
  if (actionCount < actions.length || (!summary.actionsTruncated && actionCount !== actions.length) || (summary.actionsTruncated && actionCount <= actions.length)) throw new Error('迁移动作总数与截断标志不一致。')
  return {
    migrationId: String(raw.migrationId ?? preview.migrationId ?? ''), preview: {
      migrationId: String(preview.migrationId ?? ''), binding: preview.binding,
      createdAt: String(preview.createdAt), expiresAt: String(preview.expiresAt),
      previewDigest: String(preview.previewDigest),
      summary: {
        businessCount: migrationCount(summary.businessCount, '对象数'),
        historyCount: migrationCount(summary.historyCount, '历史消息数'),
        resourceCount: migrationCount(summary.resourceCount, '资源数'),
        actionCount,
        actions, actionsTruncated: summary.actionsTruncated,
        issues, commitAllowed: summary.commitAllowed,
      },
    },
    receipt: raw.receipt && typeof raw.receipt === 'object' ? raw.receipt : null,
    replayed: raw.replayed === true,
    currentSnapshot: raw.currentSnapshot == null ? null : preserveReminderRuleSnapshot(raw.currentSnapshot),
    delivery: String(raw.delivery ?? ''),
  }
}

export function assertReminderMigrationAssociation(clinicId, migrationId, actorId, raw, expectedRequest = null, expectedDigest = null) {
  const result = parseReminderMigrationResponse(raw)
  const binding = result.preview.binding
  if (result.migrationId !== String(migrationId) || result.preview.migrationId !== String(migrationId)
      || !binding || String(binding.clinicId) !== String(clinicId) || String(binding.actorId) !== String(actorId)
      || String(binding.migrationId) !== String(migrationId)) {
    const error = new Error('迁移响应与原账号、门店或固定migrationId不匹配；原请求保留待核对。')
    error.code = 'REMINDER_MIGRATION_CONFIRMATION_UNVERIFIED'
    throw error
  }
  const receipt = result.receipt
  if (receipt && (String(receipt.migrationId) !== String(migrationId) || String(receipt.actorId) !== String(actorId) || String(receipt.previewDigest ?? '') !== result.preview.previewDigest)) {
    const error = new Error('迁移历史回执与原migrationId或previewDigest不匹配；原请求保留待核对。')
    error.code = 'REMINDER_MIGRATION_CONFIRMATION_UNVERIFIED'
    throw error
  }
  if (expectedDigest != null && (String(result.preview.previewDigest) !== String(expectedDigest) || (receipt && String(receipt.previewDigest) !== String(expectedDigest)))) {
    const error = new Error('迁移响应previewDigest与持久化原提交digest不匹配；原请求保留待核对。')
    error.code = 'REMINDER_MIGRATION_CONFIRMATION_UNVERIFIED'
    throw error
  }
  if (expectedRequest) {
    const saved = binding.request
    const expected = Object.fromEntries(MIGRATION_REQUEST_FIELDS.map((field) => [field, expectedRequest[field]]))
    const actual = saved && Object.fromEntries(MIGRATION_REQUEST_FIELDS.map((field) => [field, saved[field]]))
    if (!actual || JSON.stringify(stable(actual)) !== JSON.stringify(stable(expected))) {
      const error = new Error('服务端登记预览与原表单请求不一致；没有显示为可提交。')
      error.code = 'REMINDER_MIGRATION_CONFIRMATION_UNVERIFIED'
      throw error
    }
  }
  return result
}

export function reminderDispatchOptions(record) {
  const options = { method: String(record.method ?? 'GET') }
  if (record.payload && Object.keys(record.payload).length) options.body = record.payload
  return options
}

// Busy leases are owned by a specific account/clinic/object scope. A stale request
// can finish only its own lease, never a newer operation started after a scope reset.
export function createScopedBusyState(initialScope) {
  let scope = String(initialScope)
  let sequence = 0
  const active = new Map()
  return {
    reset(nextScope) {
      scope = String(nextScope)
      active.clear()
    },
    begin(channel) {
      const ticket = { scope, channel: String(channel), sequence: ++sequence }
      active.set(ticket.channel, ticket)
      return ticket
    },
    finish(ticket) {
      if (ticket.scope !== scope || active.get(ticket.channel) !== ticket) return false
      active.delete(ticket.channel)
      return true
    },
    isBusy(channel) { return active.has(String(channel)) },
  }
}

export function assertReminderRuleConfirmation(clinicId, expectedRuleId, response) {
  if (String(response?.clinicId ?? '') !== String(clinicId)
      || String(response?.rule?.rule_id ?? '') !== String(expectedRuleId)
      || String(response?.snapshot?.ruleId ?? '') !== String(expectedRuleId)) {
    const error = new Error('规则保存响应未确认当前门店与固定规则ID；原请求已保留，请先核对。')
    error.code = 'REMINDER_RULE_CONFIRMATION_UNVERIFIED'
    throw error
  }
  return response
}

export function parseReminderPlanResult(raw) {
  if (!raw || typeof raw !== 'object' || !Array.isArray(raw.issues) || !Array.isArray(raw.actions)) throw new Error('提醒计划响应格式无效，未显示为成功。')
  return {
    businessType: String(raw.businessType ?? ''), businessId: String(raw.businessId ?? ''), clinicId: String(raw.clinicId ?? ''),
    asOf: raw.asOf == null ? '' : String(raw.asOf),
    issues: raw.issues.map((item) => String(item)),
    actions: raw.actions.map((item) => ({
      operation: String(item?.operation ?? ''), messageId: item?.messageId == null ? null : String(item.messageId),
      channel: String(item?.channel ?? ''), plannedAt: item?.plannedAt == null ? '' : String(item.plannedAt),
      status: String(item?.status ?? ''), content: item?.content == null ? '' : String(item.content),
      recipientMasked: item?.recipientMasked == null ? '' : String(item.recipientMasked),
    })),
    actionCount: raw.actionCount == null ? String(raw.actions.length) : String(raw.actionCount),
    actionsTruncated: raw.actionsTruncated === true, requiresReconciliation: raw.requiresReconciliation === true,
    applied: raw.applied === true, delivery: String(raw.delivery ?? ''), batchRebuild: String(raw.batchRebuild ?? ''),
  }
}

export function assertReminderPlanAssociation(expectedType, expectedBusinessId, expectedClinicId, raw) {
  const result = parseReminderPlanResult(raw)
  const typeMatch = expectedType === 'appointment' ? result.businessType === 'APPOINTMENT' : result.businessType === 'FOLLOWUP'
  if (!typeMatch || result.businessId !== String(expectedBusinessId) || result.clinicId !== String(expectedClinicId)) {
    const error = new Error('服务端提醒响应与原对象类型、编号或负责门店不匹配；原请求保留待核对。')
    error.code = 'REMINDER_OBJECT_CONFIRMATION_UNVERIFIED'
    throw error
  }
  return result
}
