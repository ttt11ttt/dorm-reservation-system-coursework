<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { api, sessionToken } from '../api'
import type { Identity } from '../api'
import NoticeBar from './NoticeBar.vue'
import {
  formatFollowupError, generatePositive63BitId,
} from '../followup-core.js'
import { createFollowupWriteQueue } from '../fulfillment-write-queue.js'
import { loadFollowupPendingWrites, removePendingWrite, type PendingWrite } from '../fulfillment-core.js'
import { followupScope } from '../followup-core.js'

type Row = Record<string, unknown>
const props = defineProps<{ appointment: Row; clinicId: string; identity: Identity }>()
const emit = defineEmits<{ created: [] }>()
const open = ref(false)
const suggestedDate = ref('')
const recommendation = ref('')
const planId = ref('')
const saving = ref(false)
const error = ref('')
const notice = ref('')
const pending = ref<PendingWrite | null>(null)
const storageReadable = ref(true)
const manualVerified = ref(false)
let disposed = false
const sourceId = () => String(props.appointment.appointment_id)
const writeQueue = createFollowupWriteQueue(() => window.sessionStorage, (record) => api<Record<string, unknown>>(record.endpoint, { method: record.method, body: record.payload }))

onMounted(loadPending)
onUnmounted(() => { disposed = true })
watch([() => props.identity.accountId, () => props.clinicId, sourceId], () => {
  open.value = false; suggestedDate.value = ''; recommendation.value = ''; planId.value = ''
  pending.value = null; storageReadable.value = true; error.value = ''; notice.value = ''
  loadPending()
})

function scope() { return followupScope(props.identity.accountId, props.clinicId, `source:${sourceId()}`, 'followup-create') }
function loadPending() {
  try {
    pending.value = loadFollowupPendingWrites(window.sessionStorage, { accountId: props.identity.accountId, sourceClinicId: props.clinicId, appointmentId: `source:${sourceId()}` }).find((item) => item.action === 'followup-create') ?? null
    storageReadable.value = true
    if (pending.value) { planId.value = String(pending.value.payload.planId ?? ''); open.value = true }
    return true
  } catch {
    storageReadable.value = false
    error.value = '无法读取复诊建议原请求；已停用写入。'
    return false
  }
}
function start() {
  if (!storageReadable.value || pending.value) return
  planId.value = generatePositive63BitId()
  suggestedDate.value = ''; recommendation.value = ''; error.value = ''; notice.value = ''; open.value = true
}
async function submit() {
  if (saving.value || pending.value || !storageReadable.value) return
  if (!suggestedDate.value || !recommendation.value.trim() || !planId.value) { error.value = '请填写建议日期与复诊建议。'; return }
  saving.value = true; error.value = ''; notice.value = ''
  const token = sessionToken(); const accountId = props.identity.accountId; const clinic = props.clinicId; const source = sourceId()
  const payload = { planId: planId.value, suggestedDate: suggestedDate.value, recommendation: recommendation.value.trim() }
  const outcome = await writeQueue.submit(scope(), { endpoint: `/dentist/appointments/${encodeURIComponent(source)}/followup-plans`, method: 'POST', payload })
  const current = !disposed && token === sessionToken() && accountId === props.identity.accountId && clinic === props.clinicId && source === sourceId()
  if (outcome.kind === 'storage-error') { storageReadable.value = false; if (current) error.value = '无法读取或安全保存本次复诊原请求；未发送。请恢复会话存储。' }
  else if (outcome.kind === 'pending-exists' || outcome.kind === 'busy') { loadPending(); if (current) error.value = '此来源就诊已有待核对复诊创建请求，已阻止生成新计划或覆盖旧操作号。' }
  else if (outcome.kind === 'unknown' || outcome.kind === 'preserved') { loadPending(); if (current) error.value = '服务端未确认复诊计划是否已创建。原固定ID与payload已保留，请先读取计划列表再沿用原请求重试。' }
  else if (outcome.kind === 'rejected') { loadPending(); if (current) error.value = formatFollowupError(outcome.error) }
  else if (outcome.kind === 'success') {
    loadPending()
    if (current) {
      const reminder = String(outcome.result?.reminderScheduling ?? '')
      notice.value = `复诊建议已由服务端登记（计划 #${String((outcome.result?.plan as Row | undefined)?.followup_plan_id ?? planId.value)}）。${reminder === 'NOT_IMPLEMENTED' ? '提醒重建/发送尚未实现；没有向患者发送短信或站内消息。' : ''}`
      open.value = false; emit('created')
    }
  }
  saving.value = false
}
async function retry() {
  if (!pending.value || saving.value || !storageReadable.value) return
  saving.value = true; error.value = ''
  const token = sessionToken(); const accountId = props.identity.accountId; const clinic = props.clinicId; const source = sourceId()
  const outcome = await writeQueue.retry(scope(), pending.value)
  const current = !disposed && token === sessionToken() && accountId === props.identity.accountId && clinic === props.clinicId && source === sourceId()
  if (outcome.kind === 'success') {
    loadPending()
    if (current) { notice.value = '服务端确认原复诊创建请求；固定计划ID未改变。提醒重建/发送仍为 NOT_IMPLEMENTED。'; open.value = false; emit('created') }
  } else if (outcome.kind === 'unknown' || outcome.kind === 'preserved') { loadPending(); if (current) error.value = '原请求结果仍未确认，完整payload与固定ID继续保留。恢复原账号/授权后可继续核对。' }
  else if (outcome.kind === 'rejected') { loadPending(); if (current) error.value = formatFollowupError(outcome.error) }
  else if (outcome.kind === 'storage-error') { storageReadable.value = false; if (current) error.value = '无法读取原复诊请求；没有发送重试。' }
  else { loadPending(); if (current) error.value = '原请求不属于当前账号、门店或来源就诊；没有发送。' }
  saving.value = false
}
function manuallyResolve() {
  if (!pending.value) return
  if (!manualVerified.value) { manualVerified.value = true; return }
  try { removePendingWrite(window.sessionStorage, pending.value) }
  catch { storageReadable.value = false; error.value = '无法清除本机提示；原信封仍保留。'; return }
  pending.value = null; manualVerified.value = false; loadPending()
  error.value = '仅清除了本机提示，没有更改服务端计划。'
}
</script>

<template>
  <section class="followup-create-card">
    <NoticeBar tone="error" :title="error" @close="error = ''" />
    <NoticeBar tone="success" :title="notice" @close="notice = ''" />
    <div class="section-heading"><div><h3>登记复诊建议</h3><p class="muted">仅来自当前本人实际完成的就诊上下文；患者、牙医、门店和来源就诊均由服务端确定。</p></div><button v-if="!open" class="button button-secondary" type="button" :disabled="!!pending || !storageReadable" @click="start">＋ 记录复诊建议</button></div>
    <form v-if="open && !pending" class="form-grid form-grid-2 followup-form" @submit.prevent="submit"><label>建议复诊日期<input v-model="suggestedDate" type="date" required /></label><label class="span-2">复诊建议<textarea v-model="recommendation" rows="3" maxlength="2000" required /></label><div class="followup-form-actions span-2"><small>来源就诊 #{{ appointment.appointment_id }} · 计划ID {{ planId }}（正63位以内十进制字符串，固定用于同请求重试）</small><button class="button button-primary" :disabled="saving || !storageReadable">{{ saving ? '提交中…' : '登记建议' }}</button></div><p class="muted span-2">登记建议不会结案。提醒重建与发送为 NOT_IMPLEMENTED，不能据此称已发送。</p></form>
    <div v-if="pending" class="followup-pending-card"><strong>此来源就诊已有待核对创建请求 · ID {{ pending.operationId }}</strong><p>系统不会生成新ID。先读取牙医复诊计划列表，再决定沿用原请求重试。</p><div class="followup-pending-actions"><button class="button button-secondary" type="button" :disabled="saving || !storageReadable" @click="retry">沿用原payload重试</button><button class="text-button" type="button" @click="manuallyResolve">{{ manualVerified ? '确认服务端已核对并清除本机提示' : '已在服务端核对…' }}</button></div></div>
  </section>
</template>
