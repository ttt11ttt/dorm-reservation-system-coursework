<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { api, formatShanghai, operationId, sessionToken } from '../api'
import type { Clinic, Identity } from '../api'
import NoticeBar from './NoticeBar.vue'
import StatusBadge from './StatusBadge.vue'
import RescheduleForm from './RescheduleForm.vue'
import ReminderObjectActions from './ReminderObjectActions.vue'
import {
  canCancelOrReschedule, canVerifyNoShow, hasActualVisitRecord, hasFrontDeskGrant,
  loadFulfillmentPendingWrites, makeReviewPayload, removePendingWrite,
} from '../fulfillment-core.js'
import { createFulfillmentWriteQueue } from '../fulfillment-write-queue.js'
import type { FulfillmentAction, PendingWrite } from '../fulfillment-core.js'

type Row = Record<string, unknown>
type ReschedulePayload = Record<string, unknown>
type Decision = { expectedRowVersion: number; reason: string; responsibility: '患者' | '诊所' | '误录'; exempt: boolean }
const props = defineProps<{ sourceRow: Row; clinics: Clinic[]; identity: Identity }>()
const emit = defineEmits<{ close: []; updated: [] }>()
const appointment = ref<Row | null>(null)
const invoice = ref<Row | null>(null)
const event = ref<Row | null>(null)
const bookingStatus = ref<Row | null>(null)
const loading = ref(true)
const loadError = ref('')
const actionError = ref('')
const notice = ref('')
const action = ref<'reschedule' | 'cancel' | 'no-show' | 'review' | null>(null)
const reason = ref('')
const correctionOpen = ref(false)
const correctionReason = ref('')
const correctionBusy = ref(false)
const responsibility = ref<'患者' | '诊所' | '误录' | ''>('')
const exempt = ref(false)
const reviewValid = ref(false)
const reviewExempt = ref(false)
const reviewReason = ref('')
const confirmAction = ref(false)
const pendingWrites = ref<PendingWrite[]>([])
const pendingStorageReadable = ref(true)
const sendingKey = ref('')
const manualVerifiedKey = ref('')
const clock = ref(Date.now())
const rescheduleNo = ref(operationId('RES'))
let readRevision = 0
let disposed = false
let timer: number | undefined
const writeQueue = createFulfillmentWriteQueue(() => window.sessionStorage, (record) => api<Record<string, unknown>>(record.endpoint, { method: record.method, body: record.payload }))

const appointmentId = computed(() => String(props.sourceRow.appointment_id))
const sourceClinicId = computed(() => String(props.sourceRow.clinic_id))
const sourceClinicName = computed(() => props.clinics.find((clinic) => clinic.clinic_id === sourceClinicId.value)?.name ?? `门店 #${sourceClinicId.value}`)
const protectedRecord = computed(() => hasActualVisitRecord(appointment.value, invoice.value))
const canChange = computed(() => canCancelOrReschedule(appointment.value, invoice.value))
const canNoShow = computed(() => canVerifyNoShow(appointment.value, invoice.value, clock.value))
const canReviewEvent = computed(() => !!event.value && hasFrontDeskGrant(props.identity, sourceClinicId.value))
const hasPendingWrite = computed(() => pendingWrites.value.length > 0)
const restriction = computed(() => bookingStatus.value?.restriction as Row | null | undefined)
const eligibleEventCount = computed(() => Number(bookingStatus.value?.eligibleEventCount ?? 0))
const eventIsReviewedUnchanged = computed(() => !!event.value
  && truth(event.value.valid) === reviewValid.value
  && truth(event.value.exempt) === reviewExempt.value
  && String(event.value.responsibility_reason ?? '') === reviewReason.value.trim())

watch([() => props.identity.accountId, sourceClinicId, appointmentId], () => {
  pendingWrites.value = []
  pendingStorageReadable.value = true
  action.value = null
  confirmAction.value = false
  correctionOpen.value = false
  correctionReason.value = ''
  correctionBusy.value = false
  actionError.value = ''
  notice.value = ''
  loadPendingWrites()
  void refresh()
})

onMounted(() => {
  loadPendingWrites()
  void refresh()
  timer = window.setInterval(() => { clock.value = Date.now() }, 15_000)
})
onUnmounted(() => { disposed = true; readRevision += 1; if (timer !== undefined) window.clearInterval(timer) })

function scope(actionName: FulfillmentAction) {
  return { accountId: props.identity.accountId, sourceClinicId: sourceClinicId.value, appointmentId: appointmentId.value, action: actionName }
}
function current(token: string | null, revision?: number, expectedAccountId = props.identity.accountId) {
  return !disposed && props.identity.accountId === expectedAccountId && sessionToken() === token && (revision === undefined || revision === readRevision)
}
function message(error: unknown) { return error instanceof Error ? error.message : '操作未完成。' }
async function correctResult() {
  if (!appointment.value || correctionBusy.value || !correctionReason.value.trim() || props.identity.readOnly) return
  const id = appointmentId.value
  const token = sessionToken()
  const revision = readRevision
  correctionBusy.value = true
  actionError.value = ''
  try {
    await api(`/staff/appointments/${id}/result-correction`, { method:'POST', body:{
      expectedRowVersion:Number(appointment.value.row_version), reason:correctionReason.value.trim()
    } })
    if (current(token, revision)) {
      correctionOpen.value = false
      correctionReason.value = ''
      notice.value = '原登记已撤回，请重新核实就诊结果。'
      emit('updated')
      await refresh(true)
    }
  } catch (e) {
    if (current(token, revision)) actionError.value = message(e)
  } finally {
    if (!disposed && id===appointmentId.value) correctionBusy.value = false
  }
}
function loadPendingWrites() {
  try {
    pendingWrites.value = loadFulfillmentPendingWrites(window.sessionStorage, { accountId: props.identity.accountId, sourceClinicId: sourceClinicId.value, appointmentId: appointmentId.value })
    pendingStorageReadable.value = true
    return true
  } catch {
    pendingStorageReadable.value = false
    actionError.value = '无法读取本机待核对请求记录。请保留当前页面并刷新服务端详情，暂不要重复办理。'
    return false
  }
}
async function refresh(quiet = false) {
  const revision = ++readRevision
  const token = sessionToken()
  const accountId = props.identity.accountId
  if (!quiet) { loading.value = true; loadError.value = '' }
  try {
    const [fulfillment, administrative] = await Promise.all([
      api<{ appointment: Row; event: Row | null; bookingStatus: Row }>(`/staff/appointments/${encodeURIComponent(appointmentId.value)}/fulfillment`),
      api<{ appointment: Row; invoice: Row | null }>(`/staff/appointments/${encodeURIComponent(appointmentId.value)}`),
    ])
    if (!current(token, revision, accountId)) return
    if (String(fulfillment.appointment.appointment_id) !== appointmentId.value) return
    appointment.value = fulfillment.appointment
    event.value = fulfillment.event
    bookingStatus.value = fulfillment.bookingStatus
    invoice.value = administrative.invoice
    if (event.value && action.value !== 'review') {
      reviewValid.value = truth(event.value.valid)
      reviewExempt.value = truth(event.value.exempt)
      reviewReason.value = String(event.value.responsibility_reason ?? '')
    }
  } catch (cause) {
    if (current(token, revision, accountId)) loadError.value = message(cause)
  } finally {
    if (current(token, revision, accountId) && !quiet) loading.value = false
  }
}
function openAction(next: 'reschedule' | 'cancel' | 'no-show' | 'review') {
  if (hasPendingWrite.value || !pendingStorageReadable.value || sendingKey.value || loading.value) return
  actionError.value = ''; notice.value = ''; confirmAction.value = false; action.value = next
  reason.value = ''; responsibility.value = ''; exempt.value = false
  if (next === 'review' && event.value) {
    reviewValid.value = truth(event.value.valid)
    reviewExempt.value = truth(event.value.exempt)
    reviewReason.value = String(event.value.responsibility_reason ?? '')
  }
  if (next === 'reschedule') rescheduleNo.value = operationId('RES')
}
function rowVersion() {
  const version = Number(appointment.value?.row_version)
  return Number.isInteger(version) && version >= 0 ? version : null
}
function reviewDecision() {
  actionError.value = ''
  if (!appointment.value || rowVersion() === null) { actionError.value = '预约详情缺少有效版本号，请重新读取。'; return }
  if (!reason.value.trim() || !responsibility.value) { actionError.value = '请填写原因并明确责任归属。'; return }
  confirmAction.value = true
}
function reviewEvent() {
  actionError.value = ''
  if (!event.value || !canReviewEvent.value) { actionError.value = '只能复核该事件来源门店且已获权的预约事件。'; return }
  if (!reviewReason.value.trim()) { actionError.value = '复核原因必填。'; return }
  if (eventIsReviewedUnchanged.value) { actionError.value = '复核值与服务端当前记录相同；如需再次说明，请补充新的具体依据。'; return }
  confirmAction.value = true
}
function decisionPayload(): Decision | null {
  const version = rowVersion()
  if (version === null || !reason.value.trim() || !responsibility.value) return null
  return { expectedRowVersion: version, reason: reason.value.trim(), responsibility: responsibility.value, exempt: exempt.value }
}
async function submitDecision() {
  if (action.value !== 'cancel' && action.value !== 'no-show') return
  const payload = decisionPayload()
  if (!payload) { actionError.value = '请检查版本号、原因和责任归属。'; return }
  if (protectedRecord.value) { actionError.value = '预约已有真实到诊、治疗或结算记录；保留历史，不作取消或误录清除。'; return }
  if (action.value === 'no-show' && !canNoShow.value) { actionError.value = '只有在预约结束时间严格过去且尚无实际到诊记录时，前台才能人工核实未到诊。'; return }
  const kind = action.value
  const endpoint = `/staff/appointments/${encodeURIComponent(appointmentId.value)}/${kind === 'no-show' ? 'no-show' : 'cancel'}`
  await queueWrite(kind, endpoint, payload)
}
async function submitReschedule(payload: ReschedulePayload) {
  if (!canChange.value) { actionError.value = '该预约已有实际就诊/治疗/结算记录，不能通过普通改约覆盖。'; return }
  await queueWrite('reschedule', `/staff/appointments/${encodeURIComponent(appointmentId.value)}/reschedule`, payload)
}
async function submitEventReview() {
  if (!event.value || !canReviewEvent.value) return
  const payload = makeReviewPayload({ valid: reviewValid.value, exempt: reviewExempt.value, reason: reviewReason.value })
  if (!payload.reason) { actionError.value = '复核原因必填。'; return }
  await queueWrite('review', `/staff/breaches/${encodeURIComponent(String(event.value.breach_id))}/review`, payload)
}
async function queueWrite(actionName: FulfillmentAction, endpoint: string, payload: Record<string, unknown>) {
  if (sendingKey.value) { actionError.value = '本预约已有提交正在处理，请等待结果。'; return }
  if (!pendingStorageReadable.value) { actionError.value = '无法安全读取待核对请求，未发送新操作。'; return }
  sendingKey.value = `queue:${actionName}`
  actionError.value = ''; notice.value = ''
  const token = sessionToken()
  const writeScope = scope(actionName)
  const accountId = props.identity.accountId
  const result = await writeQueue.submit(writeScope, { endpoint, method: 'POST', payload })
  await finishWrite(result, token, accountId)
}
async function retryPending(record: PendingWrite) {
  if (sendingKey.value) { actionError.value = '本预约已有提交正在处理，请等待结果。'; return }
  if (!pendingStorageReadable.value) { actionError.value = '无法安全读取待核对请求，未发送重试。'; return }
  sendingKey.value = `retry:${record.key}`
  const token = sessionToken()
  const accountId = props.identity.accountId
  const result = await writeQueue.retry(scope(record.action), record)
  await finishWrite(result, token, accountId)
}
async function finishWrite(outcome: Awaited<ReturnType<typeof writeQueue.submit>>, token: string | null, accountId: string) {
  const isCurrentScope = () => !disposed && props.identity.accountId === accountId && sessionToken() === token
  if (outcome.kind === 'storage-error') {
    pendingStorageReadable.value = false
    if (isCurrentScope()) actionError.value = '无法安全读取或保存待核对请求，本次未发送。请恢复会话存储后再核对。'
  } else if (outcome.kind === 'busy' || outcome.kind === 'pending-exists' || outcome.kind === 'missing') {
    loadPendingWrites()
    if (isCurrentScope()) actionError.value = outcome.kind === 'pending-exists'
      ? '本预约已有待核对原请求；已阻止覆盖或另发新操作。请先核对或沿用原请求。'
      : outcome.kind === 'busy' ? '本预约已有提交正在处理，请等待结果。' : '待核对请求已变化或不属于当前账号/预约；未发送。请重新读取。'
  } else if (outcome.kind === 'unknown' || outcome.kind === 'preserved') {
    loadPendingWrites()
    if (isCurrentScope()) {
      confirmAction.value = false
      const status = Number((outcome.error as { status?: unknown } | undefined)?.status)
      actionError.value = status === 401 || status === 403
        ? '原请求此前结果未知；本次认证/权限校验未通过，因此完整原请求仍保留。恢复原账号及授权后先核对状态，再沿用原请求重试。'
        : '服务端未确认本次写入结果。原请求、操作号与内容已保留；请先读取预约/事件状态，再决定是否沿用原请求重试。'
    }
  } else if (outcome.kind === 'rejected') {
    loadPendingWrites()
    if (isCurrentScope()) {
      confirmAction.value = false
      actionError.value = message(outcome.error)
      if (Number((outcome.error as { status?: number }).status) === 409) await refresh(true)
    }
  } else if (outcome.kind === 'success') {
    loadPendingWrites()
    if (isCurrentScope()) {
      const record = outcome.record!
      const result = outcome.result ?? {}
      action.value = null; confirmAction.value = false
      if (record.action === 'reschedule') {
        const next = result.appointment as Row | undefined
        notice.value = result.replayed === true ? '服务端确认这是同一次改约请求；已返回原后继预约。' : `改约已由服务端确认；新预约编号 ${String(next?.appointment_no ?? '已返回')}。`
      } else if (record.action === 'cancel') notice.value = result.replayed === true ? '服务端确认取消已办理。' : `已按${String(record.payload.responsibility)}责任记录取消，原预约历史保留。`
      else if (record.action === 'no-show') notice.value = result.replayed === true ? '服务端确认未到诊核实已办理。' : '已由前台人工核实未到诊；事件时间取原预约结束时间。'
      else notice.value = result.replayed === true ? '服务端确认当前事件复核内容已生效。' : '事件责任/豁免复核已由来源门店服务端受理。'
      await refresh(true)
      emit('updated')
    }
  }
  sendingKey.value = ''
}
async function reloadForPending() {
  actionError.value = ''
  await refresh()
  if (pendingWrites.value.length) actionError.value = '履约与预约状态已重新读取。待核对请求仍保留；只有确认原请求未生效或需安全重放时，才可选择原请求重试。'
}
function manuallyResolve(record: PendingWrite) {
  if (manualVerifiedKey.value !== record.key) { manualVerifiedKey.value = record.key; return }
  try { removePendingWrite(window.sessionStorage, record) }
  catch { pendingStorageReadable.value = false; actionError.value = '无法清除本机提示；原请求仍保留。请恢复会话存储后再核对。'; return }
  manualVerifiedKey.value = ''
  loadPendingWrites()
  actionError.value = '仅清除了本机待核对提示，没有更改服务端预约或履约记录。请继续以最新服务端详情为准。'
}
function actionLabel(value: string) {
  return ({ reschedule: '改约', cancel: '取消', 'no-show': '未到诊核实', review: '事件复核' } as Record<string, string>)[value] ?? value
}
function pendingSummary(record: PendingWrite) {
  if (record.action === 'reschedule') return `目标门店 #${String(record.payload.clinicId)} · ${formatShanghai(record.payload.scheduledStart)} · ${String(record.payload.reason ?? '')}`
  return `${String(record.payload.responsibility ?? '')} · ${String(record.payload.reason ?? '')}`
}
function truth(value: unknown) { return value === true || value === 1 || value === '1' }
</script>

<template>
  <div class="modal-backdrop fulfillment-backdrop" @click.self="emit('close')">
    <section class="fulfillment-dialog" role="dialog" aria-modal="true" aria-labelledby="fulfillment-title">
      <header class="fulfillment-dialog-head"><div><p class="eyebrow">前台履约 · 来源门店 {{ sourceClinicName }}</p><h2 id="fulfillment-title">预约 {{ sourceRow.appointment_no }}</h2><p class="muted">患者 #{{ sourceRow.patient_id }} · {{ formatShanghai(sourceRow.scheduled_start) }} 至 {{ formatShanghai(sourceRow.scheduled_end) }}</p></div><button class="icon-button" type="button" aria-label="关闭预约履约详情" @click="emit('close')">×</button></header>
      <div class="fulfillment-dialog-body">
        <NoticeBar tone="error" :title="loadError || actionError" />
        <NoticeBar tone="success" :title="notice" @close="notice = ''" />
        <div v-if="loading" class="loading-state"><span class="spinner"></span>正在读取本次预约及履约摘要…</div>
        <template v-else-if="appointment">
          <section class="fulfillment-state-card">
            <div class="fulfillment-state-top"><div><span class="muted">服务端预约状态</span><div class="fulfillment-badges"><StatusBadge :value="String(appointment.appointment_status)" /><StatusBadge :value="String(appointment.treatment_status)" /></div></div><button class="button button-quiet" type="button" :disabled="loading" @click="refresh()">↻ 重新读取</button></div>
            <div class="fulfillment-state-grid"><div><span>办理时</span><strong>{{ formatShanghai(bookingStatus?.asOf) }}</strong></div><div><span>版本</span><strong>{{ appointment.row_version }}</strong></div><div><span>实际到诊</span><strong>{{ appointment.arrived_at ? formatShanghai(appointment.arrived_at) : '尚无实际到诊记录' }}</strong></div><div><span>预约结束</span><strong>{{ formatShanghai(appointment.scheduled_end) }}</strong></div></div>
          </section>

          <section class="fulfillment-summary">
            <div class="fulfillment-section-head"><div><h3>履约摘要</h3><p class="muted">跨店只显示计数；其他门店事件明细不可在此修改。</p></div><span class="event-count">{{ eligibleEventCount }}<small>/ 2</small></span></div>
            <p v-if="truth(bookingStatus?.restricted) && restriction" class="restriction-callout"><strong>当前有生效的自助预约限制</strong><span>至 {{ formatShanghai(restriction.ends_at) }}。限制期间仍可查询、告知取消并由前台电话/柜台人工办理。</span></p>
            <p v-else-if="eligibleEventCount > 0" class="fulfillment-rule-note">近30天内有 {{ eligibleEventCount }} 条符合触发口径的未消费事件（跨门店计数）；达到2条时服务端按规则创建14天限制。计数不等同于本门店违约明细。</p>
            <p v-else class="fulfillment-rule-note">当前没有符合触发口径的未消费事件。限制规则：近30天2条有效、未豁免、未消费事件触发14天；既有预约不会因此取消。</p>
            <div class="rule-grid"><div><strong>临近时间</strong><span>原预约开始距办理时刻不少于3小时为正常调整；不足3小时由服务端记录相应事件。临近取消仍会释放时段。</span></div><div><strong>人工核实</strong><span>未到诊只可在预约结束时间严格过去后人工核实；不能按 attended=false 自动处罚。</span></div></div>
          </section>

          <section class="event-card">
            <div class="fulfillment-section-head"><div><h3>本次预约事件</h3><p class="muted">仅显示该预约自己的事件。</p></div><StatusBadge v-if="event" :value="truth(event.exempt) ? '已豁免' : truth(event.valid) ? '有效事件' : '无效事件'" /></div>
            <div v-if="event" class="event-detail-grid"><div><span>事件类型</span><strong>{{ event.breach_kind === 'late_cancel' ? '临近取消' : event.breach_kind === 'late_reschedule' ? '临近改约' : event.breach_kind === 'no_show' ? '未到诊' : event.breach_kind }}</strong></div><div><span>事件时间</span><strong>{{ formatShanghai(event.occurred_at) }}</strong></div><div><span>责任/依据</span><strong>{{ event.responsibility_reason || '未填写' }}</strong></div><div><span>是否豁免</span><strong>{{ truth(event.exempt) ? '是' : '否' }}</strong></div><div><span>限制关联</span><strong>{{ event.restriction_id ? `限制 #${event.restriction_id}` : '未消费/无关联' }}</strong></div><div><span>最近更新</span><strong>{{ formatShanghai(event.updated_at) }}</strong></div></div>
            <div v-else class="empty-inline">当前预约没有履约事件。正常调整不会生成违约事件。</div>
            <button v-if="event && canReviewEvent && !hasPendingWrite" class="button button-secondary" type="button" :disabled="!pendingStorageReadable || !!sendingKey" @click="openAction('review')">复核本门店事件</button>
            <small v-else-if="event && !canReviewEvent" class="permission-note">此账号没有本事件来源门店的前台复核权限。</small>
          </section>

          <section v-if="pendingWrites.length" class="pending-write-card" aria-live="polite">
            <div class="fulfillment-section-head"><div><h3>待核对的原请求</h3><p class="muted">结果未知时保留原操作号与完整内容。先读取服务端状态；需要重试时只重发同一请求。</p></div><span class="badge badge-warning">{{ pendingWrites.length }} 条</span></div>
            <article v-for="record in pendingWrites" :key="record.key" class="pending-write-item">
              <div><strong>{{ actionLabel(record.action) }} · {{ record.operationId }}</strong><p>{{ pendingSummary(record) }}</p><small>创建于 {{ new Date(record.createdAt).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' }) }}</small></div>
              <div class="pending-write-controls"><button class="button button-small button-quiet" type="button" :disabled="loading || !!sendingKey" @click="reloadForPending">重新读取状态</button><button class="button button-small button-secondary" type="button" :disabled="!!sendingKey" @click="retryPending(record)">沿用原请求重试</button><button class="text-button" type="button" @click="manuallyResolve(record)">{{ manualVerifiedKey === record.key ? '确认已核对并清除本机提示' : '已在服务端核对…' }}</button></div>
            </article>
            <p class="pending-write-note">清除本机提示不会修改服务端记录。若服务端状态仍不明确，请保留提示并联系管理员核对。</p>
          </section>

          <section class="fulfillment-actions">
            <div class="fulfillment-section-head"><div><h3>可办理事项</h3><p class="muted">页面仅提交服务端已实现操作；服务端仍是最终授权与状态判断方。</p></div></div>
            <p v-if="protectedRecord" class="protected-callout"><strong>保留真实就诊记录</strong><span>已到诊、治疗状态、完成记录或结算身份会阻止普通取消/改约。此处不提供删除/误录清除；请保留历史并转负责人专门复核。</span></p>
            <p v-if="!pendingStorageReadable" class="pending-write-note" role="alert">本机待核对请求不可读取，所有写入已停用；请恢复会话存储后再核对。</p>
            <div class="fulfillment-action-buttons"><button class="button button-secondary" type="button" :disabled="!canChange || hasPendingWrite || !pendingStorageReadable || !!sendingKey" @click="openAction('reschedule')">办理改约</button><button class="button button-secondary" type="button" :disabled="!canChange || hasPendingWrite || !pendingStorageReadable || !!sendingKey" @click="openAction('cancel')">办理取消/撤销</button><button class="button button-danger" type="button" :disabled="!canNoShow || hasPendingWrite || !pendingStorageReadable || !!sendingKey" @click="openAction('no-show')">人工核实未到诊</button></div>
            <p v-if="canChange && !protectedRecord" class="muted">取消/改约均要求原因与责任归属；特殊豁免单独记录。改约需同时获原门店和目标门店授权。</p>
            <p v-if="!canNoShow && canChange" class="muted">只有预约结束时间严格过去，且不存在到诊、治疗或结算事实时，未到诊核实按钮才会开放。</p>
            <button v-if="['已到诊','未到诊'].includes(String(appointment.appointment_status)) && appointment.treatment_status==='未开始' && !invoice && !appointment.checked_at && !identity.readOnly" data-readonly-write class="secondary" type="button" :disabled="hasPendingWrite || !!sendingKey || correctionBusy" @click="correctionOpen=true">更正错登结果</button>
            <form v-if="correctionOpen" class="form-grid" @submit.prevent="correctResult">
              <label>错登原因<input v-model="correctionReason" required maxlength="500" :disabled="correctionBusy"></label>
              <div class="form-actions"><button class="secondary" type="button" :disabled="correctionBusy" @click="correctionOpen=false">返回</button><button data-readonly-write type="submit" :disabled="correctionBusy">撤回并重新核实</button></div>
            </form>
          </section>

          <section v-if="action === 'reschedule' && canChange && !hasPendingWrite" class="action-panel"><RescheduleForm :appointment="appointment" :clinics="clinics" :identity="identity" :appointment-no="rescheduleNo" :disabled="!pendingStorageReadable || !!sendingKey" @submit="submitReschedule" @cancel="action = null" /></section>
          <section v-if="(action === 'cancel' || action === 'no-show') && !hasPendingWrite" class="action-panel">
            <div class="fulfillment-form-heading"><div><p class="eyebrow">{{ action === 'no-show' ? '只记录人工核实结果' : '保留原预约及办理依据' }}</p><h3>{{ action === 'no-show' ? '核实未到诊' : '取消或诊所撤销' }}</h3></div><button class="icon-button" type="button" aria-label="关闭表单" @click="action = null">×</button></div>
            <p v-if="action === 'no-show'" class="no-show-rule">核实必须发生在 {{ formatShanghai(appointment.scheduled_end) }} 之后。attended=false 本身不代表未到诊，不会自动产生事件。</p>
            <div v-if="!confirmAction" class="form-grid form-grid-2"><label>责任归属<select v-model="responsibility"><option value="">明确选择</option><option value="患者">患者</option><option value="诊所">诊所</option><option value="误录">误录</option></select></label><label>处理说明<input v-model="reason" maxlength="8000" required placeholder="说明患者责任、诊所原因或误录依据" /></label><label class="check-label span-2"><input v-model="exempt" type="checkbox" />申请特殊情况豁免（必须填写具体依据）</label><div class="form-actions span-2"><button class="button button-quiet" type="button" @click="action = null">返回</button><button class="button button-secondary" type="button" @click="reviewDecision">核对责任与结果</button></div></div>
            <div v-else class="confirmation-summary" role="alert"><p class="eyebrow">提交前确认</p><h4>{{ action === 'no-show' ? '确认人工登记未到诊' : responsibility === '诊所' ? '确认诊所撤销' : responsibility === '误录' ? '确认误录作废' : '确认患者取消' }}</h4><dl><div><dt>原预约</dt><dd>{{ sourceRow.appointment_no }} · {{ sourceClinicName }}</dd></div><div><dt>责任</dt><dd>{{ responsibility }}</dd></div><div><dt>豁免</dt><dd>{{ exempt ? '申请豁免' : '不申请豁免' }}</dd></div><div><dt>依据</dt><dd>{{ reason }}</dd></div><div v-if="action === 'no-show'"><dt>事件时间</dt><dd>原预约结束 {{ formatShanghai(appointment.scheduled_end) }}；由服务端保存核实时间。</dd></div></dl><p class="muted">{{ action === 'no-show' ? '只有当前时间严格晚于预约结束后才能提交；不会因缺少到诊标记自动处罚。' : '取消/撤销会释放原时段并保留预约与审计历史。临近调整仍按服务端3小时规则记录。' }}</p><div class="action-confirm-buttons"><button class="button button-quiet" type="button" @click="confirmAction = false">返回修改</button><button class="button" :class="action === 'no-show' ? 'button-danger' : 'button-primary'" type="button" :disabled="!pendingStorageReadable || !!sendingKey" @click="submitDecision">{{ action === 'no-show' ? '确认未到诊核实' : '确认取消并提交' }}</button></div></div>
          </section>
          <section v-if="action === 'review' && event && !hasPendingWrite" class="action-panel"><div class="fulfillment-form-heading"><div><p class="eyebrow">只能复核来源门店的本次事件</p><h3>事件责任与豁免复核</h3></div><button class="icon-button" type="button" aria-label="关闭复核表单" @click="action = null">×</button></div><div v-if="!confirmAction" class="form-grid form-grid-2"><label>事件是否有效<select v-model="reviewValid"><option :value="true">有效</option><option :value="false">无效</option></select></label><label>是否豁免<select v-model="reviewExempt"><option :value="false">不豁免</option><option :value="true">豁免</option></select></label><label class="span-2">复核原因<input v-model="reviewReason" maxlength="8000" required /></label><p class="muted span-2">关联限制若因此不足2条有效未豁免事件，服务端会撤销限制并保留原事件/消费关联；自然滑出30天不会提前撤销有效期内限制。</p><div class="form-actions span-2"><button class="button button-quiet" type="button" @click="action = null">返回</button><button class="button button-secondary" type="button" @click="reviewEvent">核对复核内容</button></div></div><div v-else class="confirmation-summary" role="alert"><p class="eyebrow">来源门店事件 #{{ event.breach_id }}</p><h4>确认复核历史事件</h4><dl><div><dt>有效性</dt><dd>{{ reviewValid ? '有效' : '无效' }}</dd></div><div><dt>豁免</dt><dd>{{ reviewExempt ? '豁免' : '不豁免' }}</dd></div><div><dt>依据</dt><dd>{{ reviewReason }}</dd></div></dl><p class="muted">此操作只更新当前预约所属门店的事件；系统按真实跨店汇总重算限制，不修改事件时间或其他门店事件。</p><div class="action-confirm-buttons"><button class="button button-quiet" type="button" @click="confirmAction = false">返回修改</button><button class="button button-primary" type="button" :disabled="!pendingStorageReadable || !!sendingKey" @click="submitEventReview">确认复核并提交</button></div></div></section>
        </template>
        <ReminderObjectActions v-if="appointment" business-type="appointment" :business-id="appointmentId" :clinic-id="sourceClinicId" :identity="identity" />
      </div>
      <footer class="fulfillment-dialog-footer"><small>源预约、事件与限制的最终权限、状态及版本由服务端校验。</small><button class="button button-quiet" type="button" @click="emit('close')">关闭</button></footer>
    </section>
  </div>
</template>
