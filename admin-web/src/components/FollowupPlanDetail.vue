<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { api, formatShanghai, sessionToken } from '../api'
import type { Clinic, Identity } from '../api'
import NoticeBar from './NoticeBar.vue'
import StatusBadge from './StatusBadge.vue'
import FollowupAppointmentForm from './FollowupAppointmentForm.vue'
import ReminderObjectActions from './ReminderObjectActions.vue'
import {
  assertFollowupAppointmentAssociation, canArrangeFollowupAppointment, cursorQuery, formatFollowupError, followupScope, generatePositive63BitId, makeFollowupContactPayload, mergeIdCursorPage,
  preserveFollowupSnapshot, shanghaiInputValue,
} from '../followup-core.js'
import { createFollowupWriteQueue } from '../fulfillment-write-queue.js'
import { loadFollowupPendingWrites, removePendingWrite, type PendingWrite } from '../fulfillment-core.js'

type Row = Record<string, unknown>
type Mode = 'staff' | 'dentist'
const props = defineProps<{ mode: Mode; planId: string; clinicId: string; identity: Identity; clinics: Clinic[] }>()
const emit = defineEmits<{ close: []; changed: [] }>()
const plan = ref<Row | null>(null)
const snapshot = ref<Row | null>(null)
const patient = ref<Row | null>(null)
const appointments = ref<Row[]>([])
const appointmentsTruncated = ref(false)
const recentContacts = ref<Row[]>([])
const recentContactsTruncated = ref(false)
const completionReconciliationRequired = ref(false)
const loading = ref(true)
const saving = ref(false)
const loadError = ref('')
const actionError = ref('')
const notice = ref('')
const suggestedDate = ref('')
const recommendation = ref('')
const maintenanceReason = ref('')
const cancelReason = ref('')
const conflictNeedsReview = ref(false)
const conflictReviewed = ref(false)
const contactFormOpen = ref(false)
const contactId = ref('')
const contactedAt = ref('')
const contactResult = ref('')
const contactNote = ref('')
const nextContactAt = ref('')
const contactHistoryOpen = ref(false)
const contactHistory = ref<Row[]>([])
const contactHistoryHasMore = ref(false)
const contactHistoryAfterId = ref<string | null>(null)
const contactHistoryLoading = ref(false)
const pendingWrites = ref<PendingWrite[]>([])
const storageReadable = ref(true)
const manualVerifiedKey = ref('')
let revision = 0
let historyRevision = 0
let disposed = false
const writeQueue = createFollowupWriteQueue(() => window.sessionStorage, async (record) => {
  const result = await api<Record<string, unknown>>(record.endpoint, { method: record.method, body: record.payload })
  if (record.action === 'followup-appointment') {
    const expectedPlanId = String(record.appointmentId).replace(/^plan:/, '')
    assertFollowupAppointmentAssociation(expectedPlanId, result)
  }
  return result
})

const entityId = computed(() => String(props.planId))
const endpoint = computed(() => `/${props.mode}/followup-plans/${encodeURIComponent(entityId.value)}`)
const hasPendingWrite = computed(() => pendingWrites.value.length > 0)
const completedReconciliation = computed(() => completionReconciliationRequired.value === true)
const canMaintain = computed(() => props.mode === 'dentist' && plan.value?.status === '待复诊' && !completedReconciliation.value)
const canContact = computed(() => props.mode === 'staff' && plan.value?.status === '待复诊')
const ownerClinicId = computed(() => String(plan.value?.clinic_id ?? props.clinicId))
const canArrangeAppointment = computed(() => props.mode === 'staff' && canArrangeFollowupAppointment(props.identity, String(plan.value?.status ?? ''), ownerClinicId.value, ownerClinicId.value))

onMounted(() => { loadPendingWrites(); void loadDetail() })
onUnmounted(() => { disposed = true; revision += 1; historyRevision += 1 })
watch([() => props.identity.accountId, () => props.clinicId, entityId], () => {
  revision += 1; historyRevision += 1
  plan.value = null; snapshot.value = null; patient.value = null; appointments.value = []; appointmentsTruncated.value = false; recentContacts.value = []; recentContactsTruncated.value = false
  pendingWrites.value = []; contactHistory.value = []; contactHistoryOpen.value = false
  suggestedDate.value = ''; recommendation.value = ''; maintenanceReason.value = ''; cancelReason.value = ''
  contactFormOpen.value = false; conflictNeedsReview.value = false; conflictReviewed.value = false
  storageReadable.value = true; actionError.value = ''; notice.value = ''
  loadPendingWrites(); void loadDetail()
})

function requestCurrent(token: string | null, accountId: string, requestRevision: number) {
  return !disposed && token === sessionToken() && accountId === props.identity.accountId && requestRevision === revision
}
function message(error: unknown) { return formatFollowupError(error) }
function scope(action: 'followup-update' | 'followup-cancel' | 'followup-contact' | 'followup-appointment') {
  return followupScope(props.identity.accountId, ownerClinicId.value, `plan:${entityId.value}`, action)
}
function loadPendingWrites() {
  try {
    pendingWrites.value = loadFollowupPendingWrites(window.sessionStorage, { accountId: props.identity.accountId, sourceClinicId: ownerClinicId.value, appointmentId: `plan:${entityId.value}` }).filter((item) => item.action !== 'followup-create')
    storageReadable.value = true
    return true
  } catch {
    storageReadable.value = false
    actionError.value = '无法读取本机复诊待核对请求；已停用写入。请恢复会话存储后重新打开计划。'
    return false
  }
}
function installDetail(detail: Row, preserveForm: boolean) {
  plan.value = detail.plan as Row
  snapshot.value = preserveFollowupSnapshot(detail.snapshot)
  patient.value = detail.patient as Row
  const appointmentPage = detail.appointments as Row[] | Row | null
  appointments.value = Array.isArray(appointmentPage) ? appointmentPage : ((appointmentPage?.items as Row[]) ?? [])
  appointmentsTruncated.value = !Array.isArray(appointmentPage) && appointmentPage?.truncated === true
  const contactPage = detail.contacts as Row[] | Row | null
  recentContacts.value = Array.isArray(contactPage) ? contactPage : ((contactPage?.items as Row[]) ?? [])
  recentContactsTruncated.value = !Array.isArray(contactPage) && contactPage?.truncated === true
  completionReconciliationRequired.value = detail.completionReconciliationRequired === true
  loadPendingWrites()
  if (!preserveForm) {
    suggestedDate.value = String(plan.value.suggested_date ?? '')
    recommendation.value = String(plan.value.recommendation ?? '')
    maintenanceReason.value = ''; cancelReason.value = ''
  }
}
async function loadDetail(preserveForm = false) {
  const requestRevision = ++revision
  const token = sessionToken()
  const accountId = props.identity.accountId
  loading.value = true; loadError.value = ''
  try {
    const detail = await api<Row>(endpoint.value)
    if (!requestCurrent(token, accountId, requestRevision)) return
    installDetail(detail, preserveForm)
  } catch (error) {
    if (requestCurrent(token, accountId, requestRevision)) loadError.value = message(error)
  } finally {
    if (requestCurrent(token, accountId, requestRevision)) loading.value = false
  }
}

function beginContact() {
  contactId.value = generatePositive63BitId()
  contactedAt.value = shanghaiInputValue()
  contactResult.value = ''; contactNote.value = ''; nextContactAt.value = ''
  actionError.value = ''; contactFormOpen.value = true
}
function contactPayload() {
  return makeFollowupContactPayload({ contactId: contactId.value, contactedAtLocal: contactedAt.value, result: contactResult.value, note: contactNote.value, nextContactAtLocal: nextContactAt.value })
}
async function submitContact() {
  if (!canContact.value || !storageReadable.value || hasPendingWrite.value || saving.value) return
  let payload: Record<string, unknown>
  try { payload = contactPayload() } catch (error) { actionError.value = message(error); return }
  await sendWrite('followup-contact', `/staff/followup-plans/${encodeURIComponent(entityId.value)}/contacts`, payload)
}
async function submitPlanAppointment(payload: Record<string, string>) {
  if (!canArrangeAppointment.value || !storageReadable.value || hasPendingWrite.value || saving.value) return
  await sendWrite('followup-appointment', `/staff/followup-plans/${encodeURIComponent(entityId.value)}/appointments`, payload)
}
async function submitUpdate() {
  if (!canMaintain.value || !storageReadable.value || hasPendingWrite.value || saving.value) return
  if (conflictNeedsReview.value && !conflictReviewed.value) { actionError.value = '请先核对冲突后取得的最新完整快照，再提交维护。'; return }
  if (!snapshot.value || !suggestedDate.value || !recommendation.value.trim() || !maintenanceReason.value.trim()) { actionError.value = '建议日期、建议说明和维护原因均必填。'; return }
  const payload = { expected: preserveFollowupSnapshot(snapshot.value), suggestedDate: suggestedDate.value, recommendation: recommendation.value.trim(), reason: maintenanceReason.value.trim() }
  await sendWrite('followup-update', `/dentist/followup-plans/${encodeURIComponent(entityId.value)}`, payload)
}
async function submitCancellation() {
  if (!canMaintain.value || !storageReadable.value || hasPendingWrite.value || saving.value) return
  if (conflictNeedsReview.value && !conflictReviewed.value) { actionError.value = '请先核对冲突后取得的最新完整快照，再取消计划。'; return }
  if (!snapshot.value || !cancelReason.value.trim()) { actionError.value = '请提供取消原因。'; return }
  await sendWrite('followup-cancel', `/dentist/followup-plans/${encodeURIComponent(entityId.value)}/cancellation`, { expected: preserveFollowupSnapshot(snapshot.value), reason: cancelReason.value.trim() })
}

async function sendWrite(action: 'followup-update' | 'followup-cancel' | 'followup-contact' | 'followup-appointment', path: string, payload: Record<string, unknown>) {
  if (saving.value) return
  saving.value = true; actionError.value = ''; notice.value = ''
  const token = sessionToken(); const accountId = props.identity.accountId; const requestRevision = revision
  const result = await writeQueue.submit(scope(action), { endpoint: path, method: action === 'followup-update' ? 'PUT' : 'POST', payload, ...(action === 'followup-appointment' ? { operationId: String(payload.appointmentNo ?? '') } : {}) })
  await finishWrite(result, token, accountId, requestRevision)
}
async function retryPending(record: PendingWrite) {
  if (saving.value || !storageReadable.value) return
  saving.value = true; actionError.value = ''
  const token = sessionToken(); const accountId = props.identity.accountId; const requestRevision = revision
  const result = await writeQueue.retry(scope(record.action as 'followup-update' | 'followup-cancel' | 'followup-contact' | 'followup-appointment'), record)
  await finishWrite(result, token, accountId, requestRevision)
}
async function finishWrite(outcome: Awaited<ReturnType<typeof writeQueue.submit>>, token: string | null, accountId: string, requestRevision: number) {
  const current = () => requestCurrent(token, accountId, requestRevision)
  if (outcome.kind === 'storage-error') {
    storageReadable.value = false
    if (current()) actionError.value = '无法安全保存或读取复诊原请求；本次未发送或仍待核对。请恢复会话存储。'
  } else if (outcome.kind === 'busy' || outcome.kind === 'pending-exists' || outcome.kind === 'missing') {
    loadPendingWrites()
    if (current()) actionError.value = outcome.kind === 'pending-exists' ? '本计划已有待核对原请求；已阻止覆盖或另发新操作。' : outcome.kind === 'busy' ? '本计划已有提交正在处理。' : '原请求已变化或不属于当前账号/计划；未发送。'
  } else if (outcome.kind === 'unknown' || outcome.kind === 'preserved') {
    loadPendingWrites()
    if (current()) actionError.value = (outcome.error as { code?: string } | undefined)?.code === 'FOLLOWUP_ASSOCIATION_UNCONFIRMED'
      ? '预约服务返回了成功响应，但未核实其归属本计划。原payload和固定预约号已保留；请先人工核对预约与计划关系，再沿用原请求重试。'
      : '服务端未确认本次复诊写入结果。原payload、固定ID与期望快照已保留；请先读取计划/联系状态，再沿用原请求重试。'
  } else if (outcome.kind === 'rejected') {
    loadPendingWrites()
    if (current()) {
      const code = String((outcome.error as { code?: string }).code ?? '')
      actionError.value = message(outcome.error)
      if (code === 'FOLLOWUP_CONFLICT') {
        conflictNeedsReview.value = true; conflictReviewed.value = false
        await loadDetail(true)
      } else if (code === 'FOLLOWUP_COMPLETION_PENDING' || code === 'REMINDER_RECONCILIATION_REQUIRED') await loadDetail(true)
    }
  } else if (outcome.kind === 'success') {
    loadPendingWrites()
    if (current()) {
      const result = outcome.result ?? {}
      const reminderState = String(result.reminderScheduling ?? '')
      if (outcome.record?.action === 'followup-contact') {
        const status = String(result.planStatus ?? '待复诊')
        notice.value = `电话联系已追加；复诊计划状态保持${status}。未发送短信或站内提醒。`
        contactFormOpen.value = false
      } else if (outcome.record?.action === 'followup-appointment') {
        notice.value = `复诊预约已由服务端关联到计划 #${entityId.value}。计划负责门店仍为 #${ownerClinicId.value}，目标门店 #${String(outcome.record?.payload.clinicId ?? '')}。`
      } else if (outcome.record?.action === 'followup-cancel') {
        notice.value = `复诊计划已由服务端取消；撤销旧待发提醒 ${String(result.cancelledReminderCount ?? '0')} 条。${reminderState === 'NOT_IMPLEMENTED' ? '提醒重建与发送尚未实现。' : ''}`
      } else {
        notice.value = `复诊计划已由服务端更新。${reminderState === 'NOT_IMPLEMENTED' ? '提醒重建与发送尚未实现，不能视为已排程或已发送。' : ''}`
      }
      conflictNeedsReview.value = false; conflictReviewed.value = false
      emit('changed')
      await loadDetail(false)
    }
  }
  saving.value = false
}

async function loadAllContactHistory(reset = false) {
  if (props.mode !== 'staff' || contactHistoryLoading.value) return
  const requestRevision = ++historyRevision
  const token = sessionToken(); const accountId = props.identity.accountId; const planId = entityId.value; const clinicId = props.clinicId
  const afterId = reset ? null : contactHistoryAfterId.value
  contactHistoryLoading.value = true; actionError.value = ''
  try {
    const query = cursorQuery({ limit: 30 }, afterId)
    const response = await api<{ items: Row[]; hasMore: boolean; nextAfterId: string | null }>(`/staff/followup-plans/${encodeURIComponent(planId)}/contacts?${query}`)
    if (disposed || token !== sessionToken() || accountId !== props.identity.accountId || clinicId !== props.clinicId || planId !== entityId.value || requestRevision !== historyRevision) return
    const page = mergeIdCursorPage(reset ? [] : contactHistory.value, response, !reset)
    contactHistory.value = page.items; contactHistoryHasMore.value = page.hasMore; contactHistoryAfterId.value = page.nextAfterId
    contactHistoryOpen.value = true
  } catch (error) {
    if (!disposed && token === sessionToken() && accountId === props.identity.accountId && planId === entityId.value) actionError.value = message(error)
  } finally {
    if (!disposed && requestRevision === historyRevision) contactHistoryLoading.value = false
  }
}
function manuallyResolve(record: PendingWrite) {
  if (manualVerifiedKey.value !== record.key) { manualVerifiedKey.value = record.key; return }
  try { removePendingWrite(window.sessionStorage, record) }
  catch { storageReadable.value = false; actionError.value = '无法清除本机待核对提示；原请求仍保留。'; return }
  manualVerifiedKey.value = ''; loadPendingWrites()
  actionError.value = '仅清除了本机提示，没有修改服务端计划或联系历史。请以刚读取的服务端详情为准。'
}
function dateText(value: unknown) { return value ? String(value).slice(0, 10) : '—' }
function resultText(value: unknown) { return String(value ?? '') }
</script>

<template>
  <div class="modal-backdrop followup-backdrop" @click.self="emit('close')">
    <section class="followup-dialog" role="dialog" aria-modal="true" aria-labelledby="followup-detail-title">
      <header class="followup-dialog-head"><div><p class="eyebrow">{{ mode === 'staff' ? '前台复诊跟进' : '牙医复诊维护' }} · {{ mode === 'staff' ? `门店 #${clinicId}` : '本人负责' }}</p><h2 id="followup-detail-title">复诊计划 #{{ planId }}</h2><p v-if="patient" class="muted">{{ patient.name }} · {{ patient.patient_no }}<span v-if="mode === 'staff' && patient.phone"> · 电话 {{ patient.phone }}</span></p></div><button class="icon-button" type="button" aria-label="关闭复诊计划详情" @click="emit('close')">×</button></header>
      <div class="followup-dialog-body">
        <NoticeBar tone="error" :title="loadError || actionError" />
        <NoticeBar tone="success" :title="notice" @close="notice = ''" />
        <div v-if="loading" class="loading-state"><span class="spinner"></span>正在读取最新复诊计划与完整快照…</div>
        <template v-else-if="plan && snapshot">
          <section class="followup-summary-card">
            <div class="followup-summary-head"><div><StatusBadge :value="String(plan.status)" /><span v-if="plan.overdue === true" class="badge badge-warning">服务端标记逾期</span><span v-else-if="plan.overdue === false && plan.status === '待复诊'" class="badge badge-neutral">尚未逾期</span></div><button class="button button-quiet" type="button" :disabled="loading" @click="loadDetail(true)">↻ 重读最新状态</button></div>
            <dl><div><dt>建议日期</dt><dd>{{ dateText(plan.suggested_date) }}</dd></div><div><dt>建议内容</dt><dd>{{ plan.recommendation }}</dd></div><div><dt>来源就诊</dt><dd>#{{ plan.source_appointment_id }}</dd></div><div><dt>维护次数</dt><dd>{{ snapshot.mutationCount }}（服务端原字符串）</dd></div><div v-if="plan.cancel_reason"><dt>取消原因</dt><dd>{{ plan.cancel_reason }}</dd></div><div v-if="plan.completed_at"><dt>实际完成关联</dt><dd>就诊 #{{ plan.completed_appointment_id }} · {{ formatShanghai(plan.completed_at) }}</dd></div></dl>
          </section>
          <div v-if="completedReconciliation" class="followup-alert followup-alert-warn"><strong>需要实际完成结案核对</strong><span>服务端发现真实关联就诊已完成；本页面不提供伪造完成按钮，计划状态维持服务端原值，等待下一段完成关联hook。</span></div>
          <section class="followup-subpanel"><div class="section-heading"><div><h3>关联有效预约</h3><p class="muted">联系成功或已约待诊都不等于复诊完成，也不会移除待复诊计划。</p></div></div><div v-if="appointments.length" class="followup-appointment-list"><article v-for="item in appointments" :key="String(item.appointment_id)"><strong>预约 #{{ item.appointment_id }}</strong><span>{{ dateText(item.scheduled_start) }} · {{ formatShanghai(item.scheduled_start) }}–{{ formatShanghai(item.scheduled_end) }}</span><span>{{ item.appointment_status }} / {{ item.treatment_status }}</span><span v-if="item.completed_at">真实完成 {{ formatShanghai(item.completed_at) }} · 需按服务端结案核对</span></article></div><div v-else class="empty-inline">当前没有关联的有效后续预约。预约自动关联hook尚未接入。</div><small v-if="appointmentsTruncated" class="muted">关联预约多于详情展示数量；服务端已明确标记截断。本页面不会将截断列表称为完整列表。</small></section>

          <section class="followup-subpanel"><div class="section-heading"><div><h3>最近联系</h3><p class="muted">{{ recentContacts.length }} 条详情返回的最近实际联系，不代表系统发送提醒。</p></div><button v-if="mode === 'staff'" class="button button-small button-quiet" type="button" :disabled="contactHistoryLoading" @click="loadAllContactHistory(true)">{{ contactHistoryLoading ? '读取中…' : '打开分页联系历史' }}</button></div><div v-if="recentContacts.length" class="followup-contact-list"><article v-for="contact in recentContacts" :key="String(contact.contact_id)"><div><strong>{{ formatShanghai(contact.contacted_at) }} · {{ resultText(contact.result) }}</strong><p>{{ contact.note }}</p><small>电话 · 账号 #{{ contact.responsible_account_id }}<span v-if="contact.next_contact_at"> · 下次联系 {{ formatShanghai(contact.next_contact_at) }}</span></small></div><code>{{ contact.contact_id }}</code></article></div><div v-else class="empty-inline">尚无人工联系记录。</div><small v-if="recentContactsTruncated" class="muted">详情只展示最近联系；使用分页接口读取完整历史。</small>
            <div v-if="contactHistoryOpen && mode === 'staff'" class="followup-history"><div v-if="contactHistory.length" class="followup-contact-list"><article v-for="contact in contactHistory" :key="String(contact.contact_id)"><div><strong>{{ formatShanghai(contact.contacted_at) }} · {{ resultText(contact.result) }}</strong><p>{{ contact.note }}</p><small>电话 · 账号 #{{ contact.responsible_account_id }}<span v-if="contact.next_contact_at"> · 下次联系 {{ formatShanghai(contact.next_contact_at) }}</span></small></div><code>{{ contact.contact_id }}</code></article></div><div v-else class="empty-inline">联系历史为空。</div><button v-if="contactHistoryHasMore" class="button button-secondary" type="button" :disabled="contactHistoryLoading" @click="loadAllContactHistory()">{{ contactHistoryLoading ? '读取中…' : '加载更早联系（仍有后页）' }}</button></div>
          </section>

          <FollowupAppointmentForm v-if="canArrangeAppointment" :key="`${ownerClinicId}:${entityId}`" :plan="plan" :clinics="clinics" :identity="identity" :disabled="saving || hasPendingWrite || !storageReadable" @submit="submitPlanAppointment" />
          <ReminderObjectActions v-if="mode === 'staff'" :key="`reminder:${ownerClinicId}:${entityId}`" business-type="followup" :business-id="entityId" :clinic-id="ownerClinicId" :identity="identity" />
          <section v-if="canContact" class="followup-subpanel"><div class="section-heading"><div><h3>记录实际电话</h3><p class="muted">只追加联系记录，不改计划状态、不新建预约、不发送短信/站内消息。</p></div><button class="button button-secondary" type="button" :disabled="hasPendingWrite || !storageReadable" @click="beginContact">{{ contactFormOpen ? '重新填写' : '＋ 记录电话' }}</button></div><form v-if="contactFormOpen && !hasPendingWrite" class="form-grid form-grid-2 followup-form" @submit.prevent="submitContact"><label>实际联系时间 · 上海<input v-model="contactedAt" type="datetime-local" required /></label><label>联系结果<select v-model="contactResult" required><option value="">选择实际结果</option><option>未接通</option><option>已提醒</option><option>已约待诊</option><option>需调整</option><option>暂不复诊</option><option>其他</option></select></label><label class="span-2">联系说明<textarea v-model="contactNote" rows="3" maxlength="2000" required /></label><label>下次联系时间（选填）<input v-model="nextContactAt" type="datetime-local" /></label><div class="followup-form-actions span-2"><small>固定联系ID {{ contactId }} · 联系时间提交为含 +08:00 的整秒时间。</small><button class="button button-primary" :disabled="saving || hasPendingWrite || !storageReadable">保存联系记录</button></div></form></section>

          <section v-if="canMaintain" class="followup-subpanel"><div class="section-heading"><div><h3>更新复诊建议</h3><p class="muted">提交完整服务端expected快照。编辑仅改建议日期/说明，不能更换来源患者、牙医、门店或状态。</p></div></div><div v-if="conflictNeedsReview" class="followup-alert followup-alert-warn"><label class="followup-review-check"><input v-model="conflictReviewed" type="checkbox" />我已核对冲突后刷新到的最新完整计划状态与快照。</label></div><form class="form-grid form-grid-2 followup-form" @submit.prevent="submitUpdate"><label>建议复诊日期<input v-model="suggestedDate" type="date" required /></label><label class="span-2">建议内容<textarea v-model="recommendation" rows="3" maxlength="2000" required /></label><label class="span-2">维护原因<textarea v-model="maintenanceReason" rows="2" maxlength="2000" required /></label><div class="followup-form-actions span-2"><small>expected.mutationCount = {{ snapshot.mutationCount }}（保持字符串；全快照随请求提交）。成功后提醒重建为 NOT_IMPLEMENTED。</small><button class="button button-primary" :disabled="saving || hasPendingWrite || !storageReadable || completedReconciliation || (conflictNeedsReview && !conflictReviewed)">保存建议</button></div></form><form class="form-grid form-grid-2 followup-form followup-cancel-form" @submit.prevent="submitCancellation"><label class="span-2">取消原因<textarea v-model="cancelReason" rows="2" maxlength="2000" required /></label><div class="followup-form-actions span-2"><small>只取消待复诊计划并保留全部联系/审计历史；发送中或待核实提醒会由服务端拒绝。</small><button class="button button-danger" :disabled="saving || hasPendingWrite || !storageReadable || completedReconciliation || (conflictNeedsReview && !conflictReviewed)">有原因取消计划</button></div></form></section>
        </template>
        <div v-else class="empty-state"><strong>计划详情不可用</strong><span>核对权限和服务端返回后重试。</span></div>

        <section v-if="pendingWrites.length" class="followup-pending-card"><div class="section-heading"><div><h3>待核对的原复诊请求</h3><p class="muted">未知结果保留固定payload、ID和expected快照；不生成新操作号。</p></div></div><article v-for="record in pendingWrites" :key="record.key"><div><strong>{{ record.action }} · {{ record.operationId }}</strong><small>状态 {{ record.state }} · {{ new Date(record.createdAt).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' }) }}</small></div><div class="followup-pending-actions"><button class="button button-small button-secondary" :disabled="saving || !storageReadable" @click="retryPending(record)">沿用原请求重试</button><button class="text-button" @click="manuallyResolve(record)">{{ manualVerifiedKey === record.key ? '确认服务端已核对并清除本机提示' : '我已核对服务端…' }}</button></div></article><small class="muted">清除本机提示不更改服务端记录。</small></section>
        <p v-if="!storageReadable" class="followup-alert followup-alert-error">无法读取本机复诊请求，所有写入已停用。</p>
      </div>
      <footer class="followup-dialog-footer"><small>计划状态和结案只以服务端真实记录为准；本页面不提供“复诊已完成”操作。</small><button class="button button-quiet" type="button" @click="emit('close')">关闭</button></footer>
    </section>
  </div>
</template>
