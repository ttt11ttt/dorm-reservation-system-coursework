<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { api, formatShanghai, operationId, sessionToken } from '../api'
import type { Clinic, Identity } from '../api'
import NoticeBar from '../components/NoticeBar.vue'
import { loadReminderPendingWrites, removePendingWrite, type PendingWrite } from '../fulfillment-core.js'
import { createReminderWriteQueue } from '../fulfillment-write-queue.js'
import ReminderObjectActions from '../components/ReminderObjectActions.vue'
import { assertReminderMigrationAssociation, assertReminderRuleConfirmation, canManageReminderRules, canReadReminderAdmin, createScopedBusyState, describeReminderMigrationError, isReminderMigrationPreviewExpired, makeReminderMigrationCommitPayload, makeReminderMigrationPreviewRequest, makeReminderRulePayload, parseReminderMigrationResponse, preserveReminderRuleSnapshot, reminderMigrationEndpoint, reminderMigrationFingerprint, reminderMigrationScope, reminderScope } from '../reminder-core.js'
import type { ReminderRuleSnapshot } from '../reminder-core.js'

type Row = Record<string, unknown>
type RuleResponse = { clinicId: string; rule: Row | null; snapshot: ReminderRuleSnapshot | null; batchRebuild: string; delivery: string }
type MigrationResponse = ReturnType<typeof parseReminderMigrationResponse>
const props = defineProps<{ clinics: Clinic[]; identity: Identity }>()
const authorizedClinics = computed(() => props.clinics.filter((clinic) => canReadReminderAdmin(props.identity, clinic.clinic_id)))
const clinicId = ref(authorizedClinics.value[0]?.clinic_id ?? '')
const objectType = ref<'appointment' | 'followup'>('appointment')
const objectIdInput = ref('')
const selectedObject = ref<{ type: 'appointment' | 'followup'; id: string } | null>(null)
const rule = ref<Row | null>(null)
const snapshot = ref<ReminderRuleSnapshot | null>(null)
const batchRebuild = ref('')
const delivery = ref('')
const appointmentLead = ref('')
const followupLead = ref('')
const followupTime = ref('')
const inAppChoice = ref('')
const smsChoice = ref('')
const reason = ref('')
const draftRuleId = ref(operationId('RULE').slice(5))
const draftMigrationId = ref(operationId('MIG').slice(4))
const pending = ref<PendingWrite | null>(null)
const migrationPending = ref<PendingWrite | null>(null)
const migrationPreview = ref<MigrationResponse['preview'] | null>(null)
const migrationReceipt = ref<Record<string, unknown> | null>(null)
const migrationCreatedMessages = computed(() => Array.isArray(migrationReceipt.value?.createdMessages) ? migrationReceipt.value.createdMessages as Array<Record<string, unknown>> : [])
const migrationCurrentSnapshot = ref<ReminderRuleSnapshot | null>(null)
const migrationError = ref('')
const migrationNotice = ref('')
const migrationCheckEmpty = ref(false)
const migrationPreviewRetryAllowed = ref(false)
const migrationFingerprint = ref('')
const migrationNow = ref(Date.now())
const loading = ref(false)
const saving = ref(false)
const storageReadable = ref(true)
const loadError = ref('')
const actionError = ref('')
const notice = ref('')
let generation = 0
let disposed = false
let suppressMigrationInvalidation = false
const activity = createScopedBusyState(scopeKey())
const canManage = computed(() => canManageReminderRules(props.identity, clinicId.value))
const migrationBusy = ref(false)
const migrationExpired = computed(() => !!migrationPreview.value && isReminderMigrationPreviewExpired(migrationPreview.value, migrationNow.value))
const migrationUnchanged = computed(() => !!migrationPreview.value && !!migrationFingerprint.value && migrationFingerprint.value === currentMigrationFingerprint())
const canCommitMigration = computed(() => canManage.value && storageReadable.value && !saving.value && !migrationBusy.value && !pending.value && !migrationPending.value && !migrationReceipt.value && !!migrationPreview.value && migrationPreview.value.summary.commitAllowed && !migrationExpired.value && migrationUnchanged.value)
const selectedClinicName = computed(() => authorizedClinics.value.find((item) => item.clinic_id === clinicId.value)?.name ?? `门店 #${clinicId.value}`)
const queue = createReminderWriteQueue(() => window.sessionStorage, async (record) => {
  const response = await api<RuleResponse>(record.endpoint, { method: record.method, body: record.payload })
  const expectedClinic = String(record.sourceClinicId)
  const expectedRuleId = String(record.payload.ruleId ?? '')
  return assertReminderRuleConfirmation(expectedClinic, expectedRuleId, response)
})
const migrationQueue = createReminderWriteQueue(() => window.sessionStorage, async (record) => {
  const response = await api<Record<string, unknown>>(record.endpoint, { method: record.method, body: record.payload })
  let expectedRequest: Record<string, unknown> | null = null
  if (record.action === 'reminder-migration-preview') {
    const { migrationId: _identity, ...request } = record.payload
    expectedRequest = request
  }
  const expectedDigest = record.action === 'reminder-migration-commit' ? String(record.payload.previewDigest ?? '') : null
  const confirmed = assertReminderMigrationAssociation(record.sourceClinicId, record.operationId, record.accountId, response, expectedRequest, expectedDigest)
  if (record.action === 'reminder-migration-commit' && !confirmed.receipt) {
    throw Object.assign(new Error('提交响应没有包含原previewDigest的成功历史回执；请按原migrationId查询核实。'), { code: 'REMINDER_MIGRATION_CONFIRMATION_UNVERIFIED' })
  }
  return confirmed
})
let migrationTimer: number | undefined

onMounted(() => { void loadConfig() })
onMounted(() => { migrationTimer = window.setInterval(() => { migrationNow.value = Date.now() }, 5_000) })
onUnmounted(() => { disposed = true; generation += 1; activity.reset(`disposed:${scopeKey()}`); loading.value = false; saving.value = false; migrationBusy.value = false; if (migrationTimer !== undefined) window.clearInterval(migrationTimer) })
watch(authorizedClinics, (items) => { if (!items.some((item) => item.clinic_id === clinicId.value)) clinicId.value = items[0]?.clinic_id ?? '' })
watch([clinicId, () => props.identity.accountId], () => {
  generation += 1; activity.reset(scopeKey()); loading.value = false; saving.value = false; migrationBusy.value = false
  rule.value = null; snapshot.value = null; pending.value = null; migrationPending.value = null; batchRebuild.value = ''; delivery.value = ''
  clearMigrationState()
  clearForm(); selectedObject.value = null; objectIdInput.value = ''; loadError.value = ''; actionError.value = ''; notice.value = ''; storageReadable.value = true
  if (clinicId.value) void loadConfig()
})
watch([appointmentLead, followupLead, followupTime, inAppChoice, smsChoice, reason], () => { if (!suppressMigrationInvalidation) invalidateMigrationPreview() }, { flush: 'sync' })
watch(migrationExpired, (expired) => {
  if (!expired || !migrationPreview.value) return
  draftMigrationId.value = freshMigrationId()
  migrationFingerprint.value = ''
  migrationNotice.value = `预览${migrationPreview.value.migrationId}已过5分钟有效期；原previewDigest已禁用，新migrationId已分配。`
})

function clearForm() {
  appointmentLead.value = ''; followupLead.value = ''; followupTime.value = ''; inAppChoice.value = ''; smsChoice.value = ''; reason.value = ''; draftRuleId.value = operationId('RULE').slice(5)
}
function freshMigrationId() { return operationId('MIG').slice(4) }
function clearMigrationState(newIdentity = true) {
  migrationPreview.value = null; migrationReceipt.value = null; migrationCurrentSnapshot.value = null; migrationError.value = ''; migrationNotice.value = ''
  migrationCheckEmpty.value = false; migrationPreviewRetryAllowed.value = false; migrationFingerprint.value = ''
  if (newIdentity) draftMigrationId.value = freshMigrationId()
}
function invalidateMigrationPreview() {
  if (!migrationPreview.value) return
  const receipt = migrationReceipt.value
  const currentSnapshot = migrationCurrentSnapshot.value
  clearMigrationState()
  migrationReceipt.value = receipt
  migrationCurrentSnapshot.value = currentSnapshot
  migrationNotice.value = receipt
    ? '当前表单已改变，旧预览与previewDigest不可提交；历史成功回执与当前快照仍单独保留。'
    : '配置表单或服务端快照已改变；旧预览与previewDigest已作废。请重新读取并使用新migrationId预览。'
}
function currentMigrationRequest() {
  const request = makePayload()
  if (!request.expected) throw new Error('整店迁移只支持维护已有规则，不能从新建规则入口发起。')
  return request
}
function currentMigrationFingerprint() {
  try { return reminderMigrationFingerprint(draftMigrationId.value, currentMigrationRequest()) } catch { return '' }
}
function migrationRequestScope(action: 'reminder-migration-preview' | 'reminder-migration-commit') {
  return reminderMigrationScope(props.identity.accountId, clinicId.value, action)
}
function loadMigrationPending(action?: 'reminder-migration-preview' | 'reminder-migration-commit') {
  try {
    const records = loadReminderPendingWrites(window.sessionStorage, { accountId: props.identity.accountId, sourceClinicId: clinicId.value, appointmentId: `clinic:${clinicId.value}` })
    migrationPending.value = action ? records.find((item) => item.action === action) ?? null : records.find((item) => item.action === 'reminder-migration-preview' || item.action === 'reminder-migration-commit') ?? null
    if (migrationPending.value) draftMigrationId.value = migrationPending.value.operationId
    storageReadable.value = true
    return migrationPending.value
  } catch {
    migrationPending.value = null; storageReadable.value = false
    migrationError.value = '无法安全读取迁移原请求；已停用迁移操作。请恢复会话存储后重新读取。'
    return null
  }
}
function openObject() {
  if (!clinicId.value || !authorizedClinics.value.some((item) => item.clinic_id === clinicId.value)) { actionError.value = '当前没有可访问门店；不能查询预约或复诊提醒。'; return }
  const id = objectIdInput.value.trim()
  if (!/^\d+$/.test(id) || BigInt(id) <= 0n || BigInt(id) > 0x7fffffffffffffffn) { actionError.value = '请输入从现有预约/复诊详情复制的正63位以内字符串ID。'; return }
  selectedObject.value = { type: objectType.value, id }
  actionError.value = ''
}
function scopeKey() { return `${props.identity.accountId}:${clinicId.value}` }
function scope() { return reminderScope(props.identity.accountId, clinicId.value, `clinic:${clinicId.value}`, 'reminder-rule') }
function current(token: string | null, accountId: string, clinic: string, requestGeneration: number, manage = false) {
  return !disposed && token === sessionToken() && accountId === props.identity.accountId && clinic === clinicId.value && requestGeneration === generation
    && canReadReminderAdmin(props.identity, clinic) && (!manage || canManageReminderRules(props.identity, clinic))
}
function loadPending() {
  try {
    const records = loadReminderPendingWrites(window.sessionStorage, { accountId: props.identity.accountId, sourceClinicId: clinicId.value, appointmentId: `clinic:${clinicId.value}` })
    pending.value = records.find((item) => item.action === 'reminder-rule') ?? null
    migrationPending.value = records.find((item) => item.action === 'reminder-migration-preview' || item.action === 'reminder-migration-commit') ?? null
    if (migrationPending.value) draftMigrationId.value = migrationPending.value.operationId
    storageReadable.value = true
  } catch {
    pending.value = null; migrationPending.value = null; storageReadable.value = false
    actionError.value = '无法安全读取本店提醒规则原请求；已停用配置写入。请恢复会话存储后重新读取。'
    migrationError.value = '无法读取本店迁移原请求；已停用整店迁移。请恢复会话存储后重新读取。'
  }
}
function install(response: RuleResponse) {
  rule.value = response.rule
  snapshot.value = response.snapshot ? preserveReminderRuleSnapshot(response.snapshot) : null
  batchRebuild.value = String(response.batchRebuild ?? '')
  delivery.value = String(response.delivery ?? '')
  if (response.rule) {
    appointmentLead.value = response.rule.appointment_lead_minutes == null ? '' : String(response.rule.appointment_lead_minutes)
    followupLead.value = response.rule.followup_lead_days == null ? '' : String(response.rule.followup_lead_days)
    followupTime.value = response.rule.followup_send_time == null ? '' : String(response.rule.followup_send_time)
    inAppChoice.value = response.rule.in_app_enabled === true ? 'true' : 'false'
    smsChoice.value = response.rule.sms_enabled === true ? 'true' : 'false'
    draftRuleId.value = String(response.rule.rule_id)
  } else clearForm()
}
async function loadConfig() {
  if (!clinicId.value || !canReadReminderAdmin(props.identity, clinicId.value)) {
    generation += 1; activity.reset(scopeKey()); loading.value = false; saving.value = false; migrationBusy.value = false
    clearMigrationState(); rule.value = null; snapshot.value = null; loadError.value = '当前账号没有所选门店的提醒规则读取权限。'; return
  }
  clearMigrationState()
  const requestGeneration = ++generation; const token = sessionToken(); const accountId = props.identity.accountId; const clinic = clinicId.value
  const ticket = activity.begin('load')
  loading.value = true; loadError.value = ''; loadPending()
  try {
    const response = await api<RuleResponse>(`/staff/clinics/${encodeURIComponent(clinic)}/reminder-rules`)
    if (current(token, accountId, clinic, requestGeneration)) install(response)
  } catch (cause) { if (current(token, accountId, clinic, requestGeneration)) loadError.value = message(cause) }
  finally { if (activity.finish(ticket)) loading.value = false }
}
function message(cause: unknown) { return cause instanceof Error ? cause.message : '提醒规则操作未完成。' }
function describe(cause: unknown) {
  const code = String((cause as { code?: string } | undefined)?.code ?? '')
  if (code === 'RULE_CHANGE_REQUIRES_RECONCILIATION') return '服务端阻止了配置变更：本店存在待发送、发送中或失败消息，需要逐对象核对。没有强制清空或全店迁移入口。'
  if (code === 'RULE_SNAPSHOT_CONFLICT') return '提醒规则已被其他操作更新；请重新读取完整快照后再编辑。'
  if (code === 'IDEMPOTENCY_CONFLICT') return '固定规则号已对应另一请求；原请求保留，请先核对，不能更换ruleId绕过。'
  if (code === 'REMINDER_RULE_CONFIRMATION_UNVERIFIED') return '服务端保存响应未确认门店与固定规则ID；原请求保留待核对。'
  return message(cause)
}
function makePayload() {
  if (rule.value && !snapshot.value) throw new Error('已有规则但服务端未返回完整expected快照，不能提交维护。')
  if (!inAppChoice.value || !smsChoice.value) throw new Error('请明确选择站内与短信渠道是否启用。')
  return makeReminderRulePayload({
    ruleId: rule.value ? String(rule.value.rule_id) : draftRuleId.value,
    expected: rule.value ? snapshot.value : null,
    appointmentLeadMinutes: appointmentLead.value,
    followupLeadDays: followupLead.value,
    followupSendTime: followupTime.value,
    inAppEnabled: inAppChoice.value === 'true', smsEnabled: smsChoice.value === 'true', reason: reason.value,
  })
}
async function saveRule() {
  if (!canManage.value || !storageReadable.value || pending.value || migrationPending.value || saving.value || migrationBusy.value) return
  let payload: ReturnType<typeof makeReminderRulePayload>
  try { payload = makePayload() } catch (cause) { actionError.value = message(cause); return }
  if (migrationPreview.value) clearMigrationState()
  await write(payload, false)
}
async function retryOriginal() {
  if (!pending.value || !storageReadable.value || saving.value) return
  await write(pending.value.payload as ReturnType<typeof makeReminderRulePayload>, true)
}
async function write(payload: ReturnType<typeof makeReminderRulePayload>, retry: boolean) {
  saving.value = true; actionError.value = ''; notice.value = ''
  const token = sessionToken(); const accountId = props.identity.accountId; const clinic = clinicId.value; const requestGeneration = generation
  const ticket = activity.begin('write')
  try {
    const outcome = retry && pending.value
      ? await queue.retry(scope(), pending.value)
      : await queue.submit(scope(), { endpoint: `/operations/clinics/${encodeURIComponent(clinic)}/reminder-rules`, method: 'PUT', payload, operationId: payload.ruleId })
    if (!current(token, accountId, clinic, requestGeneration, true)) return
    if (outcome.kind === 'success') {
      pending.value = null; install(outcome.result as RuleResponse)
      notice.value = '服务端已确认提醒规则保存。单对象重建仍需逐项发起；全店重建和消息发送未实现。'
      reason.value = ''
    } else if (outcome.kind === 'unknown' || outcome.kind === 'preserved') {
      loadPending()
      if (current(token, accountId, clinic, requestGeneration, true)) actionError.value = describe(outcome.error) || '规则写入结果未知；原payload、expected快照和固定ruleId已保留，请先核对再沿用原请求重试。'
    } else if (outcome.kind === 'rejected') {
      loadPending()
      if (current(token, accountId, clinic, requestGeneration, true)) actionError.value = describe(outcome.error)
    } else if (outcome.kind === 'storage-error') {
      storageReadable.value = false; actionError.value = '无法安全保存或读取规则原请求；未发送新请求。请恢复会话存储。'
    } else {
      loadPending()
      actionError.value = outcome.kind === 'pending-exists' ? '本店已有提醒规则原请求待核对；已阻止另发或覆盖。' : outcome.kind === 'busy' ? '本规则请求正在处理中。' : '原请求已变化；没有发送。'
    }
  } catch (cause) { if (current(token, accountId, clinic, requestGeneration, true)) actionError.value = describe(cause) }
  finally { if (activity.finish(ticket)) saving.value = false }
}
function migrationErrorMessage(cause: unknown) {
  const code = String((cause as { code?: string } | undefined)?.code ?? '')
  return describeReminderMigrationError(code) || message(cause)
}
function isDefinitiveMigrationAbort(cause: unknown) {
  const code = String((cause as { code?: string } | undefined)?.code ?? '')
  return ['MIGRATION_SCOPE_TOO_LARGE', 'MIGRATION_AUDIT_TOO_LARGE', 'MIGRATION_PREVIEW_EXPIRED', 'MIGRATION_PREVIEW_STALE', 'MIGRATION_RECONCILIATION_REQUIRED'].includes(code)
}
function clearResolvedMigrationEnvelope(record: PendingWrite, cause: unknown) {
  try {
    removePendingWrite(window.sessionStorage, record)
    migrationPending.value = null
    clearMigrationState()
    migrationError.value = migrationErrorMessage(cause)
  } catch {
    storageReadable.value = false
    migrationPending.value = record
    migrationError.value = `${migrationErrorMessage(cause)} 本机原请求清理失败，仍保留固定身份并停用后续迁移。`
  }
}
function activateMigrationResult(result: MigrationResponse, fingerprint = '') {
  migrationPreview.value = result.preview
  migrationReceipt.value = result.receipt
  migrationCurrentSnapshot.value = result.currentSnapshot
  draftMigrationId.value = result.migrationId
  migrationFingerprint.value = fingerprint
  migrationCheckEmpty.value = false
  migrationPreviewRetryAllowed.value = false
}
function applyMigrationSnapshot(current: ReminderRuleSnapshot | null) {
  if (!current) return
  suppressMigrationInvalidation = true
  snapshot.value = preserveReminderRuleSnapshot(current)
  rule.value = {
    ...(rule.value ?? {}), rule_id: current.ruleId, clinic_id: current.clinicId,
    appointment_lead_minutes: current.appointmentLeadMinutes, followup_lead_days: current.followupLeadDays,
    followup_send_time: current.followupSendTime, in_app_enabled: current.inAppEnabled, sms_enabled: current.smsEnabled,
    updated_by: current.updatedBy, updated_at: current.updatedAt,
  }
  appointmentLead.value = current.appointmentLeadMinutes == null ? '' : String(current.appointmentLeadMinutes)
  followupLead.value = current.followupLeadDays == null ? '' : String(current.followupLeadDays)
  followupTime.value = current.followupSendTime == null ? '' : String(current.followupSendTime)
  inAppChoice.value = current.inAppEnabled ? 'true' : 'false'
  smsChoice.value = current.smsEnabled ? 'true' : 'false'
  reason.value = ''
  suppressMigrationInvalidation = false
}
function applyMigrationDraft(request: Record<string, unknown>, migrationId: string) {
  suppressMigrationInvalidation = true
  draftMigrationId.value = migrationId
  appointmentLead.value = request.appointmentLeadMinutes == null ? '' : String(request.appointmentLeadMinutes)
  followupLead.value = request.followupLeadDays == null ? '' : String(request.followupLeadDays)
  followupTime.value = request.followupSendTime == null ? '' : String(request.followupSendTime)
  inAppChoice.value = request.inAppEnabled === true ? 'true' : 'false'
  smsChoice.value = request.smsEnabled === true ? 'true' : 'false'
  reason.value = String(request.reason ?? '')
  suppressMigrationInvalidation = false
}
function adoptMigrationResult(result: MigrationResponse, requestFingerprint = '') {
  activateMigrationResult(result, requestFingerprint)
  if (result.receipt && result.currentSnapshot) applyMigrationSnapshot(result.currentSnapshot)
}
async function previewMigration(retryOriginal = false) {
  if (!canManage.value || !storageReadable.value || migrationBusy.value || saving.value || loading.value) return
  const expiredId = !retryOriginal && migrationExpired.value ? migrationPreview.value?.migrationId ?? '' : ''
  let record: PendingWrite | null = null
  let request: Record<string, unknown>
  try {
    if (retryOriginal) {
      record = loadMigrationPending('reminder-migration-preview')
      if (!record || !migrationPreviewRetryAllowed.value) return
      request = record.payload
    } else {
      if (!rule.value || !snapshot.value || migrationPending.value) return
      request = makeReminderMigrationPreviewRequest(draftMigrationId.value, makePayload())
    }
  } catch (cause) { migrationError.value = migrationErrorMessage(cause); return }
  const token = sessionToken(); const accountId = props.identity.accountId; const clinic = clinicId.value; const requestGeneration = generation
  const ticket = activity.begin('migration-preview')
  if (expiredId) { migrationPreview.value = null; migrationFingerprint.value = '' }
  migrationBusy.value = true; migrationError.value = ''; migrationNotice.value = expiredId ? `旧预览${expiredId}已过期并停用，以下请求使用新migrationId ${draftMigrationId.value}。` : ''; migrationCheckEmpty.value = false
  try {
    const outcome = retryOriginal && record
      ? await migrationQueue.retry(migrationRequestScope('reminder-migration-preview'), record)
      : await migrationQueue.submit(migrationRequestScope('reminder-migration-preview'), { endpoint: reminderMigrationEndpoint(clinic), method: 'POST', payload: request, operationId: String(request.migrationId) })
    if (!current(token, accountId, clinic, requestGeneration, true)) return
    if (outcome.kind === 'success' || (outcome.kind === 'preserved' && outcome.result)) {
      const result = outcome.result as MigrationResponse
      migrationPending.value = outcome.kind === 'preserved' ? loadMigrationPending('reminder-migration-preview') : null
      adoptMigrationResult(result, reminderMigrationFingerprint(String(request.migrationId), request))
      migrationNotice.value = `${expiredId ? `旧预览${expiredId}已过期；` : ''}${result.receipt
        ? '原身份查询确认已有历史成功回执；下方receipt为历史结果，currentSnapshot为当前配置。'
        : '服务端已登记原身份预览。尚无成功回执是预览阶段状态，不代表失败；请检查范围、阻断项和有效期。'}`
    } else if (outcome.kind === 'preserved' && retryOriginal && record && isDefinitiveMigrationAbort(outcome.error)) {
      clearResolvedMigrationEnvelope(record, outcome.error)
    } else if (outcome.kind === 'unknown' || outcome.kind === 'preserved') {
      loadMigrationPending('reminder-migration-preview')
      migrationPreviewRetryAllowed.value = false
      migrationError.value = `${migrationErrorMessage(outcome.error) || '预览结果未知。'}请先按原migrationId GET查询，不要更换编号。`
    } else if (outcome.kind === 'rejected') {
      loadMigrationPending('reminder-migration-preview')
      migrationError.value = migrationErrorMessage(outcome.error)
    } else if (outcome.kind === 'storage-error') {
      storageReadable.value = false; migrationError.value = '无法持久保存原migrationId与预览payload；没有发送替代请求。请恢复会话存储后重新读取。'
    } else {
      loadPending(); migrationError.value = outcome.kind === 'pending-exists' ? '本店存在提醒或迁移原请求待核对；已阻止新migrationId。' : outcome.kind === 'busy' ? '提醒请求正在处理。' : '原预览请求已变化；没有发送。'
    }
  } catch (cause) { if (current(token, accountId, clinic, requestGeneration, true)) migrationError.value = migrationErrorMessage(cause) }
  finally { if (activity.finish(ticket)) migrationBusy.value = false }
}
async function commitMigration(retryOriginal = false) {
  if (!canManage.value || !storageReadable.value || migrationBusy.value || saving.value || loading.value) return
  let record: PendingWrite | null = null
  let migrationId: string
  let digest: string
  if (retryOriginal) {
    record = loadMigrationPending('reminder-migration-commit')
    if (!record || !migrationCheckEmpty.value || !migrationPreview.value || !migrationPreview.value.summary.commitAllowed || migrationExpired.value) return
    migrationId = record.operationId
    digest = String(record.payload.previewDigest ?? '')
    if (migrationId !== migrationPreview.value.migrationId || digest !== migrationPreview.value.previewDigest) {
      migrationError.value = '持久化原提交与当前GET预览身份或digest不匹配；未重试。请继续保留记录并核对。'; return
    }
  } else {
    if (!canCommitMigration.value) return
    const preview = migrationPreview.value
    if (!preview) return
    migrationId = preview.migrationId
    digest = preview.previewDigest
  }
  const token = sessionToken(); const accountId = props.identity.accountId; const clinic = clinicId.value; const requestGeneration = generation
  const ticket = activity.begin('migration-commit')
  migrationBusy.value = true; migrationError.value = ''; migrationNotice.value = ''; migrationCheckEmpty.value = false
  try {
    const outcome = retryOriginal && record
      ? await migrationQueue.retry(migrationRequestScope('reminder-migration-commit'), record)
      : await migrationQueue.submit(migrationRequestScope('reminder-migration-commit'), { endpoint: `${reminderMigrationEndpoint(clinic, migrationId)}/commit`, method: 'POST', payload: makeReminderMigrationCommitPayload(digest), operationId: migrationId })
    if (!current(token, accountId, clinic, requestGeneration, true)) return
    if (outcome.kind === 'success' || (outcome.kind === 'preserved' && outcome.result)) {
      const result = outcome.result as MigrationResponse
      migrationPending.value = outcome.kind === 'preserved' ? loadMigrationPending('reminder-migration-commit') : null
      adoptMigrationResult(result, migrationFingerprint.value)
      migrationNotice.value = outcome.kind === 'preserved'
        ? '服务端返回了历史成功回执，但本机未能清理原提交信封；请GET核对并清理，不要创建新身份。'
        : '服务端返回了原previewDigest的成功历史回执。配置与重排事务已确认；消息发送仍未实现。'
    } else if (outcome.kind === 'preserved' && retryOriginal && record && isDefinitiveMigrationAbort(outcome.error)) {
      clearResolvedMigrationEnvelope(record, outcome.error)
    } else if (outcome.kind === 'unknown' || outcome.kind === 'preserved') {
      loadMigrationPending('reminder-migration-commit')
      migrationCheckEmpty.value = false
      migrationError.value = `${migrationErrorMessage(outcome.error) || '提交结果未知。'}请先按原migrationId GET查询原回执；查询确认receipt=null后，才可沿用原digest重试。`
    } else if (outcome.kind === 'rejected') {
      loadMigrationPending('reminder-migration-commit')
      const code = String((outcome.error as { code?: string } | undefined)?.code ?? '')
      if (isDefinitiveMigrationAbort(outcome.error)) clearMigrationState()
      migrationError.value = migrationErrorMessage(outcome.error) || (code ? `服务端以${code}拒绝了迁移；没有显示为成功。` : '服务端明确拒绝了迁移；没有显示为成功。')
    } else if (outcome.kind === 'storage-error') {
      storageReadable.value = false; migrationError.value = '无法安全保存原previewDigest与migrationId；未发送提交请求。请恢复会话存储。'
    } else {
      loadPending(); migrationError.value = outcome.kind === 'pending-exists' ? '本店已有原提醒/迁移请求；已阻止提交。' : outcome.kind === 'busy' ? '提醒请求正在处理。' : '原预览提交已变化；没有发送。'
    }
  } catch (cause) { if (current(token, accountId, clinic, requestGeneration, true)) migrationError.value = migrationErrorMessage(cause) }
  finally { if (activity.finish(ticket)) migrationBusy.value = false }
}
async function queryMigration() {
  if (!canManage.value || !storageReadable.value || migrationBusy.value || loading.value || saving.value) return
  const record = migrationPending.value ?? loadMigrationPending()
  const migrationId = record?.operationId ?? migrationPreview.value?.migrationId ?? String(migrationReceipt.value?.migrationId ?? '')
  if (!migrationId) { migrationError.value = '没有可查询的原migrationId；先生成并提交一次预览请求。'; return }
  const token = sessionToken(); const accountId = props.identity.accountId; const clinic = clinicId.value; const requestGeneration = generation
  const ticket = activity.begin('migration-read')
  migrationBusy.value = true; migrationError.value = ''; migrationNotice.value = ''; migrationCheckEmpty.value = false; migrationPreviewRetryAllowed.value = false
  try {
    const raw = await api<Record<string, unknown>>(reminderMigrationEndpoint(clinic, migrationId))
    if (!current(token, accountId, clinic, requestGeneration, true)) return
    let expectedRequest: Record<string, unknown> | null = null
    if (record?.action === 'reminder-migration-preview') {
      const { migrationId: _identity, ...request } = record.payload
      expectedRequest = request
    }
    const expectedDigest = record?.action === 'reminder-migration-commit' ? String(record.payload.previewDigest ?? '') : null
    const result = assertReminderMigrationAssociation(clinic, migrationId, accountId, raw, expectedRequest, expectedDigest)
    let fingerprint = ''
    if (record?.action === 'reminder-migration-preview') {
      const { migrationId: _identity, ...request } = record.payload
      applyMigrationDraft(request, migrationId)
      fingerprint = reminderMigrationFingerprint(migrationId, request)
    } else if (!record && draftMigrationId.value === migrationId) {
      const currentFingerprint = currentMigrationFingerprint()
      const serverFingerprint = reminderMigrationFingerprint(migrationId, result.preview.binding.request)
      fingerprint = currentFingerprint === serverFingerprint ? currentFingerprint : ''
    }
    adoptMigrationResult(result, fingerprint)
    if (result.receipt) {
      if (record) {
        try { removePendingWrite(window.sessionStorage, record); migrationPending.value = null }
        catch { loadPending(); storageReadable.value = false }
      }
      migrationNotice.value = 'GET已确认该原身份的历史成功回执。receipt保留提交时历史结果，currentSnapshot单独表示当前配置；这不表示消息已发送。'
      migrationCheckEmpty.value = false
    } else if (record?.action === 'reminder-migration-preview') {
      try { removePendingWrite(window.sessionStorage, record); migrationPending.value = null }
      catch { loadPending(); storageReadable.value = false }
      migrationNotice.value = 'GET已找到原身份预览，但receipt=null表示尚无成功历史回执；这不是发送失败或成功。可检查原预览后继续。'
    } else if (record?.action === 'reminder-migration-commit') {
      migrationCheckEmpty.value = true
      migrationNotice.value = 'GET确认当前没有该原身份的成功回执。receipt=null本身不证明失败；仅在原预览仍未过期且commitAllowed时，才可沿用原digest重试。'
    } else {
      migrationCheckEmpty.value = true
      migrationNotice.value = 'GET已读取预览及历史回执。receipt=null是尚无成功历史回执的状态，不表示消息发送成败。'
    }
  } catch (cause) {
    if (!current(token, accountId, clinic, requestGeneration, true)) return
    const code = String((cause as { code?: string } | undefined)?.code ?? '')
    if (code === 'NOT_FOUND' && record?.action === 'reminder-migration-preview') {
      migrationPreviewRetryAllowed.value = true
      migrationError.value = 'GET未找到该migrationId登记；原账号、门店、migrationId与preview请求仍已保留，可选择沿用原请求重试。'
    } else if (code === 'NOT_FOUND' && record?.action === 'reminder-migration-commit') {
      migrationError.value = 'GET未找到原预览登记；原提交记录继续保留，不自动改号或重建。请人工核对后再处理。'
    } else migrationError.value = migrationErrorMessage(cause)
  } finally { if (activity.finish(ticket)) migrationBusy.value = false }
}
</script>

<template>
  <section v-if="!authorizedClinics.length" class="panel empty-state"><div class="empty-icon">⌂</div><strong>当前没有可访问门店</strong><span>提醒管理入口仍保留。请由账号管理员或连锁运营人员确认门店范围；本页不会读取、配置或查询单对象提醒。</span></section>
  <section v-else class="page-stack reminder-workspace">
    <div class="page-heading"><div><p class="eyebrow">员工工作台 · 服务端规则与逐对象核对</p><h1>提醒管理</h1><p class="muted">前台可读取规则；只有本店运营人员/店长可配置。服务端权限始终为准。</p></div><label class="compact-label">授权门店<select v-model="clinicId"><option v-for="clinic in authorizedClinics" :key="clinic.clinic_id" :value="clinic.clinic_id">{{ clinic.name }} · #{{ clinic.clinic_id }}</option></select></label></div>
    <NoticeBar tone="error" :title="loadError" @close="loadError = ''" /><NoticeBar tone="error" :title="actionError" @close="actionError = ''" /><NoticeBar tone="success" :title="notice" @close="notice = ''" />
    <section v-if="pending" class="panel reminder-pending"><strong>配置原请求待核对 · 固定 ruleId {{ pending.operationId }}</strong><p>本店门店范围、完整expected快照和payload已保存；重新读取不会清掉原请求。</p><button class="button button-small button-secondary" :disabled="saving || !storageReadable" @click="retryOriginal">沿用原payload及ruleId重试</button></section>
    <section class="panel reminder-rule-panel">
      <div class="section-heading"><div><p class="eyebrow">{{ selectedClinicName }}</p><h2>本店提醒规则</h2><p class="muted">读取当前服务端配置，不创建默认规则。</p></div><button class="button button-quiet" :disabled="loading || saving" @click="loadConfig">↻ 重新读取规则</button></div>
      <div v-if="loading" class="loading-state"><span class="spinner"></span>正在读取服务端规则…</div>
      <template v-else>
        <div v-if="rule" class="reminder-rule-meta"><span>规则 #{{ String(rule.rule_id) }}</span><span>版本 {{ snapshot?.mutationCount ?? '未返回' }}</span><span>更新者 #{{ String(rule.updated_by ?? '—') }}</span><span>{{ formatShanghai(rule.updated_at) }}</span></div>
        <p v-else class="followup-alert followup-alert-info">本店尚无规则。提前量与渠道不会自动默认；保存前须明确填写可空值并选择两个渠道。</p>
        <div class="reminder-capabilities"><span>全店重建：{{ batchRebuild || '未返回' }}</span><span>消息发送：{{ delivery === 'IN_APP_ONLY' ? '小程序站内提醒' : delivery || '未返回' }}</span><small>站内提醒到时投递至本人消息列表。短信需单独接入服务商。</small></div>
        <form v-if="canManage" class="form-grid form-grid-2 reminder-rule-form" @submit.prevent="saveRule">
          <label>到诊提前分钟（可空）<input v-model="appointmentLead" type="number" min="0" max="2147483647" step="1" :disabled="saving || !!pending || !!migrationPending || migrationBusy || !storageReadable" /></label>
          <label>复诊提前天数（与时间同时填写/留空）<input v-model="followupLead" type="number" min="0" max="2147483647" step="1" :disabled="saving || !!pending || !!migrationPending || migrationBusy || !storageReadable" /></label>
          <label>复诊发送时间 · 北京时间<input v-model="followupTime" type="time" step="1" :disabled="saving || !!pending || !!migrationPending || migrationBusy || !storageReadable" /></label>
          <label>站内消息渠道<select v-model="inAppChoice" required :disabled="saving || !!pending || !!migrationPending || migrationBusy || !storageReadable"><option value="">明确选择</option><option value="true">启用</option><option value="false">关闭</option></select></label>
          <label>短信渠道<select v-model="smsChoice" required :disabled="saving || !!pending || !!migrationPending || migrationBusy || !storageReadable"><option value="">明确选择</option><option value="true">启用</option><option value="false">关闭</option></select></label>
          <label class="span-2">配置原因<textarea v-model="reason" rows="2" maxlength="1000" required :disabled="saving || !!pending || !!migrationPending || migrationBusy || !storageReadable" placeholder="说明本次门店规则调整依据" /></label>
          <div class="reminder-rule-submit span-2"><small>固定 ruleId {{ rule ? String(rule.rule_id) : draftRuleId }} · expected完整快照mutationCount {{ snapshot?.mutationCount ?? 'null（新建）' }}</small><button class="button button-primary" :disabled="saving || !!pending || !!migrationPending || migrationBusy || !storageReadable">{{ saving ? '正在提交…' : rule ? '保存规则变更' : '创建本店规则' }}</button></div>
        </form>
        <p v-else class="followup-alert followup-alert-warn">当前账号只有规则读取权限。配置编辑仅向本店运营人员或店长显示。</p>
        <div class="followup-alert followup-alert-info"><strong>服务端安全限制</strong><span>如本店存在待发送、发送中或失败消息，原PUT会返回409并保持原规则；只有下方有界迁移预览明确允许时才可确认整店迁移，不提供强制清空入口。</span></div>
        <section v-if="canManage && rule && snapshot" class="migration-panel">
          <div class="section-heading"><div><p class="eyebrow">有界事务 · 原身份可核对</p><h3>迁移本店配置并重排提醒</h3><p class="muted">此操作仅预览并在确认后提交服务端迁移；无后台发送器。对象、历史、资源及动作均由服务端完整扫描，动作展示最多100项。</p></div></div>
          <div class="migration-controls"><small>固定 migrationId {{ draftMigrationId }} · 本店原规则 #{{ String(rule.rule_id) }} · actor由服务端会话绑定</small><div class="action-buttons"><button class="button button-small button-secondary" :disabled="migrationBusy || saving || loading || !!pending || !!migrationPending || !storageReadable" @click="previewMigration()">{{ migrationBusy ? '正在处理…' : '预览整店迁移' }}</button><button v-if="migrationPreview" class="button button-small button-secondary" :disabled="migrationBusy || saving || loading || !storageReadable" @click="queryMigration">按原身份查询预览/历史回执</button></div></div>
          <p v-if="migrationError" class="followup-alert followup-alert-error" role="alert">{{ migrationError }}</p><p v-if="migrationNotice" class="followup-alert followup-alert-info">{{ migrationNotice }}</p>
          <article v-if="migrationPending" class="migration-pending-card"><strong>迁移原请求待核对 · {{ migrationPending.action === 'reminder-migration-preview' ? '预览' : '提交' }} · migrationId {{ migrationPending.operationId }}</strong><p>原账号 #{{ migrationPending.accountId }}、门店 #{{ migrationPending.sourceClinicId }}、固定endpoint、原请求和payload已持久保留。提交原digest：{{ migrationPending.action === 'reminder-migration-commit' ? String(migrationPending.payload.previewDigest ?? '缺失') : '预览请求尚无digest' }}。先GET核对原身份，不自动换号重建。</p><div class="action-buttons"><button class="button button-small button-secondary" :disabled="migrationBusy || !storageReadable" @click="queryMigration">GET查询原预览与回执</button><button v-if="migrationPending.action === 'reminder-migration-preview' && migrationPreviewRetryAllowed" class="button button-small button-secondary" :disabled="migrationBusy || !storageReadable" @click="previewMigration(true)">沿用原身份重试预览</button><button v-if="migrationPending.action === 'reminder-migration-commit' && migrationCheckEmpty" class="button button-small button-primary" :disabled="!canManage || migrationBusy || !storageReadable || !migrationPreview?.summary.commitAllowed || migrationExpired" @click="commitMigration(true)">沿用原migrationId与digest重试提交</button></div></article>
          <article v-if="migrationPreview" class="migration-preview-card" aria-live="polite">
            <header><div><strong>原身份预览 · migrationId {{ migrationPreview.migrationId }}</strong><small>创建 {{ formatShanghai(migrationPreview.createdAt) }} · 到期 {{ formatShanghai(migrationPreview.expiresAt) }}（服务端5分钟有效） · previewDigest {{ migrationPreview.previewDigest }}</small></div><span :class="migrationPreview.summary.commitAllowed && !migrationExpired ? 'badge badge-success' : 'badge badge-warning'">{{ migrationExpired ? '已过期' : migrationPreview.summary.commitAllowed ? '允许确认提交' : '服务端阻断' }}</span></header>
            <div class="migration-counts"><span>业务对象 {{ migrationPreview.summary.businessCount }}</span><span>历史消息 {{ migrationPreview.summary.historyCount }}</span><span>资源行 {{ migrationPreview.summary.resourceCount }}</span><span>全部动作 {{ migrationPreview.summary.actionCount }}</span></div>
            <p v-if="migrationExpired" class="followup-alert followup-alert-error">预览已超过5分钟有效期。旧previewDigest已禁用；需重新读取并新建预览。</p>
            <p v-if="!migrationPreview.summary.commitAllowed" class="followup-alert followup-alert-error">commitAllowed=false：存在服务端阻断项，提交按钮已禁用。不能跳过或只迁移未阻断部分。</p>
            <div v-if="migrationPreview.summary.issues.length" class="migration-issues"><strong>服务端缺项与阻断</strong><article v-for="(item, index) in migrationPreview.summary.issues" :key="`${item.businessType}:${item.businessId}:${index}`"><b>{{ item.businessType }} #{{ item.businessId }} · {{ item.blocking ? '阻断' : '提示' }}</b><small v-for="issue in item.issues" :key="issue">{{ issue }}</small></article></div><p v-else class="muted">服务端未返回缺项。</p>
            <div v-if="migrationPreview.summary.actions.length" class="migration-actions"><strong>动作明细{{ migrationPreview.summary.actionsTruncated ? '（列表已截断）' : '' }}</strong><article v-for="(item, index) in migrationPreview.summary.actions" :key="`${item.businessType}:${item.businessId}:${item.messageId ?? 'new'}:${index}`"><b>{{ item.operation }} · {{ item.businessType }} #{{ item.businessId }} · {{ item.channel }} · {{ item.status }}</b><small>messageId {{ item.messageId ?? '新建待发（预览无ID）' }} · {{ item.plannedAt === 'AT_COMMIT' ? '提交时解析计划时间' : item.plannedAt ? formatShanghai(item.plannedAt) : '无计划时间' }}<span v-if="item.timeMode"> · {{ item.timeMode }}</span></small></article></div>
            <p v-if="migrationPreview.summary.actionsTruncated" class="followup-alert followup-alert-warn">动作总数为 {{ migrationPreview.summary.actionCount }}；这里只展示前100项。截断只影响展示，服务端扫描与执行范围仍为全量。</p>
            <button class="button button-primary" :disabled="!canCommitMigration" @click="commitMigration()">确认保存配置并原子重排整店提醒</button>
          </article>
          <article v-if="migrationReceipt" class="migration-receipt-card"><strong>历史成功回执 · migrationId {{ String(migrationReceipt.migrationId) }}</strong><p>提交时间 {{ formatShanghai(migrationReceipt.committedAt) }} · 原previewDigest {{ String(migrationReceipt.previewDigest) }}</p><p>本回执保存的是提交时历史结果；读取时当前配置在下方独立显示。重排不等于发送。</p><p>动作计数：{{ JSON.stringify(migrationReceipt.actionCounts ?? {}) }} · 新建消息映射 {{ migrationCreatedMessages.length }} 条</p><article v-for="(item, index) in migrationCreatedMessages" :key="`${String(item.messageId)}:${index}`">消息 #{{ String(item.messageId) }} · {{ String(item.businessType) }} #{{ String(item.businessId) }} · {{ String(item.channel) }} · {{ formatShanghai(item.plannedAt) }}</article><p>消息发送状态：{{ String(migrationReceipt.delivery ?? '未返回') }}。没有声称任何消息已经发送。</p></article>
          <article v-if="migrationCurrentSnapshot" class="migration-current-card"><strong>当前配置快照（查询时）</strong><p>ruleId {{ migrationCurrentSnapshot.ruleId }} · mutationCount {{ migrationCurrentSnapshot.mutationCount }} · 更新者 #{{ migrationCurrentSnapshot.updatedBy }} · {{ formatShanghai(migrationCurrentSnapshot.updatedAt) }}</p><p>到诊提前 {{ migrationCurrentSnapshot.appointmentLeadMinutes ?? '未设置' }} 分钟 · 复诊提前 {{ migrationCurrentSnapshot.followupLeadDays ?? '未设置' }} 天 · 北京时间 {{ migrationCurrentSnapshot.followupSendTime ?? '未设置' }} · 站内 {{ migrationCurrentSnapshot.inAppEnabled ? '启用' : '关闭' }} · 短信 {{ migrationCurrentSnapshot.smsEnabled ? '启用' : '关闭' }}</p></article>
          <p v-if="!storageReadable" class="followup-alert followup-alert-error">迁移原请求无法安全持久化或读取；迁移操作已停用。</p>
        </section>
      </template>
    </section>
    <section class="panel reminder-rule-panel">
      <div class="section-heading"><div><p class="eyebrow">对象级操作</p><h2>预览或重建单对象提醒</h2><p class="muted">从现有预约或复诊详情复制对象编号。服务端会重读当前业务和规则，并校验对象实际负责门店。</p></div></div>
      <form class="form-grid form-grid-2" @submit.prevent="openObject"><label>对象类型<select v-model="objectType"><option value="appointment">现有预约</option><option value="followup">现有复诊计划</option></select></label><label>预约/计划ID（字符串）<input v-model="objectIdInput" inputmode="numeric" required placeholder="粘贴详情中的编号" /></label><div class="span-2"><button data-readonly-write class="button button-secondary" type="submit">打开对象提醒核对</button></div></form>
      <ReminderObjectActions v-if="selectedObject" :key="`${identity.accountId}:${clinicId}:${selectedObject.type}:${selectedObject.id}`" :business-type="selectedObject.type" :business-id="selectedObject.id" :clinic-id="clinicId" :identity="identity" />
    </section>
  </section>
</template>

<style scoped>
.migration-panel { display: grid; gap: 14px; margin-top: 22px; padding-top: 18px; border-top: 1px solid var(--line, #dfe6ed); }
.migration-controls, .migration-preview-card header, .migration-counts { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.migration-controls small, .migration-preview-card header small { display: block; color: var(--muted, #64748b); overflow-wrap: anywhere; }
.migration-preview-card, .migration-receipt-card, .migration-current-card, .migration-pending-card { display: grid; gap: 12px; padding: 16px; border: 1px solid var(--line, #dfe6ed); border-radius: 12px; background: var(--surface, #fff); }
.migration-counts { justify-content: flex-start; }
.migration-counts span { padding: 6px 10px; border-radius: 999px; background: var(--surface-muted, #f1f5f9); }
.migration-issues, .migration-actions { display: grid; gap: 8px; }
.migration-issues > article, .migration-actions > article, .migration-receipt-card > article { display: grid; gap: 4px; padding: 9px 11px; border-left: 3px solid var(--line, #dfe6ed); background: var(--surface-muted, #f8fafc); }
.migration-issues small, .migration-actions small { display: block; color: var(--muted, #64748b); overflow-wrap: anywhere; }
.migration-preview-card header > div { min-width: 0; }
.migration-preview-card header small { overflow-wrap: anywhere; }
.migration-preview-card > button { justify-self: start; }
</style>
