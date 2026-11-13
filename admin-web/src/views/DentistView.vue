<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { api, formatShanghai, operationId, sessionToken, shanghaiOffset } from '../api'
import type { Clinic, Identity } from '../api'
import NoticeBar from '../components/NoticeBar.vue'
import StatusBadge from '../components/StatusBadge.vue'
import DentistFollowups from '../components/DentistFollowups.vue'
import FollowupCreateForm from '../components/FollowupCreateForm.vue'
import { isEnabled, loadClinicalCompletionPendingWrite, type PendingWrite } from '../fulfillment-core.js'
import { createClinicalCompletionWriteQueue } from '../fulfillment-write-queue.js'
import { clinicalCompletionScope, hasRoleAtClinic, isCompletionResponseCurrent, parseFollowupCompletionFeedback } from '../followup-core.js'

type Row = Record<string, unknown>
type TreatmentDraft = { treatmentId: string; treatmentTypeId: string; units: string; unitPrice: string; performedAt: string; status: string; note: string }
const props = defineProps<{ clinics: Clinic[]; identity: Identity }>()
const dentistClinics = computed(() => props.clinics.filter((item) => hasRoleAtClinic(props.identity, '牙医', item.clinic_id)))
const clinicId = ref(dentistClinics.value[0]?.clinic_id ?? '')
const workMode = ref<'care' | 'followups'>('care')
const visits = ref<Row[]>([])
const context = ref<Row | null>(null)
const catalogue = ref<Row[]>([])
const selectedId = ref('')
const lines = ref<TreatmentDraft[]>([])
const dateFrom = ref('')
const dateTo = ref('')
const busy = ref(false)
const error = ref('')
const notice = ref('')
const allergyDeclaration = ref('')
const allergen = ref('')
const reaction = ref('')
const allergyReason = ref('')
const completionNote = ref('')
const completionFeedback = ref<ReturnType<typeof parseFollowupCompletionFeedback>>(null)
const completionRequestSnapshot = ref<PendingWrite | null>(null)
const completionStorageReadable = ref(true)
const completionSubmitting = ref(false)
const voiding = ref<{ treatmentId: string; reason: string } | null>(null)
const correction = ref<(TreatmentDraft & { appointmentId: string; reason: string }) | null>(null)
const correctionBusy = ref(false)
const history = ref<Row[] | null>(null)
const historyCursor = ref<string | null>(null)
const historyBusy = ref(false)
let contextRevision = 0
let worklistRevision = 0
let disposed = false
const completionQueue = createClinicalCompletionWriteQueue(() => window.sessionStorage, (record) => api<Row>(record.endpoint, { method: record.method, body: record.payload }))

const appointment = computed(() => context.value?.appointment as Row | undefined)
const patient = computed(() => context.value?.patient as Row | undefined)
const activeAllergies = computed(() => (context.value?.allergies as Row[] | undefined) ?? [])
const treatments = computed(() => (context.value?.treatments as Row[] | undefined) ?? [])
const clinicName = computed(() => props.clinics.find((item) => item.clinic_id === clinicId.value)?.name ?? '门店')
const allergyChecked = computed(() => appointment.value?.allergy_checked_version !== null && String(appointment.value?.allergy_checked_version) === String(patient.value?.allergy_version))
const canRegisterFollowup = computed(() => !!appointment.value && isEnabled(appointment.value.attended) && appointment.value.treatment_status === '已完成' && !!appointment.value.completed_at)
function hasDentistClinic(clinic = clinicId.value) { return !!clinic && dentistClinics.value.some((item) => item.clinic_id === clinic) }

onMounted(() => { setDateRange(); void loadWorklist() })
onUnmounted(() => { disposed = true; contextRevision += 1; worklistRevision += 1; completionFeedback.value = null; completionRequestSnapshot.value = null })
function setDateRange() {
  const now = new Date()
  const shifted = new Date(now.getTime() + 8 * 60 * 60 * 1000)
  dateFrom.value = shifted.toISOString().slice(0, 10)
  const end = new Date(now.getTime() + 8 * 24 * 60 * 60 * 1000)
  dateTo.value = new Date(end.getTime() + 8 * 60 * 60 * 1000).toISOString().slice(0, 10)
}
function rangeOffset(date: string, end = false) { return `${date}T${end ? '23:59:59' : '00:00:00'}+08:00` }
function message(e: unknown) { return e instanceof Error ? e.message : '请求未完成。' }
async function loadWorklist() {
  if (!hasDentistClinic()) { visits.value = []; catalogue.value = []; context.value = null; selectedId.value = ''; busy.value = false; return }
  const requestRevision = ++worklistRevision; const token = sessionToken(); const accountId = props.identity.accountId; const clinic = clinicId.value
  const identity = props.identity
  const current = () => !disposed && requestRevision === worklistRevision && token === sessionToken() && accountId === props.identity.accountId && identity === props.identity && clinic === clinicId.value && hasDentistClinic(clinic)
  busy.value = true; error.value = ''
  try {
    const from = rangeOffset(dateFrom.value); const to = rangeOffset(dateTo.value, true)
    const nextVisits = await api<Row[]>(`/dentist/appointments?clinicId=${encodeURIComponent(clinic)}&from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`)
    if (!current()) return
    visits.value = nextVisits
    const nextCatalogue = await api<Row[]>(`/dentist/treatment-types?clinicId=${encodeURIComponent(clinic)}`)
    if (current()) catalogue.value = nextCatalogue
  } catch (e) { if (current()) error.value = message(e) }
  finally { if (current()) busy.value = false }
}
async function openVisit(row: Row) { await openContext(String(row.appointment_id)) }
async function openContext(id: string) {
  history.value = null
  historyCursor.value = null
  historyBusy.value = false
  if (!hasDentistClinic()) return 'stale' as const
  const requestRevision = ++contextRevision; const token = sessionToken(); const accountId = props.identity.accountId; const clinic = clinicId.value
  const identity = props.identity
  const current = () => !disposed && requestRevision === contextRevision && id === selectedId.value && token === sessionToken() && accountId === props.identity.accountId && identity === props.identity && clinic === clinicId.value && hasDentistClinic(clinic) && workMode.value === 'care'
  selectedId.value = id; context.value = null; busy.value = true; error.value = ''; notice.value = ''; completionFeedback.value = null; completionRequestSnapshot.value = null; completionStorageReadable.value = true
  try {
    const nextContext = await api<Row>(`/dentist/appointments/${encodeURIComponent(id)}`)
    if (!current()) return 'stale' as const
    context.value = nextContext
    restoreCompletionRequest(id)
    const appointmentData = context.value.appointment as Row
    const patientData = context.value.patient as Row
    allergyDeclaration.value = String(patientData.allergy_declaration ?? '')
    const first = ((context.value.allergies as Row[]) ?? [])[0]
    allergen.value = first ? String(first.substance ?? '') : ''; reaction.value = first ? String(first.reaction_note ?? '') : ''
    allergyReason.value = ''; completionNote.value = String(appointmentData.completion_note ?? '')
    lines.value = ((context.value.treatments as Row[]) ?? []).filter((line) => line.status !== '作废').map((line) => ({
      treatmentId: String(line.treatment_id), treatmentTypeId: String(line.treatment_type_id), units: String(line.units), unitPrice: String(line.unit_price),
      performedAt: localInput(String(line.performed_at)), status: String(line.status), note: String(line.note ?? ''),
    }))
    return 'loaded' as const
  } catch (e) { if (current()) { context.value = null; error.value = message(e); return 'failed' as const }; return 'stale' as const }
  finally { if (current()) busy.value = false }
}
async function loadHistory(append = false) {
  if (!appointment.value || historyBusy.value) return
  const revision = contextRevision
  const id = selectedId.value
  const token = sessionToken()
  historyBusy.value = true
  try {
    const suffix = append && historyCursor.value ? '?beforeId=' + encodeURIComponent(historyCursor.value) : ''
    const result = await api<{ items: Row[]; nextBeforeId: string | null }>(`/dentist/appointments/${id}/patient-history${suffix}`)
    if (disposed || revision !== contextRevision || token !== sessionToken()) return
    history.value = append ? [...(history.value || []), ...result.items] : result.items
    historyCursor.value = result.nextBeforeId
  } catch (e) {
    if (!disposed && revision === contextRevision) error.value = message(e)
  } finally {
    if (revision === contextRevision) historyBusy.value = false
  }
}
function switchWorkMode(mode: 'care' | 'followups') {
  if (workMode.value === mode) return
  contextRevision += 1
  completionFeedback.value = null
  completionRequestSnapshot.value = null
  workMode.value = mode
  if (mode === 'care' && selectedId.value) restoreCompletionRequest(selectedId.value)
}
function restoreCompletionRequest(id: string) {
  try {
    completionRequestSnapshot.value = loadClinicalCompletionPendingWrite(window.sessionStorage, clinicalCompletionScope(props.identity.accountId, clinicId.value, id))
    completionStorageReadable.value = true
  } catch {
    completionRequestSnapshot.value = null
    completionStorageReadable.value = false
    error.value = '无法读取本就诊的完成待核对原请求；已停用新的完成写入。请恢复会话存储并重新读取。'
  }
}
function localInput(value: string) { const date = new Date(value); return new Date(date.getTime() + 8 * 60 * 60 * 1000).toISOString().slice(0, 16) }
function addLine() {
  const item = catalogue.value[0]
  if (!item) { error.value = '牙医目录暂无启用项目。'; return }
  const now = new Date(Date.now() + 8 * 60 * 60 * 1000).toISOString().slice(0, 16)
  lines.value.push({ treatmentId: operationId('T').slice(2), treatmentTypeId: String(item.treatment_type_id), units: '1', unitPrice: String(item.standard_unit_price), performedAt: now, status: '草稿', note: '' })
}
function updateLineType(line: TreatmentDraft) {
  const type = catalogue.value.find((item) => String(item.treatment_type_id) === line.treatmentTypeId)
  if (type) { line.unitPrice = String(type.standard_unit_price); if (!type.allows_fraction && line.units.includes('.')) line.units = '1' }
}
async function changeAllergies() {
  if (!hasDentistClinic() || !appointment.value || !patient.value) return
  error.value = ''; notice.value = ''
  const items = allergyDeclaration.value === '已报告过敏' ? [{ substance: allergen.value.trim(), reactionNote: reaction.value.trim() || null }] : []
  try {
    await api(`/staff/appointments/${selectedId.value}/allergies`, { method: 'PUT', body: { expectedRowVersion: appointment.value.row_version, expectedAllergyVersion: patient.value.allergy_version, declaration: allergyDeclaration.value, items, reason: allergyReason.value.trim() } })
    notice.value = '过敏主档已更新并保留变更依据。请继续签署本次最新快照。'; await openContext(selectedId.value)
  } catch (e) { error.value = message(e) }
}
async function checkAllergies() {
  if (!hasDentistClinic() || !appointment.value || !patient.value) return
  error.value = ''; notice.value = ''
  try {
    await api(`/staff/appointments/${selectedId.value}/allergy-check`, { method: 'POST', body: { expectedRowVersion: appointment.value.row_version, allergyVersion: patient.value.allergy_version } })
    notice.value = '已签署本次过敏快照。'; await openContext(selectedId.value)
  } catch (e) { error.value = message(e); if (['ROW_VERSION_CONFLICT','ALLERGY_VERSION_CONFLICT'].includes(String((e as { code?: string }).code))) await openContext(selectedId.value) }
}
async function saveLines() {
  if (!hasDentistClinic() || !appointment.value || !patient.value) return
  error.value = ''; notice.value = ''
  const payload = lines.value.map((line) => ({ treatmentId: line.treatmentId, treatmentTypeId: line.treatmentTypeId, units: line.units, unitPrice: line.unitPrice, performedAt: shanghaiOffset(line.performedAt), status: line.status, note: line.note.trim() || null }))
  try {
    await api(`/staff/appointments/${selectedId.value}/treatments`, { method: 'PUT', body: { expectedRowVersion: appointment.value.row_version, allergyVersion: patient.value.allergy_version, lines: payload } })
    notice.value = '治疗集合已保存。'; await openContext(selectedId.value)
  } catch (e) { error.value = message(e); if (['ROW_VERSION_CONFLICT','ALLERGY_VERSION_CONFLICT','TREATMENT_SET_CONFLICT'].includes(String((e as { code?: string }).code))) await openContext(selectedId.value) }
}
async function voidTreatment() {
  if (!hasDentistClinic() || !voiding.value || !appointment.value || !patient.value) return
  const target = voiding.value; error.value = ''; notice.value = ''
  try {
    await api(`/staff/appointments/${selectedId.value}/treatments/${target.treatmentId}/void`, { method: 'POST', body: { expectedRowVersion: appointment.value.row_version, allergyVersion: patient.value.allergy_version, reason: target.reason.trim() } })
    lines.value = lines.value.filter((line) => line.treatmentId !== target.treatmentId); voiding.value = null; notice.value = '已作废并保留原治疗及原因。需要更正时可新增一条替代治疗。'; await openContext(selectedId.value)
  } catch (e) { error.value = message(e); if (String((e as { code?: string }).code) === 'ROW_VERSION_CONFLICT') await openContext(selectedId.value) }
}
async function correctTreatment() {
  const entry = correction.value
  if (!entry || entry.appointmentId !== selectedId.value || !appointment.value || correctionBusy.value || props.identity.readOnly) return
  const revision = contextRevision
  correctionBusy.value = true
  error.value = ''; notice.value = ''
  try {
    await api(`/staff/appointments/${entry.appointmentId}/treatments/${entry.treatmentId}/correction`, {
      method: 'POST', body: { expectedRowVersion: appointment.value.row_version, units: entry.units, unitPrice: entry.unitPrice, reason: entry.reason.trim() },
    })
    if (disposed || revision !== contextRevision) return
    correction.value = null
    notice.value = '治疗价量已更正，已有结算已同步。'
    await openContext(entry.appointmentId)
  } catch (e) {
    if (!disposed && revision === contextRevision) error.value = message(e)
  } finally {
    correctionBusy.value = false
  }
}
async function completeVisit() {
  if (!hasDentistClinic() || !appointment.value || !patient.value || completionSubmitting.value || completionRequestSnapshot.value || !completionStorageReadable.value) return
  completionSubmitting.value = true
  error.value = ''; notice.value = ''; completionFeedback.value = null
  const id = selectedId.value; const accountId = props.identity.accountId; const clinic = clinicId.value; const token = sessionToken(); const requestRevision = contextRevision
  const payload = { expectedRowVersion: appointment.value.row_version, allergyVersion: patient.value.allergy_version, completionNote: completionNote.value.trim() }
  const scope = { contextRevision: requestRevision, appointmentId: id, accountId, clinicId: clinic, sessionToken: token }
  const identity = props.identity
  const current = () => identity === props.identity && hasDentistClinic(clinic) && isCompletionResponseCurrent(scope, { contextRevision, appointmentId: selectedId.value, accountId: props.identity.accountId, clinicId: clinicId.value, sessionToken: sessionToken(), disposed, workMode: workMode.value })
  try {
    const result = await completionQueue.submit(clinicalCompletionScope(accountId, clinic, id), { endpoint: `/staff/appointments/${encodeURIComponent(id)}/completion`, method: 'POST', payload, operationId: `completion-${id}-${String(payload.expectedRowVersion)}` })
    if (!current()) return
    if (result.kind === 'storage-error') {
      completionStorageReadable.value = false
      error.value = '无法安全保存本次完成原请求；没有发送。请恢复会话存储后重新读取。'
      return
    }
    if (result.kind === 'pending-exists') {
      restoreCompletionRequest(id)
      error.value = '已有本就诊完成原请求待核对；请沿用原payload重试或先人工核对，不能另发新内容。'
      return
    }
    if (result.kind !== 'success') {
      if (result.kind === 'unknown' || result.kind === 'preserved') {
        completionRequestSnapshot.value = result.record ?? null
        error.value = '本次诊疗完成请求结果未知。原payload已按账号、门店和就诊范围保留；请先核对服务端状态，不能更换内容重发。'
      } else {
        error.value = result.error instanceof Error ? message(result.error) : '本次完成请求未发送。'
        if (['ROW_VERSION_CONFLICT','ALLERGY_VERSION_CONFLICT'].includes(String((result.error as { code?: string } | undefined)?.code))) await openContext(id)
      }
      return
    }
    const response = result.result ?? {}
    const feedback = parseFollowupCompletionFeedback(response.followup)
    const refreshed = await openContext(id)
    if (refreshed === 'stale' || selectedId.value !== id || accountId !== props.identity.accountId || clinic !== clinicId.value || token !== sessionToken() || disposed || workMode.value !== 'care') return
    completionRequestSnapshot.value = null
    completionFeedback.value = feedback
    notice.value = '诊疗完成已由牙医签署。结算请交由前台发出。'
    await loadWorklist()
  } finally { completionSubmitting.value = false }
}

async function retryCompletionRequest() {
  const record = completionRequestSnapshot.value
  if (!hasDentistClinic() || !record || completionSubmitting.value || !completionStorageReadable.value) return
  const id = selectedId.value; const accountId = props.identity.accountId; const clinic = clinicId.value; const token = sessionToken(); const requestRevision = contextRevision
  const scope = { contextRevision: requestRevision, appointmentId: id, accountId, clinicId: clinic, sessionToken: token }
  const identity = props.identity
  const current = () => identity === props.identity && hasDentistClinic(clinic) && isCompletionResponseCurrent(scope, { contextRevision, appointmentId: selectedId.value, accountId: props.identity.accountId, clinicId: clinicId.value, sessionToken: sessionToken(), disposed, workMode: workMode.value })
  completionSubmitting.value = true; error.value = ''
  try {
    const result = await completionQueue.retry(clinicalCompletionScope(accountId, clinic, id), record)
    if (!current()) return
    if (result.kind === 'success') {
      const feedback = parseFollowupCompletionFeedback(result.result?.followup)
      const refreshed = await openContext(id)
      if (refreshed === 'stale' || selectedId.value !== id || accountId !== props.identity.accountId || clinic !== clinicId.value || token !== sessionToken() || disposed || workMode.value !== 'care') return
      completionFeedback.value = feedback
      notice.value = '诊疗完成已由牙医签署。结算请交由前台发出。'
      await loadWorklist()
    } else if (result.kind === 'preserved' || result.kind === 'unknown') {
      completionRequestSnapshot.value = result.record ?? record
      error.value = '原完成请求仍未确认；已保留同一payload。请再次核对服务端状态，不要编辑或重建请求。'
    } else if (result.kind === 'storage-error') {
      completionStorageReadable.value = false
      error.value = '无法安全读取或更新原完成请求；未生成新请求。请恢复会话存储。'
    } else {
      completionRequestSnapshot.value = loadClinicalCompletionPendingWrite(window.sessionStorage, clinicalCompletionScope(accountId, clinic, id))
      error.value = '原请求记录已变化或不属于当前就诊；没有发送新请求。'
    }
  } finally { completionSubmitting.value = false }
}
</script>

<template>
  <section v-if="!dentistClinics.length" class="panel empty-state"><div class="empty-icon">⌂</div><strong>当前没有可访问的牙医门店</strong><span>牙医接诊入口仍保留。请由账号管理员确认本人牙医岗位及门店范围；当前不会读取预约或治疗目录。</span></section>
  <div v-else class="page-stack">
    <nav v-if="dentistClinics.length" class="followup-work-tabs" aria-label="牙医工作台分区"><button type="button" :class="{ active: workMode === 'care' }" @click="switchWorkMode('care')">本人接诊</button><button type="button" :class="{ active: workMode === 'followups' }" @click="switchWorkMode('followups')">本人复诊计划</button></nav>
    <template v-if="workMode === 'care'">
    <div class="page-heading"><div><p class="eyebrow">牙医工作台 · {{ clinicName }}</p><h1>我的接诊</h1><p class="muted">仅显示当前登录牙医本人负责的预约与临床上下文。</p></div><div class="date-range"><label>开始日期<input v-model="dateFrom" type="date" /></label><label>结束日期<input v-model="dateTo" type="date" /></label><button class="button button-secondary" @click="loadWorklist">查询</button></div></div>
    <NoticeBar tone="error" :title="error" @close="error = ''" /><NoticeBar tone="success" :title="notice" @close="notice = ''" />
    <section v-if="completionRequestSnapshot" class="followup-alert followup-alert-warn"><strong>诊疗完成结果待核对</strong><span>原提交快照已按账号/门店/就诊保存：预约 #{{ completionRequestSnapshot.appointmentId }} · 版本 {{ String(completionRequestSnapshot.payload.expectedRowVersion) }}。重新读取只会恢复此原请求，不会因列表状态清除记录。</span><button class="button button-small button-secondary" :disabled="completionSubmitting || !completionStorageReadable" @click="retryCompletionRequest">沿用原payload安全重试</button></section>
    <section v-if="!completionStorageReadable" class="followup-alert followup-alert-error"><strong>本机待核对存储不可读</strong><span>已停用新的完成写入和重试；恢复会话存储后重新读取。</span></section>
    <section v-if="completionFeedback" class="followup-alert followup-alert-warn"><strong>{{ completionFeedback.outcomeText }}</strong><span v-if="completionFeedback.planId">复诊计划 #{{ completionFeedback.planId }}<span v-if="completionFeedback.status"> · 服务端状态 {{ completionFeedback.status }}</span></span><span v-if="completionFeedback.completedAppointmentId">完成关联就诊 #{{ completionFeedback.completedAppointmentId }}<span v-if="completionFeedback.completedAt"> · {{ formatShanghai(completionFeedback.completedAt) }}</span></span><span v-if="completionFeedback.planReconciliationRequired">计划仍需结案核对，当前反馈不代表计划已完成。</span><span v-if="completionFeedback.reminderReconciliationRequired || completionFeedback.unresolvedReminderCount !== '0'">有 {{ completionFeedback.unresolvedReminderCount }} 条提醒状态待核实；没有据此声称提醒已发送。</span><span v-if="completionFeedback.reminderScheduling === 'NOT_IMPLEMENTED'">提醒重建/发送尚未实现；没有重建或发送提醒。</span></section>
    <section class="panel">
      <div class="section-heading"><div><h2>预约列表</h2><p class="muted">{{ visits.length }} 条本人负责的记录 · 时间按 Asia/Shanghai 显示</p></div><button class="button button-quiet" @click="loadWorklist">↻ 刷新</button></div>
      <div v-if="busy && !context" class="loading-state"><span class="spinner"></span>正在读取本人接诊…</div>
      <div v-else-if="!visits.length" class="empty-state"><div class="empty-icon">◷</div><strong>当前范围没有预约</strong><span>可调整日期范围，或等待前台完成真实预约。</span></div>
      <div v-else class="table-wrap"><table><thead><tr><th>预约编号</th><th>患者</th><th>时段</th><th>需求</th><th>到诊</th><th>诊疗</th><th></th></tr></thead><tbody><tr v-for="row in visits" :key="String(row.appointment_id)" :class="{ 'row-selected': String(row.appointment_id) === selectedId }"><td><strong>{{ row.appointment_no }}</strong></td><td>{{ row.patient_name }}<small class="cell-sub">{{ row.patient_no }}</small></td><td>{{ formatShanghai(row.scheduled_start) }}<small class="cell-sub">至 {{ formatShanghai(row.scheduled_end) }}</small></td><td class="truncate-cell">{{ row.requested_treatments }}</td><td><StatusBadge :value="String(row.appointment_status)" /></td><td><StatusBadge :value="String(row.treatment_status)" /></td><td><button class="button button-small button-secondary" @click="openVisit(row)">{{ String(row.appointment_id) === selectedId ? '当前就诊' : '打开就诊' }}</button></td></tr></tbody></table></div>
    </section>

    <section v-if="context && appointment && patient" class="clinical-layout">
      <div class="clinical-main">
        <section class="panel">
          <div class="visit-header"><div><p class="eyebrow">本次临床上下文</p><h2>{{ patient.name }} <span class="patient-code">{{ patient.patient_no }}</span></h2><p class="muted">预约 {{ appointment.appointment_no }} · {{ formatShanghai(appointment.scheduled_start) }} · {{ appointment.requested_treatments }}</p></div><StatusBadge :value="String(appointment.treatment_status)" /></div>
          <div class="clinical-facts"><div><span>到诊状态</span><strong>{{ appointment.attended ? `已到诊 · ${formatShanghai(appointment.arrived_at)}` : '尚未到诊' }}</strong></div><div><span>主档过敏版本</span><strong>v{{ patient.allergy_version }} · {{ patient.allergy_declaration }}</strong></div><div><span>本次快照</span><strong>{{ allergyChecked ? `已核对 v${appointment.allergy_checked_version}` : '未签署最新快照' }}</strong></div></div>
        </section>

        <section class="panel allergy-panel">
          <div class="section-heading"><div><h2>过敏与安全核对</h2><p class="muted">主档申报与本次签署快照分开记录；“未询问”不会推断为无过敏。</p></div><span class="safety-mark">＋</span></div>
          <div v-if="activeAllergies.length" class="allergy-list"><div v-for="item in activeAllergies" :key="String(item.allergy_id ?? item.substance)" class="allergy-item"><span class="allergy-dot"></span><span><strong>{{ item.substance }}</strong><small>{{ item.reaction_note || '未提供反应说明' }}</small></span><StatusBadge :value="String(item.verification_status)" /></div></div>
          <p v-else class="empty-inline">当前申报没有有效过敏条目。请确认主档申报状态是否准确。</p>
          <div class="form-grid form-grid-2 allergy-form">
            <label>过敏申报<select v-model="allergyDeclaration"><option value="未询问">未询问</option><option value="无已知过敏">无已知过敏</option><option value="已报告过敏">已报告过敏</option></select></label>
            <template v-if="allergyDeclaration === '已报告过敏'"><label>过敏物质<input v-model="allergen" required maxlength="100" /></label><label class="span-2">反应说明<input v-model="reaction" maxlength="255" placeholder="由患者报告；可留空" /></label></template>
            <label class="span-2">申报变更原因<input v-model="allergyReason" maxlength="8000" placeholder="必填，用于保留本次核实依据" /></label>
            <div class="form-actions span-2"><button class="button button-secondary" :disabled="!appointment.attended || appointment.treatment_status === '已完成' || allergyDeclaration === '未询问'" @click="changeAllergies">保存过敏申报</button><button class="button button-primary" :disabled="!appointment.attended || allergyDeclaration === '未询问' || allergyChecked || appointment.treatment_status === '已完成'" @click="checkAllergies">签署本次快照</button></div>
          </div>
          <p v-if="appointment.allergy_snapshot" class="snapshot-line"><span class="dot dot-green"></span>已留存本次签署快照：{{ String(appointment.allergy_snapshot).slice(0, 160) }}</p>
        </section>

        <section class="panel treatment-panel">
          <div class="section-heading"><div><h2>实际治疗记录</h2><p class="muted">本次全部未作废明细 · 单价与数量按字符串保存，零价实际治疗可记录。</p></div><button data-readonly-write class="button button-secondary" :disabled="!allergyChecked || appointment.treatment_status === '已完成'" @click="addLine">＋ 添加治疗</button></div>
          <div v-if="!lines.length" class="empty-inline">当前没有有效治疗行。确无实际治疗时可保留零行并填写完成说明。</div>
          <div v-for="(line, index) in lines" :key="line.treatmentId" class="treatment-card">
            <div class="treatment-card-head"><div class="treatment-name"><span class="line-number">{{ String(index + 1).padStart(2, '0') }}</span><span><strong>{{ catalogue.find((item) => String(item.treatment_type_id) === line.treatmentTypeId)?.name ?? '治疗项目' }}</strong><small>ID {{ line.treatmentId }}</small></span></div><div class="treatment-head-actions"><StatusBadge :value="line.status" /><button v-if="appointment.treatment_status !== '已完成'" class="text-button text-danger" @click="voiding = { treatmentId: line.treatmentId, reason: '' }">作废留痕</button></div></div>
            <div class="form-grid treatment-fields">
              <label>治疗项目<select v-model="line.treatmentTypeId" :disabled="line.status === '确认' || appointment.treatment_status === '已完成'" @change="updateLineType(line)"><option v-for="type in catalogue" :key="String(type.treatment_type_id)" :value="String(type.treatment_type_id)">{{ type.name }} · {{ type.unit_name }}</option></select></label>
              <label>数量<input v-model="line.units" inputmode="decimal" :disabled="line.status === '确认' || appointment.treatment_status === '已完成'" /></label>
              <label>单价（元）<input v-model="line.unitPrice" inputmode="decimal" :disabled="line.status === '确认' || appointment.treatment_status === '已完成'" /></label>
              <label>实际实施时间 · 上海时间<input v-model="line.performedAt" type="datetime-local" :disabled="line.status === '确认' || appointment.treatment_status === '已完成'" /></label>
              <label>记录状态<select v-model="line.status" :disabled="appointment.treatment_status === '已完成' || line.status === '确认'"><option>草稿</option><option>确认</option></select></label>
              <label>临床说明<input v-model="line.note" :disabled="line.status === '确认' || appointment.treatment_status === '已完成'" maxlength="8000" /></label>
            </div>
          </div>
          <div v-if="lines.some((line) => line.status !== '确认') || lines.length" class="treatment-actions"><span class="muted tiny">确认治疗后内容只读；更正须填写原因作废并以新ID补录。</span><button class="button button-secondary" :disabled="!allergyChecked || appointment.treatment_status === '已完成'" @click="saveLines">保存全部治疗行</button></div>
          <div v-if="appointment.treatment_status==='已完成' && !identity.readOnly && lines.some(line=>line.status==='确认')" class="treatment-actions">
            <button v-for="line in lines.filter(item=>item.status==='确认')" :key="line.treatmentId" data-readonly-write class="button button-secondary" :disabled="correctionBusy" @click="correction={...line,appointmentId:selectedId,reason:''}">更正 {{ catalogue.find(item=>String(item.treatment_type_id)===line.treatmentTypeId)?.name ?? '治疗价量' }}</button>
          </div>
          <form v-if="correction && correction.appointmentId===selectedId" class="form-grid" @submit.prevent="correctTreatment">
            <label>更正数量<input v-model="correction.units" required inputmode="decimal" :disabled="correctionBusy"></label>
            <label>更正单价<input v-model="correction.unitPrice" required inputmode="decimal" :disabled="correctionBusy"></label>
            <label>更正原因<input v-model="correction.reason" required maxlength="500" :disabled="correctionBusy"></label>
            <div class="form-actions">
              <button type="button" class="secondary" :disabled="correctionBusy" @click="correction=null">返回</button>
              <button data-readonly-write type="submit" :disabled="correctionBusy">保存更正并同步结算</button>
            </div>
          </form>
          <div v-if="treatments.some((line) => line.status === '作废')" class="history-note"><strong>历史留痕</strong><span>本次有 {{ treatments.filter((line) => line.status === '作废').length }} 条已作废治疗记录，原记录及原因仍保留在服务端。</span></div>
        </section>

        <section v-if="appointment.appointment_status==='已到诊'" class="panel">
          <h2>跨店既往治疗</h2>
          <button v-if="history===null" class="secondary" :disabled="historyBusy" @click="loadHistory()">读取既往记录</button>
          <p v-else-if="!history.length" class="muted">暂无其他已完成就诊。</p>
          <details v-for="visit in history" :key="String(visit.appointment_id)">
            <summary>{{ formatShanghai(visit.completed_at) }} · {{ visit.clinic_name }} · {{ visit.dentist_name }}</summary>
            <p>{{ visit.completion_note }}</p>
            <ul><li v-for="line in (visit.treatments as Row[])" :key="String(line.treatment_id)">{{ line.name_snapshot }} · {{ line.units }} {{ line.unit_snapshot }}<span v-if="line.note"> · {{ line.note }}</span></li></ul>
          </details>
          <button v-if="historyCursor" class="secondary" :disabled="historyBusy" @click="loadHistory(true)">继续读取</button>
        </section>
        <section class="panel completion-panel"><div class="section-heading"><div><h2>确认本次完成</h2><p class="muted">到诊不代表诊疗完成。请由负责牙医核对后明确签署完成说明。</p></div><span class="complete-icon">✓</span></div><label>完成说明<textarea v-model="completionNote" rows="3" maxlength="8000" placeholder="记录本次实际接诊结果；无治疗时说明确无实施项目的原因" :disabled="appointment.treatment_status === '已完成'" /></label><div class="completion-footer"><div class="ready-list"><span :class="appointment.attended ? 'ready' : ''">{{ appointment.attended ? '✓' : '○' }} 已到诊</span><span :class="allergyChecked ? 'ready' : ''">{{ allergyChecked ? '✓' : '○' }} 最新过敏快照</span><span :class="!lines.some((line) => line.status === '草稿') ? 'ready' : ''">{{ !lines.some((line) => line.status === '草稿') ? '✓' : '○' }} 治疗草稿已处理</span></div><button data-readonly-write class="button button-primary" :disabled="completionSubmitting || !!completionRequestSnapshot || !completionStorageReadable || appointment.treatment_status === '已完成' || !completionNote.trim()" @click="completeVisit">{{ completionSubmitting ? '正在核实…' : appointment.treatment_status === '已完成' ? '已完成' : '牙医确认完成' }}</button></div></section>
        <FollowupCreateForm v-if="canRegisterFollowup" :key="`${identity.accountId}:${clinicId}:${selectedId}`" :appointment="appointment" :clinic-id="clinicId" :identity="identity" />
      </div>
      <aside class="clinical-aside"><section class="panel summary-card"><p class="eyebrow">患者概况</p><h3>{{ patient.name }}</h3><dl><div><dt>患者编号</dt><dd>{{ patient.patient_no }}</dd></div><div><dt>出生日期</dt><dd>{{ patient.birth_date }}</dd></div><div><dt>需求备注</dt><dd>{{ appointment.special_needs || '无特别说明' }}</dd></div><div><dt>预约来源</dt><dd>{{ appointment.booking_channel }}</dd></div></dl><div class="scope-note">🔒 仅本人负责本次就诊可见</div></section><section class="panel workflow-card"><p class="eyebrow">流程状态</p><ol class="workflow-list"><li class="is-done"><span>1</span><div><strong>前台登记到诊</strong><small>{{ formatShanghai(appointment.arrived_at) }}</small></div></li><li :class="appointment.allergy_checked_version !== null ? 'is-done' : 'is-current'"><span>2</span><div><strong>过敏申报与本次核对</strong><small>{{ allergyChecked ? '快照已签署' : '待牙医核实' }}</small></div></li><li :class="appointment.treatment_status === '已完成' ? 'is-done' : 'is-current'"><span>3</span><div><strong>实际治疗与完成</strong><small>{{ appointment.treatment_status }}</small></div></li><li :class="appointment.invoice ? 'is-done' : ''"><span>4</span><div><strong>前台结算</strong><small>由前台发出唯一结算</small></div></li></ol></section></aside>
    </section>
    <div v-if="voiding" class="modal-backdrop" @click.self="voiding = null"><section class="modal-card" role="dialog" aria-modal="true" aria-labelledby="void-title"><div class="modal-header"><div><p class="eyebrow">保留原行与签署信息</p><h2 id="void-title">作废治疗记录</h2></div><button class="icon-button" aria-label="关闭" @click="voiding = null">×</button></div><p class="muted">作废后原行不会删除。若需要更正，请新增使用稳定ID的替代治疗。</p><label>作废原因<textarea v-model="voiding.reason" rows="3" required placeholder="说明录入错误或更正依据" /></label><div class="modal-actions"><button class="button button-quiet" @click="voiding = null">返回</button><button data-readonly-write class="button button-danger" :disabled="!voiding.reason.trim()" @click="voidTreatment">确认作废并保留依据</button></div></section></div>
    </template>
    <DentistFollowups v-else-if="dentistClinics.length" :clinics="clinics" :identity="identity" />
  </div>
</template>
