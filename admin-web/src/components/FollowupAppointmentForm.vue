<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { api, formatShanghai, operationId, sessionToken, shanghaiOffset } from '../api'
import type { Clinic, Identity } from '../api'
import { hasFrontDeskGrant, isExplicitFrontDeskTarget } from '../fulfillment-core.js'
import { canArrangeFollowupAppointment, makeFollowupAppointmentPayload } from '../followup-core.js'

type Row = Record<string, unknown>
type WindowRange = { start: number; end: number; scheduleId: string; dentistId: string }
const props = defineProps<{ plan: Row; clinics: Clinic[]; identity: Identity; disabled: boolean }>()
const emit = defineEmits<{ submit: [payload: Record<string, string>] }>()
const targetClinicId = ref('')
const dentists = ref<Row[]>([])
const windows = ref<WindowRange[]>([])
const occupied = ref<Array<{ start: number; end: number; dentistId: string }>>([])
const availabilityAsOf = ref(0)
const loading = ref(false)
const error = ref('')
const targetDentistId = ref('')
const startInput = ref('')
const duration = ref('30')
const bookingChannel = ref('')
const requestedTreatments = ref(String(props.plan.recommendation ?? ''))
const specialNeeds = ref('')
const appointmentNo = ref(operationId('APT'))
let generation = 0
let disposed = false

const ownerClinicId = computed(() => String(props.plan.clinic_id ?? ''))
const allowedClinics = computed(() => props.clinics.filter((clinic) => hasFrontDeskGrant(props.identity, clinic.clinic_id)))
const hasOwnerAccess = computed(() => canArrangeFollowupAppointment(props.identity, String(props.plan.status ?? ''), ownerClinicId.value, ownerClinicId.value))
const chosenWindow = computed(() => {
  const start = Date.parse(`${startInput.value}:00+08:00`)
  const minutes = Number(duration.value)
  if (!Number.isFinite(start) || !Number.isInteger(minutes) || minutes < 1) return null
  const end = start + minutes * 60_000
  const window = windows.value.find((item) => item.dentistId === targetDentistId.value && start >= item.start && end <= item.end)
  const overlap = occupied.value.some((item) => item.dentistId === targetDentistId.value && item.start < end && item.end > start)
  return { start, end, window, overlap, valid: !!window && !overlap && start >= availabilityAsOf.value }
})
const targetClinicName = computed(() => allowedClinics.value.find((clinic) => clinic.clinic_id === targetClinicId.value)?.name ?? '未选择')
const ownerClinicName = computed(() => props.clinics.find((clinic) => clinic.clinic_id === ownerClinicId.value)?.name ?? `门店 #${ownerClinicId.value}`)

watch(targetClinicId, async (clinicId) => {
  const currentGeneration = ++generation
  dentists.value = []; windows.value = []; occupied.value = []; targetDentistId.value = ''; startInput.value = ''; availabilityAsOf.value = 0; error.value = ''
  if (!clinicId) return
  if (!hasOwnerAccess.value || !hasFrontDeskGrant(props.identity, clinicId)) { error.value = '当前账号必须同时拥有计划负责门店和目标门店的前台权限。'; return }
  loading.value = true
  const token = sessionToken()
  try {
    const [people, availability] = await Promise.all([
      api<Row[]>(`/dentists?clinicId=${encodeURIComponent(clinicId)}`),
      api<{ asOf: string; windows: Row[]; occupied: Row[] }>(`/availability?clinicId=${encodeURIComponent(clinicId)}`),
    ])
    if (!current(currentGeneration, clinicId, token)) return
    dentists.value = people
    availabilityAsOf.value = new Date(availability.asOf).getTime()
    windows.value = availability.windows.map((item) => ({ start: Date.parse(String(item.start_at)), end: Date.parse(String(item.end_at)), scheduleId: String(item.schedule_id), dentistId: String(item.dentist_id) }))
    occupied.value = availability.occupied.map((item) => ({ start: Date.parse(String(item.scheduled_start)), end: Date.parse(String(item.scheduled_end)), dentistId: String(item.dentist_id) }))
  } catch (cause) {
    if (current(currentGeneration, clinicId, token)) error.value = cause instanceof Error ? cause.message : '无法读取目标门店的真实出诊时段。'
  } finally {
    if (current(currentGeneration, clinicId, token)) loading.value = false
  }
})
onUnmounted(() => { disposed = true; generation += 1 })
function current(expected: number, clinicId: string, token: string | null) { return !disposed && generation === expected && targetClinicId.value === clinicId && sessionToken() === token }
function localTime(epoch: number) { return new Date(epoch + 8 * 60 * 60 * 1000).toISOString().slice(0, 16) }
function submit() {
  error.value = ''
  if (!hasOwnerAccess.value || !isExplicitFrontDeskTarget(ownerClinicId.value, targetClinicId.value, allowedClinics.value.map((clinic) => clinic.clinic_id))) { error.value = '计划负责门店和明确选择的目标门店都必须有前台授权。'; return }
  if (!chosenWindow.value?.valid || !chosenWindow.value.window) { error.value = '所选开始时间及持续时长必须落入真实出诊窗口，并避开牙医已有占用。'; return }
  try {
    emit('submit', makeFollowupAppointmentPayload({ appointmentNo: appointmentNo.value, clinicId: targetClinicId.value, dentistId: targetDentistId.value, scheduleId: chosenWindow.value.window.scheduleId, scheduledStart: shanghaiOffset(startInput.value), scheduledEnd: shanghaiOffset(localTime(chosenWindow.value.end)), requestedTreatments: requestedTreatments.value, specialNeeds: specialNeeds.value, bookingChannel: bookingChannel.value as '电话' | '柜台' }))
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '预约内容无效。' }
}
</script>

<template>
  <section class="followup-subpanel">
    <div class="section-heading"><div><h3>为此计划安排复诊</h3><p class="muted">计划负责门店：{{ ownerClinicName }}（{{ ownerClinicId }}）。计划所有权保持不变；患者由服务端按计划绑定。</p></div></div>
    <p v-if="!hasOwnerAccess" class="followup-alert followup-alert-error">当前账号无计划负责门店的前台权限，不能从本计划安排复诊。</p>
    <form v-else class="form-grid form-grid-2 followup-form" @submit.prevent="submit">
      <label>目标门店<select v-model="targetClinicId" :disabled="disabled || loading" required><option value="">请明确选择有权限的目标门店</option><option v-for="clinic in allowedClinics" :key="clinic.clinic_id" :value="clinic.clinic_id">{{ clinic.name }} · #{{ clinic.clinic_id }}{{ clinic.clinic_id === ownerClinicId ? '（计划负责门店，需主动选择）' : '' }}</option></select></label>
      <label>目标牙医<select v-model="targetDentistId" :disabled="disabled || loading || !targetClinicId" required><option value="">{{ loading ? '读取真实出诊…' : '选择目标牙医' }}</option><option v-for="dentist in dentists" :key="String(dentist.dentist_id)" :value="String(dentist.dentist_id)">{{ dentist.name }}</option></select></label>
      <label>开始时间 · Asia/Shanghai<input v-model="startInput" type="datetime-local" :disabled="disabled || loading || !windows.length" required /></label>
      <label>时长<select v-model="duration" :disabled="disabled"><option value="30">30分钟</option><option value="45">45分钟</option><option value="60">60分钟</option><option value="90">90分钟</option></select></label>
      <label>办理渠道<select v-model="bookingChannel" :disabled="disabled" required><option value="">明确选择</option><option value="电话">电话</option><option value="柜台">柜台</option></select></label>
      <label class="span-2">本次预约需求<textarea v-model="requestedTreatments" :disabled="disabled" maxlength="8000" rows="2" required /></label>
      <label class="span-2">特殊需求（选填）<input v-model="specialNeeds" :disabled="disabled" maxlength="8000" /></label>
      <p v-if="targetClinicId" class="muted span-2">目标门店 {{ targetClinicName }} · 服务端查询时刻 {{ availabilityAsOf ? formatShanghai(availabilityAsOf) : '读取中' }}<span v-if="chosenWindow"> · {{ chosenWindow.valid ? '时段有效' : '时段不可用' }} · {{ formatShanghai(startInput ? shanghaiOffset(startInput) : '') }} — {{ formatShanghai(shanghaiOffset(localTime(chosenWindow.end))) }}</span><span v-if="chosenWindow?.overlap"> · 该牙医已有占用</span><span v-else> · 跨门店患者冲突仍由服务端复核</span></p>
      <p v-if="error" class="form-error span-2" role="alert">{{ error }}</p>
      <div class="followup-form-actions span-2"><small>固定预约号 {{ appointmentNo }} · POST /staff/followup-plans/{planId}/appointments</small><button class="button button-primary" :disabled="disabled || loading || !chosenWindow?.valid">确认安排复诊</button></div>
    </form>
  </section>
</template>
