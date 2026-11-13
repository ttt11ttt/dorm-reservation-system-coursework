<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { api, formatShanghai, sessionToken } from '../api'
import type { Identity } from '../api'
import { loadReminderPendingWrites, type PendingWrite } from '../fulfillment-core.js'
import { createReminderWriteQueue } from '../fulfillment-write-queue.js'
import { assertReminderPlanAssociation, canReadReminderAdmin, createScopedBusyState, parseReminderPlanResult, reminderDispatchOptions, reminderScope } from '../reminder-core.js'
import type { ReminderPlanResult } from '../reminder-core.js'

type BusinessType = 'appointment' | 'followup'
const props = defineProps<{ businessType: BusinessType; businessId: string; clinicId: string; identity: Identity }>()
const endpointRoot = computed(() => props.businessType === 'appointment' ? '/staff/appointments' : '/staff/followup-plans')
const entityKey = computed(() => `${props.businessType}:${props.businessId}`)
const allowed = computed(() => canReadReminderAdmin(props.identity, props.clinicId))
const result = ref<ReminderPlanResult | null>(null)
const previewVerified = ref(false)
const pending = ref<PendingWrite | null>(null)
const busy = ref(false)
const storageReadable = ref(true)
const error = ref('')
const notice = ref('')
let generation = 0
let disposed = false
const activity = createScopedBusyState(scopeKey())
const queue = createReminderWriteQueue(() => window.sessionStorage, async (record) => {
  const response = await api<Record<string, unknown>>(record.endpoint, reminderDispatchOptions(record))
  const [recordType, businessId] = String(record.appointmentId).split(':', 2)
  if (recordType !== 'appointment' && recordType !== 'followup') throw new Error('待核对提醒对象类型无效；未发送。')
  const businessType: BusinessType = recordType
  assertReminderPlanAssociation(businessType, businessId, record.sourceClinicId, response)
  return response
})

onMounted(() => { loadPending(); if (!allowed.value) error.value = '当前账号没有本对象门店的提醒管理读取权限。' })
onUnmounted(() => { disposed = true; generation += 1; activity.reset(`disposed:${scopeKey()}`); busy.value = false })
watch([() => props.identity.accountId, () => props.clinicId, entityKey], () => {
  generation += 1; activity.reset(scopeKey()); busy.value = false; result.value = null; previewVerified.value = false; pending.value = null; error.value = ''; notice.value = ''; storageReadable.value = true
  loadPending()
})

function scopeKey() { return `${props.identity.accountId}:${props.clinicId}:${entityKey.value}` }
function scope(action: 'reminder-rule' | 'reminder-rebuild') { return reminderScope(props.identity.accountId, props.clinicId, entityKey.value, action) }
function current(requestGeneration: number, token: string | null, accountId: string, clinicId: string, key: string) {
  return !disposed && allowed.value && generation === requestGeneration && token === sessionToken() && accountId === props.identity.accountId && clinicId === props.clinicId && key === entityKey.value
}
function loadPending() {
  try {
    const records = loadReminderPendingWrites(window.sessionStorage, { accountId: props.identity.accountId, sourceClinicId: props.clinicId, appointmentId: entityKey.value })
    pending.value = records.find((item) => item.action === 'reminder-rebuild') ?? null
    storageReadable.value = true
  } catch {
    pending.value = null; storageReadable.value = false
    error.value = '无法读取本机提醒原请求；已停用重建。请恢复会话存储后重新打开对象。'
  }
}
async function preview() {
  if (!allowed.value) { activity.reset(scopeKey()); busy.value = false; error.value = '当前账号没有本对象门店的提醒管理读取权限。'; return }
  if (busy.value) return
  const requestGeneration = generation; const token = sessionToken(); const accountId = props.identity.accountId; const clinicId = props.clinicId; const key = entityKey.value
  const ticket = activity.begin('object')
  busy.value = true; error.value = ''; notice.value = ''
  try {
    const raw = await api<unknown>(`${endpointRoot.value}/${encodeURIComponent(props.businessId)}/reminders/preview`)
    if (!current(requestGeneration, token, accountId, clinicId, key)) return
    result.value = assertReminderPlanAssociation(props.businessType, props.businessId, props.clinicId, raw)
    previewVerified.value = true
    notice.value = '已读取服务端当前规则、业务状态与提醒历史；预览未写入。'
  } catch (cause) { if (current(requestGeneration, token, accountId, clinicId, key)) error.value = message(cause) }
  finally { if (activity.finish(ticket)) busy.value = false }
}
function message(cause: unknown) { return cause instanceof Error ? cause.message : '提醒操作未完成。' }
async function rebuild() {
  if (!allowed.value || !storageReadable.value || !previewVerified.value || pending.value || busy.value) return
  await submitOrRetry(false)
}
async function retryOriginal() {
  if (!pending.value || !storageReadable.value || busy.value) return
  await submitOrRetry(true)
}
async function submitOrRetry(retry: boolean) {
  const requestGeneration = generation; const token = sessionToken(); const accountId = props.identity.accountId; const clinicId = props.clinicId; const key = entityKey.value
  const record = pending.value
  const ticket = activity.begin('object')
  busy.value = true; error.value = ''; notice.value = ''
  try {
    const outcome = retry && record
      ? await queue.retry(scope('reminder-rebuild'), record)
      : await queue.submit(scope('reminder-rebuild'), { endpoint: `${endpointRoot.value}/${encodeURIComponent(props.businessId)}/reminders/rebuild`, method: 'POST', payload: {}, operationId: `reminder-${props.businessType}-${props.businessId}` })
    if (!current(requestGeneration, token, accountId, clinicId, key)) return
    if (outcome.kind === 'success') {
      pending.value = null
      result.value = parseReminderPlanResult(outcome.result)
      previewVerified.value = false
      notice.value = '服务端已处理此对象的提醒重建决策。发送器未实现，不能视为已发送。'
    } else if (outcome.kind === 'unknown' || outcome.kind === 'preserved') {
      loadPending()
      if (current(requestGeneration, token, accountId, clinicId, key)) error.value = describeWriteError(outcome.error) || '重建结果未知；已保留固定对象路径和原请求。请先核对，再沿用原请求重试。'
    } else if (outcome.kind === 'rejected') {
      loadPending()
      if (current(requestGeneration, token, accountId, clinicId, key)) error.value = describeWriteError(outcome.error) || '服务端明确拒绝了本次重建，没有显示为成功。'
    } else if (outcome.kind === 'storage-error') {
      storageReadable.value = false
      error.value = '无法安全保存或读取原提醒请求；没有新建替代请求。请恢复会话存储。'
    } else {
      loadPending()
      error.value = outcome.kind === 'pending-exists' ? '本对象已有提醒请求待核对；未发送新操作。' : outcome.kind === 'busy' ? '本提醒操作正在处理。' : '原请求已变化；未发送。'
    }
  } catch (cause) {
    if (current(requestGeneration, token, accountId, clinicId, key)) error.value = message(cause)
  } finally { if (activity.finish(ticket)) busy.value = false }
}
function describeWriteError(cause: unknown) {
  const code = String((cause as { code?: string } | undefined)?.code ?? '')
  if (code === 'RULE_CHANGE_REQUIRES_RECONCILIATION') return '配置变更被服务端阻止：本店仍有待发送、发送中或失败记录，须逐对象核对。没有提供强制清空或全店迁移。'
  if (code === 'RULE_SNAPSHOT_CONFLICT') return '提醒配置快照已变化；请重新读取当前规则后再编辑。'
  if (code === 'REMINDER_RULE_CONFIRMATION_UNVERIFIED') return '规则保存响应未确认原门店与规则ID；原请求仍待核对。'
  return message(cause)
}
</script>

<template>
  <section v-if="allowed" class="followup-subpanel reminder-object-panel">
    <div class="section-heading"><div><h3>{{ businessType === 'appointment' ? '本预约提醒' : '本复诊计划提醒' }}</h3><p class="muted">先读取并核对当前服务端对象及负责门店，之后才可重建；重建不发送短信/站内消息。</p></div><div class="action-buttons"><button class="button button-small button-secondary" :disabled="busy" @click="preview">{{ busy ? '处理中…' : '预览提醒计划' }}</button><button class="button button-small button-primary" :disabled="busy || !!pending || !storageReadable || !previewVerified" @click="rebuild">单对象重建</button></div></div>
    <p v-if="error" class="followup-alert followup-alert-error" role="alert">{{ error }}</p><p v-if="notice" class="followup-alert followup-alert-info">{{ notice }}</p>
    <article v-if="pending" class="followup-pending-card"><strong>重建结果待核对 · 原操作 {{ pending.operationId }}</strong><p>对象、门店、POST路径与空请求体已按当前账号保存；未知结果不会另换操作。</p><button class="button button-small button-secondary" :disabled="busy || !storageReadable" @click="retryOriginal">沿用原操作重试</button></article>
    <div v-if="result" class="reminder-result" aria-live="polite">
      <div class="reminder-result-meta"><span>对象 {{ result.businessType }} #{{ result.businessId }} · 门店 #{{ result.clinicId }}</span><span v-if="result.asOf">服务端核对 {{ formatShanghai(result.asOf) }}</span><span>动作 {{ result.actionCount }} 项<span v-if="result.actionsTruncated">（响应仅展示前100项，服务端明确标记已截断）</span></span><span>{{ result.applied ? '服务端已应用单对象计划' : '仅预览，未应用' }}</span></div>
      <p v-if="result.issues.length" class="followup-alert followup-alert-warn"><strong>服务端反馈的缺项/核对项</strong><span v-for="issue in result.issues" :key="issue">{{ issue }}</span></p><p v-else class="muted">服务端未返回缺项。</p>
      <p v-if="result.requiresReconciliation" class="followup-alert followup-alert-warn">服务端标记仍需核实；不得将此结果理解为消息已发送。</p>
      <p class="followup-alert followup-alert-info">消息发送：{{ result.delivery || '未返回状态' }} · 全店重建：{{ result.batchRebuild || '未返回状态' }}。当前消息发送器及全店重建均未实现。</p>
      <div v-if="result.actions.length" class="reminder-action-list"><article v-for="(item, index) in result.actions" :key="`${item.messageId ?? 'new'}:${item.channel}:${index}`"><div><strong>{{ item.operation }} · {{ item.channel }} · {{ item.status }}</strong><small>{{ item.messageId ? `消息 #${item.messageId}` : '预览动作尚无消息ID' }} · {{ item.plannedAt ? formatShanghai(item.plannedAt) : '无计划时间' }}</small><p v-if="item.content">{{ item.content }}</p><small v-if="item.recipientMasked">收件人（掩码）{{ item.recipientMasked }}</small></div></article></div>
      <div v-else class="empty-inline">服务端未返回动作。</div>
    </div>
    <p v-if="!storageReadable" class="followup-alert followup-alert-error">本机提醒请求存储不可读，写入已停用。</p>
  </section>
</template>
