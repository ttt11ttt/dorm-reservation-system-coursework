<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { api, formatShanghai, operationId, sessionToken, shanghaiOffset } from '../api'
import type { Clinic, Identity } from '../api'
import InvoiceActions from '../components/InvoiceActions.vue'
import NoticeBar from '../components/NoticeBar.vue'
import StatusBadge from '../components/StatusBadge.vue'
import FulfillmentPanel from '../components/FulfillmentPanel.vue'
import FrontDeskFollowups from '../components/FrontDeskFollowups.vue'
import { hasFrontDeskGrant } from '../fulfillment-core.js'
import { makeGuestReservationConversionPayload, preserveGuestReservationConversion, preserveStaffGuestReservations } from '../guest-reservation-core.js'
import type { GuestReservationConversionPayload, StaffGuestReservation } from '../guest-reservation-core.js'

type Row = Record<string, unknown>
type Window = { start: number; end: number; dentistId: string; scheduleId: string }
type Occupied = { start: number; end: number; dentistId: string }
type GuestConversionAttempt = { reservation: StaffGuestReservation; payload: GuestReservationConversionPayload; phase: 'sending' | 'unknown' | 'checking' | 'retryable' | 'blocked' }
const props = defineProps<{ clinics: Clinic[]; identity: Identity }>()
const authorizedClinics = computed(() => props.clinics.filter((clinic) => hasFrontDeskGrant(props.identity, clinic.clinic_id)))
const clinicId = ref(authorizedClinics.value[0]?.clinic_id ?? '')
const workMode = ref<'appointments' | 'followups'>('appointments')
const fulfillmentRow = ref<Row | null>(null)
const appointments = ref<Row[]>([])
const busyList = ref(false)
const error = ref('')
const notice = ref('')
const query = ref('')
const patients = ref<Row[]>([])
const selectedPatient = ref<Row | null>(null)
const patientBusy = ref(false)
const showNewPatient = ref(false)
const showBooking = ref(false)
const appointmentNo = ref(operationId('APT'))
const dentists = ref<Row[]>([])
const windows = ref<Window[]>([])
const occupied = ref<Occupied[]>([])
const guestReservations = ref<StaffGuestReservation[]>([])
const guestListBusy = ref(false)
const guestListError = ref('')
const guestCancelTarget = ref<StaffGuestReservation>()
const guestCancelReason = ref('')
const guestCancelBusy = ref(false)
const conversionTarget = ref<StaffGuestReservation | null>(null)
const conversionPatientId = ref('')
const conversionEvidenceRef = ref('')
const conversionConfirmedInPerson = ref(false)
const conversionPending = ref<GuestConversionAttempt | null>(null)
const conversionBusy = ref(false)
const conversionError = ref('')
const conversionNotice = ref('')
const conversionReceipt = ref<{ reservationNo: string; appointmentId: string; replayed: boolean } | null>(null)
const availabilityAsOf = ref(0)
const busySlots = ref(false)
const startInput = ref('')
const durationChoice = ref('30')
const customDuration = ref('45')
const invoice = ref<Row | null>(null)
const patientForm = ref({ patientNo: '', name: '', birthDate: '1990-01-01', billingAddress: '', phone: '', email: '', emergencyName: '', emergencyPhone: '' })
const bookingForm = ref({ dentistId: '', requestedTreatments: '', specialNeeds: '', bookingChannel: '柜台' })
let pageRevision = 0
let disposed = false

const clinicName = computed(() => props.clinics.find((clinic) => clinic.clinic_id === clinicId.value)?.name ?? '选择门店')
const canUseFollowups = computed(() => authorizedClinics.value.length > 0)
const durationMinutes = computed(() => durationChoice.value === 'custom' ? Number.parseInt(customDuration.value, 10) : Number.parseInt(durationChoice.value, 10))
const interval = computed(() => {
  if (!startInput.value || !Number.isInteger(durationMinutes.value) || durationMinutes.value < 1) return null
  const start = Date.parse(`${startInput.value}:00+08:00`)
  if (!Number.isFinite(start)) return null
  const end = start + durationMinutes.value * 60_000
  const window = windows.value.find((item) => item.dentistId === bookingForm.value.dentistId && start >= item.start && end <= item.end)
  const overlap = occupied.value.some((item) => item.dentistId === bookingForm.value.dentistId && item.start < end && item.end > start)
  const afterNow = start >= availabilityAsOf.value
  return { start, end, window, overlap, afterNow, valid: !!window && !overlap && afterNow }
})
const intervalEndLabel = computed(() => interval.value ? formatInput(new Date(interval.value.end)).replace('T', ' ') : '')

onMounted(() => { void loadAppointments(); void loadGuestReservations() })
onUnmounted(() => { disposed = true; pageRevision += 1 })
watch(authorizedClinics, (items) => { if (!items.some((item) => item.clinic_id === clinicId.value)) clinicId.value = items[0]?.clinic_id ?? '' })
watch([clinicId, () => props.identity], async () => {
  pageRevision += 1
  appointments.value = []; fulfillmentRow.value = null; query.value = ''; patients.value = []; selectedPatient.value = null
  guestReservations.value = []; guestListBusy.value = false; guestListError.value = ''; conversionTarget.value = null; conversionPatientId.value = ''; conversionEvidenceRef.value = ''
  guestCancelTarget.value = undefined; guestCancelReason.value = ''; guestCancelBusy.value = false
  conversionConfirmedInPerson.value = false; conversionPending.value = null; conversionBusy.value = false; conversionError.value = ''; conversionNotice.value = ''; conversionReceipt.value = null
  showNewPatient.value = false; showBooking.value = false; invoice.value = null; notice.value = ''; error.value = ''
  patientForm.value = { patientNo: '', name: '', birthDate: '1990-01-01', billingAddress: '', phone: '', email: '', emergencyName: '', emergencyPhone: '' }
  bookingForm.value = { dentistId: '', requestedTreatments: '', specialNeeds: '', bookingChannel: '柜台' }; appointmentNo.value = operationId('APT')
  await Promise.all([loadAppointments(), loadGuestReservations()])
})

function hasClinicAccess(clinic = clinicId.value) { return !!clinic && authorizedClinics.value.some((item) => item.clinic_id === clinic) }
function captureScope() { return { revision: pageRevision, clinic: clinicId.value, account: props.identity.accountId, identity: props.identity, token: sessionToken() } }
function isCurrent(scope: ReturnType<typeof captureScope>) { return !disposed && scope.revision === pageRevision && scope.clinic === clinicId.value && hasClinicAccess(scope.clinic) && scope.account === props.identity.accountId && scope.identity === props.identity && scope.token === sessionToken() }

async function loadAppointments() {
  if (!hasClinicAccess()) return
  const scope = captureScope()
  busyList.value = true; error.value = ''
  try { const rows = await api<Row[]>(`/staff/appointments?clinicId=${encodeURIComponent(scope.clinic)}`); if (isCurrent(scope)) appointments.value = rows }
  catch (e) { if (isCurrent(scope)) error.value = message(e) }
  finally { if (isCurrent(scope)) busyList.value = false }
}
async function loadGuestReservations() {
  if (!hasClinicAccess()) { guestReservations.value = []; guestListBusy.value = false; return null }
  const scope = captureScope()
  guestListBusy.value = true; guestListError.value = ''
  try {
    const raw = await api<unknown>(`/staff/guest-reservations?clinicId=${encodeURIComponent(scope.clinic)}`)
    if (!isCurrent(scope)) return null
    const rows = preserveStaffGuestReservations(raw, scope.clinic)
    guestReservations.value = rows
    return rows
  } catch (e) {
    if (isCurrent(scope)) guestListError.value = message(e)
    return null
  } finally { if (isCurrent(scope)) guestListBusy.value = false }
}
function message(e: unknown) { return e instanceof Error ? e.message : '请求未完成。' }
async function cancelGuestForClinic() {
  const target = guestCancelTarget.value
  if (!target || guestCancelBusy.value || !guestCancelReason.value.trim() || props.identity.readOnly) return
  const scope = captureScope()
  guestCancelBusy.value = true
  guestListError.value = ''
  try {
    await api(`/staff/guest-reservations/${target.reservationId}/clinic-cancel`, {
      method: 'POST', body: { expectedRowVersion: target.rowVersion, reason: guestCancelReason.value.trim() },
    })
    if (isCurrent(scope)) {
      guestCancelTarget.value = undefined
      guestCancelReason.value = ''
      await loadGuestReservations()
    }
  } catch (e) {
    if (isCurrent(scope)) guestListError.value = message(e)
  } finally {
    if (isCurrent(scope)) guestCancelBusy.value = false
  }
}
async function searchPatients() {
  if (!hasClinicAccess()) return
  if (!query.value.trim()) { error.value = '请输入患者编号、姓名或电话进行精确检索。'; return }
  const scope = captureScope()
  patientBusy.value = true; error.value = ''; patients.value = []; selectedPatient.value = null
  try { const rows = await api<Row[]>(`/staff/patients?clinicId=${encodeURIComponent(scope.clinic)}&q=${encodeURIComponent(query.value.trim())}`); if (isCurrent(scope)) patients.value = rows }
  catch (e) { if (isCurrent(scope)) error.value = message(e) }
  finally { if (isCurrent(scope)) patientBusy.value = false }
}
function choosePatient(patient: Row) {
  if (conversionTarget.value) {
    conversionPatientId.value = String(patient.patient_id)
    patients.value = []; query.value = ''; showNewPatient.value = false
    conversionError.value = ''; conversionNotice.value = `已选择现有患者 #${conversionPatientId.value}。访客联系人不会自动匹配或并入患者档案。`
    return
  }
  selectedPatient.value = patient; showBooking.value = true; prepareBooking()
}
async function createPatient() {
  if (!hasClinicAccess()) return
  const scope = captureScope()
  patientBusy.value = true; error.value = ''
  try {
    const patient = await api<Row>('/staff/patients', { method: 'POST', body: { clinicId: scope.clinic, ...patientForm.value, email: patientForm.value.email.trim() || null } })
    if (!isCurrent(scope)) return
    if (conversionTarget.value) {
      conversionPatientId.value = String(patient.patient_id); patients.value = []; showNewPatient.value = false; showBooking.value = false
      patientForm.value = { patientNo: '', name: '', birthDate: '1990-01-01', billingAddress: '', phone: '', email: '', emergencyName: '', emergencyPhone: '' }
      notice.value = `已按独立前台建档流程建立患者 #${conversionPatientId.value}。访客预约仍待到店身份核验转换。`
      return
    }
    patients.value = [patient]; selectedPatient.value = patient; showNewPatient.value = false; showBooking.value = true; notice.value = '档案已建立，过敏状态为“未询问”；请完成本次预约信息。'; await prepareBooking()
  } catch (e) { if (isCurrent(scope)) error.value = message(e) }
  finally { if (isCurrent(scope)) patientBusy.value = false }
}
function formatInput(date: Date) {
  const shifted = new Date(date.getTime() + 8 * 60 * 60 * 1000)
  return shifted.toISOString().slice(0, 16)
}
async function prepareBooking() {
  if (!hasClinicAccess()) return
  const scope = captureScope()
  busySlots.value = true; windows.value = []; occupied.value = []; startInput.value = ''; error.value = ''
  try {
    const [people, availability] = await Promise.all([
      api<Row[]>(`/dentists?clinicId=${encodeURIComponent(scope.clinic)}`),
      api<{ asOf: string; windows: Row[]; occupied: Row[] }>(`/availability?clinicId=${encodeURIComponent(scope.clinic)}`),
    ])
    if (!isCurrent(scope)) return
    dentists.value = people
    if (!bookingForm.value.dentistId || !people.some((person) => String(person.dentist_id) === bookingForm.value.dentistId)) bookingForm.value.dentistId = String(people[0]?.dentist_id ?? '')
    availabilityAsOf.value = new Date(availability.asOf).getTime()
    occupied.value = availability.occupied.map((item) => ({ start: new Date(String(item.scheduled_start)).getTime(), end: new Date(String(item.scheduled_end)).getTime(), dentistId: String(item.dentist_id) }))
    const result: Window[] = []
    for (const window of availability.windows) {
      if (String(window.dentist_id) !== bookingForm.value.dentistId) continue
      const windowStart = new Date(String(window.start_at)).getTime()
      const windowEnd = new Date(String(window.end_at)).getTime()
      result.push({ start: windowStart, end: windowEnd, dentistId: bookingForm.value.dentistId, scheduleId: String(window.schedule_id) })
    }
    windows.value = result
    const firstWindow = result.find((item) => item.end > availabilityAsOf.value)
    if (firstWindow) startInput.value = formatInput(new Date(Math.max(firstWindow.start, availabilityAsOf.value + 60_000)))
  } catch (e) { if (isCurrent(scope)) error.value = message(e) }
  finally { if (isCurrent(scope)) busySlots.value = false }
}
watch(() => bookingForm.value.dentistId, () => { if (showBooking.value) void prepareBooking() })
async function submitBooking() {
  if (!hasClinicAccess()) return
  if (!selectedPatient.value || !interval.value?.valid) { error.value = '请选择完全落在真实开放与出诊窗口内、且不与已占用时段重叠的开始时间和时长。'; return }
  if (!bookingForm.value.requestedTreatments.trim()) { error.value = '请填写患者本次描述的预约需求。'; return }
  const scope = captureScope()
  error.value = ''
  try {
    const result = await api<{ replayed: boolean; appointment: Row }>('/staff/appointments', { method: 'POST', body: {
      appointmentNo: appointmentNo.value, patientId: String(selectedPatient.value.patient_id), clinicId: clinicId.value,
      dentistId: bookingForm.value.dentistId, scheduleId: interval.value.window!.scheduleId,
      scheduledStart: shanghaiOffset(startInput.value), scheduledEnd: shanghaiOffset(formatInput(new Date(interval.value.end))),
      bookingChannel: bookingForm.value.bookingChannel, requestedTreatments: bookingForm.value.requestedTreatments.trim(), specialNeeds: bookingForm.value.specialNeeds.trim() || null,
    } })
    if (!isCurrent(scope)) return
    showBooking.value = false; selectedPatient.value = null; notice.value = result.replayed ? '已按原操作编号取回既有预约。' : '预约已提交并由服务端确认。'; appointmentNo.value = operationId('APT'); await loadAppointments()
  } catch (e) { if (isCurrent(scope)) error.value = message(e) }
}
async function arrive(row: Row) {
  if (!hasClinicAccess()) return
  const scope = captureScope()
  error.value = ''; notice.value = ''
  try { await api(`/staff/appointments/${String(row.appointment_id)}/arrival`, { method: 'POST' }); if (!isCurrent(scope)) return; notice.value = '已记录实际到诊时间；预约时段保持不变，诊疗仍由牙医单独确认。'; await loadAppointments() }
  catch (e) { if (isCurrent(scope)) error.value = message(e) }
}
async function issue(row: Row) {
  if (!hasClinicAccess()) return
  const scope = captureScope()
  error.value = ''; invoice.value = null
  try {
    const result = await api<{ replayed: boolean; invoice: Row }>(`/staff/appointments/${String(row.appointment_id)}/invoice-draft`, { method: 'POST' })
    if (!isCurrent(scope)) return
    invoice.value = result.invoice; notice.value = result.replayed ? '已返回同次既有结算单。' : '结算草稿已生成，请核对后发出。'
  } catch (e) { if (isCurrent(scope)) error.value = message(e) }
}
async function viewOrIssue(row: Row) {
  if (!hasClinicAccess()) return
  const scope = captureScope()
  error.value = ''; notice.value = ''; invoice.value = null
  try {
    const details = await api<{ appointment: Row; invoice: Row | null }>(`/staff/appointments/${String(row.appointment_id)}`)
    if (!isCurrent(scope)) return
    if (details.invoice?.invoice_id) {
      const invoiceDetails = await api<Row>(`/staff/invoices/${String(details.invoice.invoice_id)}`)
      if (!isCurrent(scope)) return
      invoice.value = invoiceDetails
      notice.value = '已读取本次唯一结算明细。'
    } else await issue(row)
  } catch (e) { if (isCurrent(scope)) error.value = message(e) }
}
function startGuestConversion(row: StaffGuestReservation) {
  if (!hasClinicAccess() || conversionPending.value || row.status !== '待到诊' || row.clinicId !== clinicId.value) return
  conversionTarget.value = row
  conversionPatientId.value = ''; conversionEvidenceRef.value = ''; conversionConfirmedInPerson.value = false
  conversionError.value = ''; conversionNotice.value = ''; conversionReceipt.value = null
  query.value = ''; patients.value = []; selectedPatient.value = null; showNewPatient.value = false; showBooking.value = false
  patientForm.value = { patientNo: '', name: '', birthDate: '1990-01-01', billingAddress: '', phone: '', email: '', emergencyName: '', emergencyPhone: '' }
}
function finishGuestConversion(reservationNo: string, appointmentId: string, replayed: boolean) {
  conversionReceipt.value = { reservationNo, appointmentId, replayed }
  conversionPending.value = null; conversionTarget.value = null; conversionPatientId.value = ''; conversionEvidenceRef.value = ''; conversionConfirmedInPerson.value = false
  conversionError.value = ''; conversionNotice.value = replayed ? '已核对到原访客预约已转换；保留原预约号。' : '到店核验转换已确认；普通预约沿用原访客预约号。'
  void loadAppointments()
}
async function submitGuestConversion(attempt: GuestConversionAttempt | null = null, exactRetry = false) {
  if (!hasClinicAccess() || conversionBusy.value) return
  const prior = attempt ?? conversionPending.value
  const reservation = prior?.reservation ?? conversionTarget.value
  if (!reservation || reservation.clinicId !== clinicId.value) return
  if (exactRetry && prior?.phase !== 'retryable') return
  let payload: GuestReservationConversionPayload
  if (!exactRetry) {
    try {
      payload = makeGuestReservationConversionPayload(reservation, conversionPatientId.value, conversionEvidenceRef.value.trim(), conversionConfirmedInPerson.value)
    } catch (cause) { conversionError.value = message(cause); return }
  } else payload = prior!.payload
  const target: GuestConversionAttempt = { reservation, payload, phase: 'sending' }
  const scope = captureScope()
  conversionPending.value = target
  conversionBusy.value = true; conversionError.value = ''; conversionNotice.value = ''
  try {
    const raw = await api<unknown>(`/staff/guest-reservations/${encodeURIComponent(target.reservation.reservationId)}/convert`, { method: 'POST', body: target.payload })
    if (!isCurrent(scope)) return
    const confirmed = preserveGuestReservationConversion(raw, {
      clinicId: target.reservation.clinicId, reservationId: target.reservation.reservationId, reservationNo: target.reservation.reservationNo,
    })
    guestReservations.value = guestReservations.value.map((item) => item.reservationId === confirmed.reservation.reservationId ? confirmed.reservation : item)
    finishGuestConversion(confirmed.reservation.reservationNo, confirmed.appointmentId, confirmed.replayed)
  } catch (cause) {
    if (!isCurrent(scope)) return
    const code = String((cause as { code?: string } | undefined)?.code ?? '')
    const status = Number((cause as { status?: number } | undefined)?.status ?? 0)
    if (status >= 400 && status < 500 && status !== 409) {
      conversionPending.value = null; conversionError.value = message(cause)
      void loadGuestReservations()
    } else {
      const phase = status === 409 || code === 'ROW_VERSION_CONFLICT' ? 'blocked' : 'unknown'
      conversionPending.value = { ...target, phase }
      conversionError.value = phase === 'unknown'
        ? '转换结果未知。正在只读刷新访客列表，按原预约号核对converted_appointment_id；不会改用其他患者重试。'
        : '服务端报告状态冲突。正在只读刷新原访客预约；请按新状态人工核对，不会自动重试。'
      await reconcileGuestConversion(phase === 'unknown')
      if (code === 'ROW_VERSION_CONFLICT' && conversionPending.value?.reservation.reservationId === target.reservation.reservationId && !guestListError.value) {
        const refreshed = guestReservations.value.find((item) => item.reservationId === target.reservation.reservationId && item.reservationNo === target.reservation.reservationNo)
        if (refreshed && (refreshed.status !== '待到诊' || refreshed.rowVersion !== target.payload.expectedRowVersion)) {
          conversionPending.value = null; conversionTarget.value = null; conversionPatientId.value = ''; conversionEvidenceRef.value = ''; conversionConfirmedInPerson.value = false
          guestListError.value = `原转换因版本冲突未提交。已刷新 ${refreshed.reservationNo}，当前为“${refreshed.status}”/版本${refreshed.rowVersion}；请核对最新行后再决定是否重新发起。`
        }
      }
    }
  } finally { if (isCurrent(scope)) conversionBusy.value = false }
}
async function reconcileGuestConversion(allowExactRetry: boolean) {
  const attempt = conversionPending.value
  if (!attempt || conversionBusy.value && attempt.phase === 'sending') return
  conversionPending.value = { ...attempt, phase: 'checking' }
  const rows = await loadGuestReservations()
  if (conversionPending.value?.reservation.reservationId !== attempt.reservation.reservationId) return
  if (!rows) {
    conversionPending.value = { ...attempt, phase: allowExactRetry ? 'unknown' : 'blocked' }
    conversionError.value = '只读核对未完成。保持原预约号和原patientId/依据请求；请重新读取列表后再处理。'
    return
  }
  const current = rows.find((item) => item.reservationId === attempt.reservation.reservationId && item.reservationNo === attempt.reservation.reservationNo)
  if (current?.status === '已转换' && current.convertedAppointmentId) {
    finishGuestConversion(current.reservationNo, current.convertedAppointmentId, true)
    return
  }
  if (!current) {
    conversionPending.value = { ...attempt, phase: 'blocked' }
    conversionError.value = '列表中没有找到原访客预约号。保留原请求并停止转换；请人工核实门店与原号。'
    return
  }
  if (allowExactRetry && current.status === '待到诊' && current.rowVersion === attempt.payload.expectedRowVersion) {
    conversionPending.value = { ...attempt, reservation: current, phase: 'retryable' }
    conversionError.value = '原预约仍待到诊且版本未变。可以沿用完全相同的patientId、依据和版本重试，或继续人工核对。'
    return
  }
  conversionPending.value = { ...attempt, reservation: current, phase: 'blocked' }
  conversionError.value = `已读到原预约当前状态“${current.status}”、版本${current.rowVersion}。不会改变患者或请求；需人工核实后续处理。`
}
async function refreshGuestReservations() {
  if (conversionPending.value) await reconcileGuestConversion(conversionPending.value.phase === 'unknown' || conversionPending.value.phase === 'retryable')
  else await loadGuestReservations()
}
function closeGuestConversion() {
  if (conversionPending.value) return
  conversionTarget.value = null; conversionPatientId.value = ''; conversionEvidenceRef.value = ''; conversionConfirmedInPerson.value = false
  conversionError.value = ''; conversionNotice.value = ''; query.value = ''; patients.value = []; showNewPatient.value = false
  patientForm.value = { patientNo: '', name: '', birthDate: '1990-01-01', billingAddress: '', phone: '', email: '', emergencyName: '', emergencyPhone: '' }
}
function closePatientForms() { showNewPatient.value = false; showBooking.value = false; selectedPatient.value = null }
</script>

<template>
  <section v-if="!authorizedClinics.length" class="panel empty-state"><div class="empty-icon">⌂</div><strong>当前没有可访问门店</strong><span>预约接诊入口仍保留；请由账号管理员或连锁运营人员配置并确认前台门店范围。确认后重新加载身份，此处不会发起门店业务请求。</span></section>
  <div v-else class="page-stack">
    <div class="page-heading">
      <div><p class="eyebrow">前台 · {{ clinicName }}</p><h1>接诊工作台</h1><p class="muted">检索患者、核对真实可约时段，登记到诊并办理同次结算。</p></div>
      <label class="compact-label">当前门店<select v-model="clinicId" aria-label="选择门店"><option v-for="clinic in authorizedClinics" :key="clinic.clinic_id" :value="clinic.clinic_id">{{ clinic.name }}</option></select></label>
    </div>
    <nav v-if="canUseFollowups" class="followup-work-tabs" aria-label="前台工作台分区"><button type="button" :class="{ active: workMode === 'appointments' }" @click="workMode = 'appointments'">预约接诊</button><button type="button" :class="{ active: workMode === 'followups' }" @click="workMode = 'followups'">复诊跟进</button></nav>
    <template v-if="workMode === 'appointments'">
    <NoticeBar tone="error" :title="error" @close="error = ''" /><NoticeBar tone="success" :title="notice" @close="notice = ''" />
    <section class="panel patient-search">
      <div class="section-heading"><div><h2>患者档案</h2><p class="muted">按编号、姓名或电话精确检索。共用手机号不会合并档案。</p><p v-if="conversionTarget" class="followup-alert followup-alert-info">正在为访客预约 {{ conversionTarget.reservationNo }} 独立核对既有患者。可在此搜索并选择，或按完整常规流程独立建档；不会从访客联系人自动匹配或预填。</p></div><button class="button button-secondary" type="button" @click="showNewPatient = !showNewPatient">{{ showNewPatient ? '收起建档' : '＋ 新建档案' }}</button></div>
      <form class="search-row" @submit.prevent="searchPatients"><label class="grow">快速检索<input v-model="query" type="search" placeholder="患者编号 / 姓名 / 电话" /></label><button class="button button-primary" :disabled="patientBusy">{{ patientBusy ? '查询中…' : '检索档案' }}</button></form>
      <div v-if="patients.length" class="patient-results">
        <button v-for="patient in patients" :key="String(patient.patient_id)" class="patient-result" type="button" @click="choosePatient(patient)">
          <span class="avatar">{{ String(patient.name).slice(0, 1) }}</span><span class="patient-result-main"><strong>{{ patient.name }}</strong><small>{{ patient.patient_no }} · {{ patient.phone }}</small></span><span class="badge badge-neutral">{{ patient.allergy_declaration }}</span><span class="select-arrow">→</span>
        </button>
      </div>
      <div v-else-if="query && !patientBusy" class="empty-inline">暂无精确匹配的档案。可以按“新建档案”建立新患者。</div>
      <form v-if="showNewPatient" class="form-grid form-grid-3 form-divider" @submit.prevent="createPatient">
        <label>患者编号<input v-model="patientForm.patientNo" required maxlength="32" autocomplete="off" /></label><label>姓名<input v-model="patientForm.name" required maxlength="80" autocomplete="name" /></label><label>出生日期<input v-model="patientForm.birthDate" type="date" required /></label>
        <label>联系电话<input v-model="patientForm.phone" required maxlength="32" autocomplete="tel" /></label><label>电子邮箱（选填）<input v-model="patientForm.email" type="email" maxlength="254" /></label><label>通讯地址<input v-model="patientForm.billingAddress" required maxlength="255" /></label>
        <label>紧急联系人<input v-model="patientForm.emergencyName" required maxlength="80" /></label><label>紧急联系电话<input v-model="patientForm.emergencyPhone" required maxlength="32" /></label>
        <div class="form-actions"><button class="button button-primary" :disabled="patientBusy">{{ patientBusy ? '保存中…' : conversionTarget ? '独立建档并选择该患者' : '保存档案并继续预约' }}</button></div>
      </form>
      <div v-if="showBooking" class="booking-box">
        <div class="booking-title"><div><span class="eyebrow">新预约 · {{ selectedPatient?.name }}</span><h3>选择真实出诊时段</h3></div><button class="icon-button" aria-label="关闭预约表单" @click="closePatientForms">×</button></div>
        <div class="form-grid form-grid-3">
          <label>接诊牙医<select v-model="bookingForm.dentistId"><option v-for="dentist in dentists" :key="String(dentist.dentist_id)" :value="String(dentist.dentist_id)">{{ dentist.name }}</option></select></label>
          <label>开始时间 · Asia/Shanghai<input v-model="startInput" type="datetime-local" :disabled="busySlots || !windows.length" /></label>
          <label>预约时长<select v-model="durationChoice"><option value="30">30 分钟（快捷）</option><option value="45">45 分钟</option><option value="60">60 分钟</option><option value="90">90 分钟</option><option value="custom">自定义时长</option></select></label>
          <label v-if="durationChoice === 'custom'">自定义分钟数<input v-model="customDuration" type="number" min="1" step="1" inputmode="numeric" /></label>
          <label>预约来源<select v-model="bookingForm.bookingChannel"><option>柜台</option><option>电话</option></select></label>
          <div class="interval-preview" :class="interval?.valid ? 'interval-valid' : 'interval-invalid'"><span class="interval-mark">{{ interval?.valid ? '✓' : '!' }}</span><span><strong>{{ interval?.valid ? '时段可提交' : '请调整时间或时长' }}</strong><small v-if="interval">{{ startInput.replace('T', ' ') }} — {{ intervalEndLabel }} · {{ durationMinutes }} 分钟</small><small v-else>{{ busySlots ? '正在读取服务端开放与出诊窗口…' : windows.length ? '请填写有效开始时间及分钟数' : '当前门店暂无可用出诊窗口' }}</small><small v-if="interval && !interval.afterNow">开始时间早于服务端当前时间</small><small v-if="interval?.overlap">与服务端已占用时段重叠</small><small v-if="interval && !interval.window">超出真实开放或牙医出诊范围</small></span><button type="button" class="text-button" @click="prepareBooking">重新读取时段</button></div>
          <label class="span-2">本次需求<textarea v-model="bookingForm.requestedTreatments" rows="2" required maxlength="8000" placeholder="记录患者描述的需求；不作为实际治疗或计费依据" /></label>
          <label>特殊需求（选填）<input v-model="bookingForm.specialNeeds" maxlength="8000" /></label>
        </div>
        <div class="booking-footer"><small>操作编号 {{ appointmentNo }} · 超时重试沿用此编号 · 最终时段仍由服务端复核</small><button class="button button-primary" :disabled="busySlots || !interval?.valid" @click="submitBooking">提交预约</button></div>
      </div>
    </section>

    <section v-if="conversionTarget" class="panel guest-conversion-panel">
      <div class="section-heading"><div><p class="eyebrow">到店人工核实 · 保留原号</p><h2>转换访客预约 {{ conversionTarget.reservationNo }}</h2><p class="muted">访客记录尚未核验医疗身份。请先现场核实本人，再关联已存在的患者；此步骤不会创建微信绑定，也不会按电话合并。</p></div><button v-if="!conversionPending" class="button button-quiet" @click="closeGuestConversion">关闭</button></div>
      <dl class="guest-conversion-summary"><div><dt>预约时段</dt><dd>{{ formatShanghai(conversionTarget.startAt) }} 至 {{ formatShanghai(conversionTarget.endAt) }}</dd></div><div><dt>牙医</dt><dd>#{{ conversionTarget.dentistId }}</dd></div><div><dt>访客联系人</dt><dd>{{ conversionTarget.contactName }} · {{ conversionTarget.contactPhone }}</dd></div><div><dt>预约状态 / 版本</dt><dd>{{ conversionTarget.status }} · {{ conversionTarget.rowVersion }}</dd></div></dl>
      <p v-if="conversionError" class="followup-alert followup-alert-error" role="alert">{{ conversionError }}</p><p v-if="conversionNotice" class="followup-alert followup-alert-info">{{ conversionNotice }}</p>
      <form class="form-grid form-grid-2" @submit.prevent="submitGuestConversion()">
        <label>既有患者ID<input v-model="conversionPatientId" inputmode="numeric" autocomplete="off" required maxlength="19" :disabled="!!conversionPending" placeholder="从上方患者检索选择，或输入已有患者ID" /></label>
        <label>核验依据引用（非秘密）<input v-model="conversionEvidenceRef" required maxlength="80" :disabled="!!conversionPending" placeholder="例如前台核验记录编号" /></label>
        <label class="span-2 guest-confirmation"><input v-model="conversionConfirmedInPerson" type="checkbox" required :disabled="!!conversionPending" />我已在现场独立核验来访者身份，并确认所选患者ID属于本人。</label>
        <div class="span-2 form-actions"><button v-if="!conversionPending" class="button button-primary" :disabled="conversionBusy || !hasClinicAccess()">核验并沿原预约号转换</button></div>
      </form>
      <div v-if="conversionPending" class="followup-alert followup-alert-warn guest-conversion-pending"><strong>原转换请求待核对 · {{ conversionPending.reservation.reservationNo }}</strong><span>原患者ID #{{ conversionPending.payload.patientId }}、依据引用和版本{{ conversionPending.payload.expectedRowVersion }}已冻结。</span><button class="button button-small button-secondary" :disabled="conversionBusy" @click="refreshGuestReservations">只读刷新并核对原预约号</button><button v-if="conversionPending.phase === 'retryable'" class="button button-small button-primary" :disabled="conversionBusy" @click="submitGuestConversion(conversionPending, true)">沿用完全相同的请求重试</button></div>
    </section>
    <section v-if="conversionReceipt" class="panel guest-conversion-receipt"><strong>原访客预约 {{ conversionReceipt.reservationNo }} 已关联普通预约</strong><span>预约ID #{{ conversionReceipt.appointmentId }} · {{ conversionReceipt.replayed ? '经原号只读核对确认' : '服务端已确认转换' }}</span><p class="muted">普通预约沿用原预约号；患者医疗资料仍须通过既有患者路径和服务端权限读取。</p></section>

    <section class="panel guest-reservation-list">
      <div class="section-heading"><div><p class="eyebrow">独立访客记录 · 真实预约占号</p><h2>访客预约（待到店核验）</h2><p class="muted">未核验记录不属于患者档案，不显示患者病历；只展示前台办理所需的预约号、时间、牙医、联系人和状态。</p></div><button class="button button-quiet" :disabled="guestListBusy || conversionBusy" @click="refreshGuestReservations">↻ {{ conversionPending ? '核对原预约' : '刷新访客预约' }}</button></div>
      <p v-if="guestListError" class="followup-alert followup-alert-error" role="alert">{{ guestListError }}</p>
      <form v-if="guestCancelTarget" class="form-grid" @submit.prevent="cancelGuestForClinic">
        <strong>撤销访客预约 {{ guestCancelTarget.reservationNo }}</strong>
        <label>撤销原因及联系情况 <input v-model="guestCancelReason" maxlength="500" required :disabled="guestCancelBusy"></label>
        <div class="form-actions">
          <button class="secondary" type="button" :disabled="guestCancelBusy" @click="guestCancelTarget=undefined">返回</button>
          <button data-readonly-write type="submit" :disabled="guestCancelBusy">确认诊所撤销</button>
        </div>
      </form>
      <div v-if="guestListBusy && !guestReservations.length" class="loading-state"><span class="spinner"></span>正在读取本店访客预约…</div>
      <div v-else-if="!guestReservations.length" class="empty-inline">当前列表没有访客预约记录。此处只展示服务端返回的数据。</div>
      <div v-else class="table-wrap"><table><thead><tr><th>预约号</th><th>预约时段</th><th>牙医</th><th>联系人</th><th>需求</th><th>状态</th><th>办理</th></tr></thead><tbody>
        <tr v-for="row in guestReservations" :key="row.reservationId"><td><strong>{{ row.reservationNo }}</strong><small class="cell-sub">医疗身份待到店核验</small></td><td class="nowrap">{{ formatShanghai(row.startAt) }}<small class="cell-sub">至 {{ formatShanghai(row.endAt) }}</small></td><td>牙医 #{{ row.dentistId }}</td><td>{{ row.contactName }}<small class="cell-sub">{{ row.contactPhone }}</small></td><td class="truncate-cell">{{ row.requestedTreatments }}</td><td><span class="badge" :class="row.status === '已转换' ? 'badge-success' : row.status === '待到诊' ? 'badge-warning' : 'badge-neutral'">{{ row.status }}</span><small v-if="row.convertedAppointmentId" class="cell-sub">普通预约 #{{ row.convertedAppointmentId }}</small></td><td><button v-if="row.status === '待到诊'" class="button button-small button-secondary" :disabled="!!conversionPending" @click="startGuestConversion(row)">到店核验转换</button><span v-else class="muted">只读</span><button v-if="row.status === '待到诊' && !identity.readOnly" data-readonly-write class="button button-small button-quiet" :disabled="guestCancelBusy || !!conversionPending" @click="guestCancelTarget=row; guestCancelReason=''">诊所撤销</button></td></tr>
      </tbody></table></div>
    </section>

    <section class="panel">
      <div class="section-heading"><div><h2>预约与接诊</h2><p class="muted">当前接口返回 {{ appointments.length }} 条。列表仅展示业务记录，不汇总经营指标。</p></div><button class="button button-quiet" :disabled="busyList" @click="loadAppointments">↻ 刷新</button></div>
      <div v-if="busyList" class="loading-state"><span class="spinner"></span>正在读取服务端预约…</div>
      <div v-else-if="!appointments.length" class="empty-state"><div class="empty-icon">◷</div><strong>当前暂无预约记录</strong><span>建立患者档案后，可从真实开放时段发起代约。</span></div>
      <div v-else class="table-wrap"><table><thead><tr><th>预约 / 患者</th><th>预约时段</th><th>接诊牙医</th><th>需求</th><th>预约状态</th><th>诊疗状态</th><th>操作</th></tr></thead><tbody>
        <tr v-for="row in appointments" :key="String(row.appointment_id)"><td><strong>{{ row.appointment_no }}</strong><small class="cell-sub">患者 #{{ row.patient_id }}</small></td><td class="nowrap">{{ formatShanghai(row.scheduled_start) }}<small class="cell-sub">至 {{ formatShanghai(row.scheduled_end) }}</small></td><td>牙医 #{{ row.dentist_id }}</td><td class="truncate-cell">{{ row.requested_treatments }}</td><td><StatusBadge :value="String(row.appointment_status)" /></td><td><StatusBadge :value="String(row.treatment_status)" /></td><td><div class="table-actions"><button class="button button-small button-quiet" @click="fulfillmentRow = row">履约详情</button><button data-readonly-write v-if="['待到诊','待核实'].includes(String(row.appointment_status))" class="button button-small button-secondary" @click="arrive(row)">登记到诊</button><button v-if="row.treatment_status === '已完成'" class="button button-small button-primary" @click="viewOrIssue(row)">查看 / 发出结算</button></div></td></tr>
      </tbody></table></div>
    </section>

    <section v-if="invoice" class="panel invoice-panel">
      <div class="section-heading"><div><p class="eyebrow">服务端结算结果</p><h2>结算单 {{ invoice.invoice_no }}</h2><p class="muted">状态：{{ invoice.status }} · 创建于 {{ formatShanghai(invoice.created_at) }}</p></div><div class="invoice-total"><span>应结金额</span><strong>¥ {{ invoice.total_amount }}</strong></div></div>
      <div v-if="invoice.zero_amount_reason" class="zero-reason"><span class="zero-icon">ⓘ</span><span><strong>零额说明</strong>{{ invoice.zero_amount_reason }}</span></div>
      <div class="table-wrap"><table><thead><tr><th>#</th><th>项目</th><th>数量</th><th>单价</th><th>金额</th></tr></thead><tbody><tr v-for="line in (invoice.lines as Row[])" :key="String(line.invoice_line_id)"><td>{{ line.line_no }}</td><td>{{ line.name_snapshot }}<small class="cell-sub">单位：{{ line.unit_snapshot }}</small></td><td>{{ line.units_snapshot }}</td><td>¥ {{ line.unit_price_snapshot }}</td><td><strong>¥ {{ line.subtotal }}</strong><small v-if="String(line.subtotal) === '0.00' || String(line.subtotal) === '0'" class="cell-sub">零价项目</small></td></tr></tbody></table></div>
      <InvoiceActions :invoice="invoice" :read-only="identity.readOnly" @updated="invoice=$event" />
      <div class="invoice-footer"><span>结算单号 {{ invoice.invoice_no }} · 由服务端按本次已确认治疗完整生成</span><strong>合计　¥ {{ invoice.total_amount }}</strong></div>
    </section>
    </template>
    <FrontDeskFollowups v-else-if="canUseFollowups" :clinics="clinics" :identity="identity" />
  </div>
  <FulfillmentPanel v-if="fulfillmentRow && workMode === 'appointments' && hasClinicAccess()" :key="`${clinicId}:${fulfillmentRow.appointment_id}:${identity.accountId}`" :source-row="fulfillmentRow" :clinics="authorizedClinics" :identity="identity" @close="fulfillmentRow = null" @updated="loadAppointments" />
</template>
