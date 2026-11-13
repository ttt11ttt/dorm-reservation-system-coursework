<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { api, formatShanghai, sessionToken, shanghaiOffset } from '../api'
import type { Clinic, Identity } from '../api'
import { hasFrontDeskGrant, isExplicitFrontDeskTarget } from '../fulfillment-core.js'

type Row = Record<string, unknown>
type WindowRange = { start: number; end: number; scheduleId: string; dentistId: string }
type OccupiedRange = { start: number; end: number; dentistId: string }
type ReschedulePayload = {
  expectedRowVersion: number
  appointmentNo: string
  clinicId: string
  dentistId: string
  scheduleId: string
  scheduledStart: string
  scheduledEnd: string
  bookingChannel: '电话' | '柜台'
  requestedTreatments: string
  specialNeeds: string | null
  reason: string
  responsibility: '患者' | '诊所' | '误录'
  exempt: boolean
}
const props = defineProps<{ appointment: Row; clinics: Clinic[]; identity: Identity; appointmentNo: string; disabled?: boolean }>()
const emit = defineEmits<{ submit: [payload: ReschedulePayload]; cancel: [] }>()
const targetClinicId = ref('')
const dentists = ref<Row[]>([])
const windows = ref<WindowRange[]>([])
const occupied = ref<OccupiedRange[]>([])
const availabilityAsOf = ref(0)
const loading = ref(false)
const error = ref('')
const targetDentistId = ref('')
const startInput = ref('')
const durationChoice = ref('30')
const customDuration = ref('45')
const bookingChannel = ref<'电话' | '柜台' | ''>('')
const reason = ref('')
const responsibility = ref<'患者' | '诊所' | '误录' | ''>('')
const exempt = ref(false)
const requestedTreatments = ref(String(props.appointment.requested_treatments ?? ''))
const specialNeeds = ref(String(props.appointment.special_needs ?? ''))
const showReview = ref(false)
let generation = 0
let disposed = false

const allowedClinics = computed(() => props.clinics.filter((clinic) => hasFrontDeskGrant(props.identity, clinic.clinic_id)))
const durationMinutes = computed(() => durationChoice.value === 'custom' ? Number.parseInt(customDuration.value, 10) : Number.parseInt(durationChoice.value, 10))
const chosenWindow = computed(() => {
  if (!startInput.value || !Number.isInteger(durationMinutes.value) || durationMinutes.value < 1) return null
  const start = Date.parse(`${startInput.value}:00+08:00`)
  if (!Number.isFinite(start)) return null
  const end = start + durationMinutes.value * 60_000
  const window = windows.value.find((item) => item.dentistId === targetDentistId.value && start >= item.start && end <= item.end)
  const overlap = occupied.value.some((item) => item.dentistId === targetDentistId.value && item.start < end && item.end > start)
  const afterNow = start >= availabilityAsOf.value
  return { start, end, window, overlap, afterNow, valid: !!window && !overlap && afterNow }
})
const endInputLabel = computed(() => chosenWindow.value ? formatLocal(new Date(chosenWindow.value.end)).replace('T', ' ') : '')
const targetClinicName = computed(() => allowedClinics.value.find((clinic) => clinic.clinic_id === targetClinicId.value)?.name ?? '未选择')
const sourceClinicName = computed(() => props.clinics.find((clinic) => clinic.clinic_id === String(props.appointment.clinic_id))?.name ?? `门店 #${props.appointment.clinic_id}`)
const canReview = computed(() => isExplicitFrontDeskTarget(String(props.appointment.clinic_id), targetClinicId.value, allowedClinics.value.map((clinic) => clinic.clinic_id)))

watch(targetClinicId, async (clinicId) => {
  generation += 1
  const requestGeneration = generation
  dentists.value = []; windows.value = []; occupied.value = []; targetDentistId.value = ''; startInput.value = ''; availabilityAsOf.value = 0; error.value = ''; showReview.value = false
  if (!clinicId) return
  await loadTargetResources(clinicId, requestGeneration)
})
watch(() => props.appointment.appointment_id, () => resetForm())
watch(() => props.appointment.row_version, (next, previous) => {
  if (next !== previous) {
    showReview.value = false
    error.value = '预约版本已更新。已保留本表单内容，请核对新状态后重新确认。'
  }
})
onUnmounted(() => { disposed = true; generation += 1 })

function current(requestGeneration: number, clinicId: string, token: string | null) {
  return !disposed && generation === requestGeneration && targetClinicId.value === clinicId && sessionToken() === token
}
function formatLocal(date: Date) { return new Date(date.getTime() + 8 * 60 * 60 * 1000).toISOString().slice(0, 16) }
function message(value: unknown) { return value instanceof Error ? value.message : '无法读取所选目标门店的真实出诊时段。' }
async function loadTargetResources(clinicId: string, requestGeneration: number) {
  loading.value = true
  const token = sessionToken()
  try {
    const [people, availability] = await Promise.all([
      api<Row[]>(`/dentists?clinicId=${encodeURIComponent(clinicId)}`),
      api<{ asOf: string; windows: Row[]; occupied: Row[] }>(`/availability?clinicId=${encodeURIComponent(clinicId)}`),
    ])
    if (!current(requestGeneration, clinicId, token)) return
    dentists.value = people
    availabilityAsOf.value = new Date(availability.asOf).getTime()
    windows.value = availability.windows.map((window) => ({ start: new Date(String(window.start_at)).getTime(), end: new Date(String(window.end_at)).getTime(), scheduleId: String(window.schedule_id), dentistId: String(window.dentist_id) }))
    occupied.value = availability.occupied.map((item) => ({ start: new Date(String(item.scheduled_start)).getTime(), end: new Date(String(item.scheduled_end)).getTime(), dentistId: String(item.dentist_id) }))
  } catch (cause) {
    if (current(requestGeneration, clinicId, token)) error.value = message(cause)
  } finally {
    if (current(requestGeneration, clinicId, token)) loading.value = false
  }
}
function resetForm() {
  generation += 1
  targetClinicId.value = ''; dentists.value = []; windows.value = []; occupied.value = []; targetDentistId.value = ''; startInput.value = ''; reason.value = ''; responsibility.value = ''; bookingChannel.value = ''; exempt.value = false; showReview.value = false
  requestedTreatments.value = String(props.appointment.requested_treatments ?? ''); specialNeeds.value = String(props.appointment.special_needs ?? '')
}
function reviewChange() {
  error.value = ''
  if (!canReview.value) { error.value = '请选择已获前台授权的目标门店；目标门店不会自动套用当前门店。'; return }
  if (!chosenWindow.value?.valid) { error.value = '开始时间与时长必须完整落入服务端真实出诊窗口，并避开已返回的牙医占用。'; return }
  if (!targetDentistId.value || !bookingChannel.value || !responsibility.value || !reason.value.trim() || !requestedTreatments.value.trim()) { error.value = '请明确选择牙医、办理渠道、责任、原因及本次需求。'; return }
  showReview.value = true
}
function confirmReschedule() {
  if (!chosenWindow.value?.valid || !chosenWindow.value.window) return
  const rowVersion = Number(props.appointment.row_version)
  if (!Number.isInteger(rowVersion) || rowVersion < 0) { error.value = '服务端未返回有效版本号，请重新读取预约详情。'; return }
  emit('submit', {
    expectedRowVersion: rowVersion,
    appointmentNo: props.appointmentNo,
    clinicId: targetClinicId.value,
    dentistId: targetDentistId.value,
    scheduleId: chosenWindow.value.window.scheduleId,
    scheduledStart: shanghaiOffset(startInput.value),
    scheduledEnd: shanghaiOffset(formatLocal(new Date(chosenWindow.value.end))),
    bookingChannel: bookingChannel.value as '电话' | '柜台',
    requestedTreatments: requestedTreatments.value.trim(),
    specialNeeds: specialNeeds.value.trim() || null,
    reason: reason.value.trim(),
    responsibility: responsibility.value as '患者' | '诊所' | '误录',
    exempt: exempt.value,
  })
}
</script>

<template>
  <section class="fulfillment-action-form">
    <div class="fulfillment-form-heading"><div><p class="eyebrow">原预约保持不变，服务端确认后才建立唯一后继</p><h3>改约信息</h3></div><button class="icon-button" type="button" aria-label="关闭改约表单" @click="emit('cancel')">×</button></div>
    <div class="source-target"><div><span>来源门店（固定）</span><strong>{{ sourceClinicName }}</strong></div><span class="source-arrow">→</span><label>目标门店<select v-model="targetClinicId" :disabled="disabled" required><option value="">请明确选择目标门店</option><option v-for="clinic in allowedClinics" :key="clinic.clinic_id" :value="clinic.clinic_id">{{ clinic.name }}{{ clinic.clinic_id === String(appointment.clinic_id) ? '（原门店，需主动选择）' : '' }}</option></select><small>原门店和目标门店都必须有前台授权；目标门店不会自动预填。</small></label></div>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <div class="form-grid form-grid-3">
      <label>目标牙医<select v-model="targetDentistId" :disabled="disabled || loading || !targetClinicId" required><option value="">{{ loading ? '读取真实出诊…' : dentists.length ? '选择目标牙医' : '无有效出诊牙医' }}</option><option v-for="dentist in dentists" :key="String(dentist.dentist_id)" :value="String(dentist.dentist_id)">{{ dentist.name }}</option></select></label>
      <label>开始时间 · Asia/Shanghai<input v-model="startInput" type="datetime-local" :disabled="disabled || loading || !windows.length" required /></label>
      <label>时长<select v-model="durationChoice" :disabled="disabled"><option value="30">30分钟</option><option value="45">45分钟</option><option value="60">60分钟</option><option value="90">90分钟</option><option value="custom">自定义</option></select></label>
      <label v-if="durationChoice === 'custom'">自定义分钟数<input v-model="customDuration" type="number" min="1" step="1" inputmode="numeric" /></label>
      <label>新预约办理渠道<select v-model="bookingChannel" :disabled="disabled" required><option value="">明确选择</option><option value="电话">电话</option><option value="柜台">柜台</option></select></label>
      <label>责任认定<select v-model="responsibility" :disabled="disabled" required><option value="">明确选择</option><option value="患者">患者</option><option value="诊所">诊所</option><option value="误录">误录</option></select></label>
      <label class="span-2">改约原因<input v-model="reason" :disabled="disabled" maxlength="8000" required placeholder="记录患者请求或诊所原因；若申请豁免，说明具体依据" /></label>
      <label class="check-label"><input v-model="exempt" :disabled="disabled" type="checkbox" />申请特殊情况豁免</label>
      <label class="span-2">预约需求<textarea v-model="requestedTreatments" :disabled="disabled" rows="2" maxlength="8000" required /></label>
      <label>特殊需求（选填）<input v-model="specialNeeds" :disabled="disabled" maxlength="8000" /></label>
    </div>
    <div v-if="targetClinicId" class="interval-preview" :class="chosenWindow?.valid ? 'interval-valid' : 'interval-invalid'">
      <span class="interval-mark">{{ chosenWindow?.valid ? '✓' : '!' }}</span><span><strong>{{ chosenWindow?.valid ? '时段在真实开放/出诊窗口内' : loading ? '正在读取服务端时段' : '请检查时段与占用' }}</strong><small v-if="chosenWindow">{{ startInput.replace('T',' ') }} — {{ endInputLabel }} · {{ durationMinutes }}分钟</small><small v-if="chosenWindow?.overlap">牙医该时间已有待到诊/已到诊/待核实占用。</small><small v-if="chosenWindow && !chosenWindow.window">时段超出目标门店开放或目标牙医出诊范围。</small><small v-if="chosenWindow && !chosenWindow.afterNow">开始时间早于服务端查询时刻。</small><small>当前页面无法看到患者跨店的其他预约；最终患者冲突由服务端复核。</small></span><button class="text-button" type="button" :disabled="!targetClinicId || loading" @click="targetClinicId && loadTargetResources(targetClinicId, generation)">重新读取</button>
    </div>
    <div v-if="showReview" class="confirmation-summary" role="alert"><p class="eyebrow">请确认后提交</p><h4>将原预约改为以下人工安排</h4><dl><div><dt>门店</dt><dd>{{ sourceClinicName }} → {{ targetClinicName }}</dd></div><div><dt>时间</dt><dd>{{ formatShanghai(shanghaiOffset(startInput)) }} — {{ endInputLabel }}（{{ durationMinutes }}分钟）</dd></div><div><dt>责任 / 豁免</dt><dd>{{ responsibility }} · {{ exempt ? '申请豁免' : '不申请豁免' }}</dd></div><div><dt>渠道 / 原因</dt><dd>{{ bookingChannel }} · {{ reason }}</dd></div><div><dt>幂等编号</dt><dd>{{ appointmentNo }}</dd></div></dl><p class="muted">原预约会保留为“已改约”历史；提交失败时不会自动取消原安排。临近取消/改约仍释放原时段，违约或豁免按服务端规则记录。</p><div class="action-confirm-buttons"><button class="button button-quiet" type="button" @click="showReview = false">返回修改</button><button class="button button-primary" type="button" :disabled="disabled" @click="confirmReschedule">确认改约并提交</button></div></div>
    <div v-else class="action-form-footer"><small>改约操作号 {{ appointmentNo }} 固定用于同一原请求的安全重试。</small><button class="button button-primary" type="button" :disabled="disabled || loading || !chosenWindow?.valid" @click="reviewChange">核对改约内容</button></div>
  </section>
</template>
