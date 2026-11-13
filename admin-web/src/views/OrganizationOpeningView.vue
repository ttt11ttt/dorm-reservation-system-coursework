<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { api, sessionToken } from '../api'
import type { Identity } from '../api'
import { createRequestLane } from '../staff-admin-ui-coordination.js'
import { createOrganizationWriteState } from '../organization-create-core.js'
import {
  classifyOrganizationOpeningError, clearInvalidOrganizationOpeningMarker, clearOrganizationOpeningMarker,
  loadOrganizationOpeningMarker, normalizeOpeningId, normalizeOrganizationOpeningBody, openingManualRetryAllowed,
  openingNeedsActiveClinicCheck, organizationOpeningContextMatches, organizationOpeningPostReadOutcome, preserveExactOpeningRetryBody,
  organizationOpeningAccess, organizationOpeningClinicRequest, organizationOpeningDetailRequest,
  organizationOpeningListRequest, persistOrganizationOpeningMarker, preserveOpeningClinic,
  preserveOrganizationOpeningDetail, preserveOrganizationOpeningList, preserveOrganizationOpeningReceipt,
} from '../organization-opening-core.js'
import type { OrganizationOpeningBody, OrganizationOpeningMarker, OrganizationOpeningSnapshot } from '../organization-opening-core.js'

const props = defineProps<{ identity: Identity }>()
const access = computed(() => organizationOpeningAccess(props.identity))
const clinicId = ref('')
const confirmedClinic = ref<Record<string, unknown> | null>(null)
const clinicBusy = ref(false)
const clinicError = ref('')
const items = ref<OrganizationOpeningSnapshot[]>([])
const page = ref({ hasMore: false, nextAfterId: null as string | null })
const listBusy = ref(false)
const listError = ref('')
const detail = ref<OrganizationOpeningSnapshot | null>(null)
const detailBusy = ref(false)
const detailError = ref('')
const stopReason = ref('')
const stopBusy = ref(false)
const selectedOpeningId = ref('')
const form = ref({ openingId: '', startLocal: '', endLocal: '', note: '' })
const writeBusy = ref(false)
const writeState = ref<'idle' | 'sending' | 'unknown' | 'conflict' | 'rejected' | 'confirmed'>('idle')
const writeMessage = ref('')
const receipt = ref<{ replayed: boolean; resource: OrganizationOpeningSnapshot; snapshot: OrganizationOpeningSnapshot } | null>(null)
const readAfterPostError = ref('')
const pending = ref<OrganizationOpeningMarker | null>(null)
const markerBlocked = ref(false)
const markerError = ref('')
const markerInspected = ref(false)
const clinicInspected = ref(false)
const openingInspected = ref(false)
const manualRetypeReady = ref(false)
const cleanup = ref({ actorAccountId: '', clinicId: '', openingId: '', confirmed: false })
const cleanupInspected = ref(false)
const actorGeneration = ref(0)
const disposed = ref(false)
let retryBody: OrganizationOpeningBody | null = null
const clinicLane = createRequestLane()
const listLane = createRequestLane()
const detailLane = createRequestLane()
const writeLane = createOrganizationWriteState()
const markerForOtherActor = computed(() => !!pending.value && pending.value.actorAccountId !== String(props.identity.accountId))
const manualRetryAllowed = computed(() => openingManualRetryAllowed({ marker: pending.value, actorAccountId: String(props.identity.accountId), clinicId: clinicId.value, openingId: form.value.openingId, clinicInspected: clinicInspected.value && markerInspected.value && manualRetypeReady.value, openingInspected: openingInspected.value }))
const exactRetryAllowed = computed(() => !!retryBody && !!pending.value && pending.value.state === 'unknown' && pending.value.actorAccountId === String(props.identity.accountId) && pending.value.clinicId === clinicId.value && pending.value.openingId === retryBody.openingId)
const createLocked = computed(() => markerBlocked.value || markerForOtherActor.value || !!pending.value && !manualRetryAllowed.value && !exactRetryAllowed.value)

function storage() { return window.sessionStorage }
function clearRetryBody() {
  if (retryBody) { retryBody.startAt = ''; retryBody.endAt = ''; retryBody.note = null }
  retryBody = null
}
function clearPrivateValues() {
  clearRetryBody(); form.value = { openingId: '', startLocal: '', endLocal: '', note: '' }; receipt.value = null; detail.value = null
  detailError.value = ''; listError.value = ''; readAfterPostError.value = ''
}
function resetClinicReads() {
  clinicLane.invalidate(); listLane.invalidate(); detailLane.invalidate()
  confirmedClinic.value = null; items.value = []; page.value = { hasMore: false, nextAfterId: null }
  detail.value = null; selectedOpeningId.value = ''; clinicBusy.value = false; listBusy.value = false; detailBusy.value = false
  clinicError.value = ''; listError.value = ''; detailError.value = ''; clinicInspected.value = false; openingInspected.value = false; manualRetypeReady.value = false
  clearPrivateValues()
}
function restoreMarker() {
  try {
    pending.value = loadOrganizationOpeningMarker(storage()); markerBlocked.value = false; markerError.value = ''
    if (pending.value) {
      markerInspected.value = false
      writeState.value = pending.value.state === 'conflict' ? 'conflict' : 'unknown'
      writeMessage.value = '存在待核对操作。不会自动重试；请用原账号、门店和固定ID显式读取后核对。'
      if (pending.value.actorAccountId === String(props.identity.accountId)) {
        clinicId.value = pending.value.clinicId; form.value.openingId = pending.value.openingId
      }
    }
  } catch (error) { markerBlocked.value = true; markerError.value = '待核对标识无法读取或格式无效，原值已保留；创建已暂停。' }
}
function captured() { return { accountId: String(props.identity.accountId), token: sessionToken() ?? '', scope: JSON.stringify([access.value.chain, access.value.clinicIds]), clinicId: clinicId.value, actorGeneration: actorGeneration.value, disposed: disposed.value } }
function isCurrent(c: ReturnType<typeof captured>, lane: ReturnType<typeof createRequestLane>, ticket: number) {
  return organizationOpeningContextMatches(c, { ...captured(), disposed: disposed.value }) && lane.isCurrent(ticket)
}
function guardClinicId() {
  if (!clinicId.value.trim()) throw new Error('请填写门店ID。')
  return normalizeOpeningId(clinicId.value.trim(), '门店ID')
}

async function confirmClinic() {
  let request
  try { const id = guardClinicId(); request = organizationOpeningClinicRequest(access.value, id) }
  catch { clinicError.value = '门店ID无效或超出当前运营授权范围。'; return }
  const c = captured(); const ticket = clinicLane.begin(); clinicBusy.value = true; clinicError.value = ''; clinicInspected.value = false
  try {
    const result = preserveOpeningClinic(await api<unknown>(request.endpoint), request)
    if (!isCurrent(c, clinicLane, ticket)) return
    confirmedClinic.value = result; clinicInspected.value = true; items.value = []; page.value = { hasMore: false, nextAfterId: null }
    await loadOpenings(false)
  } catch {
    if (isCurrent(c, clinicLane, ticket)) clinicError.value = '门店详情读取失败。请确认门店确实存在且属于当前运营范围。'
  } finally { if (clinicLane.finish(ticket)) clinicBusy.value = false }
}

async function loadOpenings(append: boolean) {
  if (!confirmedClinic.value || !clinicInspected.value) return false
  let request
  try { request = organizationOpeningListRequest(access.value, String(confirmedClinic.value.clinicId), { afterId: append ? page.value.nextAfterId : null, limit: 30 }) }
  catch { listError.value = '当前身份不能读取该门店的实际开放。'; return false }
  if (append && !request.afterId) return false
  const c = captured(); const ticket = listLane.begin(); listBusy.value = true; listError.value = ''
  try {
    const result = preserveOrganizationOpeningList(await api<unknown>(request.endpoint), request)
    if (!isCurrent(c, listLane, ticket)) return
    items.value = append ? [...items.value, ...result.items] : result.items
    page.value = { hasMore: result.hasMore, nextAfterId: result.nextAfterId }
    return true
  } catch { if (isCurrent(c, listLane, ticket)) listError.value = '实际开放列表读取失败，请重新读取。'; return false }
  finally { if (listLane.finish(ticket)) listBusy.value = false }
}

async function openDetail(row: OrganizationOpeningSnapshot) {
  let request
  try { request = organizationOpeningDetailRequest(access.value, row.openingId) } catch { detailError.value = '当前身份不能读取该实际开放。'; return }
  selectedOpeningId.value = row.openingId; detail.value = null; detailError.value = ''
  const c = captured(); const ticket = detailLane.begin(); detailBusy.value = true
  try {
    const current = preserveOrganizationOpeningDetail(await api<unknown>(request.endpoint), request)
    if (!isCurrent(c, detailLane, ticket) || selectedOpeningId.value !== row.openingId) return
    if (current.resource.clinicId !== String(confirmedClinic.value?.clinicId)) throw new Error('门店不匹配。')
    detail.value = current.resource
    return true
  } catch { if (isCurrent(c, detailLane, ticket)) detailError.value = '实际开放详情读取失败或与当前门店不一致。'; return false }
  finally { if (detailLane.finish(ticket)) detailBusy.value = false }
}

async function inspectUnknownTarget() {
  const marker = pending.value
  if (!marker || marker.actorAccountId !== String(props.identity.accountId) || marker.clinicId !== clinicId.value) return
  if (!clinicInspected.value || !confirmedClinic.value) { writeMessage.value = '请先显式读取并确认原门店。'; return }
  let openingId: string
  try { openingId = normalizeOpeningId(form.value.openingId) } catch { writeMessage.value = '请输入原固定开放ID。'; return }
  if (openingId !== marker.openingId) { writeMessage.value = '只能读取待核对标识中的原固定ID。'; return }
  const c = captured(); const ticket = detailLane.begin(); detailBusy.value = true; openingInspected.value = false; markerInspected.value = false; manualRetypeReady.value = false
  try {
    const request = organizationOpeningDetailRequest(access.value, openingId)
    const current = preserveOrganizationOpeningDetail(await api<unknown>(request.endpoint), request)
    if (!isCurrent(c, detailLane, ticket)) return
    if (current.resource.clinicId !== marker.clinicId) throw new Error('门店不匹配。')
    detail.value = current.resource; selectedOpeningId.value = openingId; openingInspected.value = true; markerInspected.value = true
    writeMessage.value = '已读取当前开放详情；这不代表原POST成功或失败。只有同一原内容重试可由服务端判定。'
  } catch (error) {
    if (!isCurrent(c, detailLane, ticket)) return
    const status = Number((error as { status?: unknown })?.status)
    if (status === 404) { detail.value = null; selectedOpeningId.value = openingId; openingInspected.value = true; markerInspected.value = true; writeMessage.value = '当前未找到该ID。原POST结果仍未知；可人工重填原内容并沿原ID提交，由服务端判定。' }
    else detailError.value = '当前开放详情读取失败；待核对标识仍保留。'
  } finally { if (detailLane.finish(ticket)) detailBusy.value = false }
}

async function stopOpening() {
  const item = detail.value
  if (!item || !stopReason.value.trim() || stopBusy.value || props.identity.readOnly) return
  const scope = captured()
  stopBusy.value = true
  detailError.value = ''
  try {
    await api(`/org/openings/${item.openingId}/stop`, { method:'POST', body:{
      expectedUpdatedAt:item.updatedAt, reason:stopReason.value.trim()
    } })
    if (detail.value===item && captured().token===scope.token) {
      stopReason.value = ''
      await openDetail(item)
    }
  } catch (e) {
    if (detail.value===item) detailError.value = (e as Error).message
  } finally {
    stopBusy.value = false
  }
}
function prepareManualRetype() {
  if (!manualRetryAllowed.value && pending.value?.state !== 'sending' && markerInspected.value && openingInspected.value && clinicInspected.value && pending.value?.actorAccountId === String(props.identity.accountId) && pending.value.clinicId === clinicId.value && pending.value.openingId === form.value.openingId) {
    clearRetryBody(); form.value = { openingId: pending.value.openingId, startLocal: '', endLocal: '', note: '' }; manualRetypeReady.value = true; writeState.value = 'unknown'
    writeMessage.value = '请重新填写原始开始、结束时间和备注。保留原固定ID；服务端会判定是否为原请求。'
  }
}
function markerFrom(body: OrganizationOpeningBody, state: OrganizationOpeningMarker['state']): OrganizationOpeningMarker {
  return { actorAccountId: String(props.identity.accountId), kind: 'opening', clinicId: body.clinicId, openingId: body.openingId, route: `/org/clinics/${body.clinicId}/openings`, state, scope: 'operational' }
}

async function submitCreate(exact = false) {
  if (writeBusy.value || markerBlocked.value || markerForOtherActor.value) return
  let body: OrganizationOpeningBody
  try {
    if (!confirmedClinic.value || !clinicInspected.value || String(confirmedClinic.value.clinicId) !== clinicId.value) throw new Error('请先显式读取并确认目标门店。')
    if (exact) {
      if (!retryBody || !exactRetryAllowed.value) throw new Error('当前没有可重试的原始请求。')
      body = preserveExactOpeningRetryBody(retryBody)
    } else {
      if (pending.value && !manualRetryAllowed.value) throw new Error('请先按原账号、门店和固定ID完成显式读取与人工重填。')
      if (!confirmedClinic.value.active && openingNeedsActiveClinicCheck({ pendingMarker: pending.value, exactRetry: false })) throw new Error('新建实际开放要求门店当前启用。')
      body = normalizeOrganizationOpeningBody({ ...form.value, clinicId: String(confirmedClinic.value.clinicId) })
    }
  } catch (error) { writeMessage.value = error instanceof Error ? error.message : '开放资料无效。'; writeState.value = 'rejected'; return }
  const marker = markerFrom(body, 'sending')
  try { persistOrganizationOpeningMarker(storage(), marker); pending.value = marker }
  catch { writeMessage.value = '无法安全保存待核对标识；未发送创建请求，原标识已保留。'; writeState.value = 'unknown'; return }
  const c = captured(); const ticket = writeLane.begin(); writeBusy.value = true; writeState.value = 'sending'; writeMessage.value = '正在提交固定开放ID…'; receipt.value = null; readAfterPostError.value = ''
  try {
    let raw: unknown
    try { raw = await api<unknown>(marker.route, { method: 'POST', body: { openingId: body.openingId, startAt: body.startAt, endAt: body.endAt, note: body.note } }) }
    catch (error) {
      if (!isCurrent(c, writeLane, ticket)) return
      const classification = classifyOrganizationOpeningError(error)
      if (classification === 'unknown') {
        const next = markerFrom(body, 'unknown'); try { pending.value = persistOrganizationOpeningMarker(storage(), next) } catch { pending.value = marker }
        retryBody = { ...body }; writeState.value = 'unknown'; writeMessage.value = '请求结果未知。保留原请求内容和固定ID；请显式选择沿原内容重试。'
      } else if (classification === 'conflict') {
        const next = markerFrom(body, 'conflict'); try { pending.value = persistOrganizationOpeningMarker(storage(), next) } catch { pending.value = marker }
        retryBody = null; writeState.value = 'conflict'; writeMessage.value = '服务端返回冲突。保留原固定ID并读取当前状态后核对。'
      } else {
        let cleared = false
        try { clearOrganizationOpeningMarker(storage(), marker); cleared = true } catch { /* keep safe marker if storage changed */ }
        pending.value = cleared ? null : marker; retryBody = null; writeState.value = 'rejected'; writeMessage.value = '服务端拒绝了创建请求；请核对输入和授权后再操作。'
      }
      return
    }
    let saved
    try { saved = preserveOrganizationOpeningReceipt(raw, body, String(props.identity.accountId)) }
    catch {
      if (!isCurrent(c, writeLane, ticket)) return
      const next = markerFrom(body, 'unknown'); try { pending.value = persistOrganizationOpeningMarker(storage(), next) } catch { pending.value = marker }
      retryBody = { ...body }; writeState.value = 'unknown'; writeMessage.value = '服务端响应无法安全核对。原请求结果待核对，固定ID和原内容已保留。'; return
    }
    if (!isCurrent(c, writeLane, ticket)) return
    receipt.value = saved; writeState.value = 'confirmed'; writeMessage.value = saved.replayed ? '服务端已回放同一原请求的创建回执。' : '服务端已确认实际开放创建。'
    try { clearOrganizationOpeningMarker(storage(), marker); pending.value = null } catch { pending.value = markerFrom(body, 'unknown') }
    clearRetryBody(); form.value = { openingId: '', startLocal: '', endLocal: '', note: '' }; manualRetypeReady.value = false
    const detailRead = await openDetail({ ...saved.resource })
    if (!isCurrent(c, writeLane, ticket)) return
    const listRead = await loadOpenings(false)
    if (!isCurrent(c, writeLane, ticket)) return
    const outcome = organizationOpeningPostReadOutcome(true, !!detailRead && !!listRead)
    writeState.value = outcome.writeState
    if (outcome.readFailed) readAfterPostError.value = 'POST回执已确认；单独读取当前详情或列表失败。'
  } finally { if (writeLane.finish(ticket)) writeBusy.value = false }
}

async function cleanupInvalidMarker() {
  if (!cleanupInspected.value || cleanup.value.confirmed !== true) { markerError.value = '请先显式读取填写的门店与开放ID，并勾选已人工核对。'; return }
  try {
    const result = clearInvalidOrganizationOpeningMarker(storage(), { manuallyReconciled: true, confirmed: true })
    if (result.status === 'valid') { pending.value = result.marker; markerBlocked.value = false; markerError.value = '发现新的有效待核对标识，已保留。'; return }
    pending.value = null; markerBlocked.value = false; markerInspected.value = true; markerError.value = ''; cleanup.value.confirmed = false
  } catch { markerError.value = '无法清理待核对标识；原值已保留。' }
}
async function inspectForCleanup() {
  try {
    const actor = normalizeOpeningId(cleanup.value.actorAccountId, '原操作账号ID')
    const clinic = normalizeOpeningId(cleanup.value.clinicId, '门店ID')
    const opening = normalizeOpeningId(cleanup.value.openingId, '开放ID')
    if (actor !== String(props.identity.accountId)) throw new Error('请切回原操作账号。')
    const context = captured()
    const clinicReq = organizationOpeningClinicRequest(access.value, clinic)
    const clinicResult = preserveOpeningClinic(await api<unknown>(clinicReq.endpoint), clinicReq)
    if (!organizationOpeningContextMatches(context, captured())) return
    if (!clinicResult) throw new Error('门店未确认。')
    const req = organizationOpeningDetailRequest(access.value, opening)
    try { preserveOrganizationOpeningDetail(await api<unknown>(req.endpoint), req) } catch (error) { if (Number((error as { status?: unknown })?.status) !== 404) throw error }
    if (!organizationOpeningContextMatches(context, captured())) return
    cleanupInspected.value = true; markerError.value = '已读取填写的当前门店与开放ID；请确认原操作已人工核对后再清理。'
  } catch { cleanupInspected.value = false; markerError.value = '显式核对失败；原标识仍保留。' }
}

watch(clinicId, (next) => { if (confirmedClinic.value && next.trim() !== String(confirmedClinic.value.clinicId)) resetClinicReads() })
function onSessionExpired() { actorGeneration.value += 1; writeLane.invalidate(); writeBusy.value = false; resetClinicReads(); clinicId.value = '' }
watch(() => props.identity, () => {
  actorGeneration.value += 1; writeLane.invalidate(); writeBusy.value = false; resetClinicReads(); clinicId.value = ''; manualRetypeReady.value = false; restoreMarker()
}, { flush: 'sync' })
onMounted(() => { restoreMarker(); window.addEventListener('adc:session-expired', onSessionExpired) })
onUnmounted(() => {
  window.removeEventListener('adc:session-expired', onSessionExpired)
  disposed.value = true; actorGeneration.value += 1; clinicLane.invalidate(); listLane.invalidate(); detailLane.invalidate(); writeLane.invalidate(); writeBusy.value = false
  clearPrivateValues(); confirmedClinic.value = null; items.value = []; detail.value = null
})
</script>

<template>
  <section class="panel opening-workbench" aria-label="门店实际开放">
    <div class="section-heading"><div><p class="eyebrow">门店运营 · 实际开放</p><h2>开放时段</h2></div><span class="muted">仅展示服务端实际记录</span></div>
    <p class="muted">开放记录描述门店的实际开放区间，不代表牙医出诊、牙医可用时间或可预约时段。撤销记录只作历史展示。</p>

    <section v-if="markerBlocked" class="opening-recovery" aria-live="polite">
      <h3>待核对标识已暂停创建</h3><p class="muted">原标识无法安全读取或格式无效。系统不会显示原文、自动清理、发送请求或覆盖标识。请切回原账号，显式读取原门店和开放ID后人工核对。</p>
      <div class="opening-grid"><label>原操作账号ID<input v-model="cleanup.actorAccountId" inputmode="numeric" autocomplete="off"></label><label>原门店ID<input v-model="cleanup.clinicId" inputmode="numeric" autocomplete="off"></label><label>原开放ID<input v-model="cleanup.openingId" inputmode="numeric" autocomplete="off"></label></div>
      <div class="opening-actions"><button class="button button-quiet" :disabled="clinicBusy || detailBusy" @click="inspectForCleanup">显式读取核对</button><label class="opening-check"><input v-model="cleanup.confirmed" type="checkbox">我已人工核对原操作，不再依赖此损坏标识</label><button class="button button-quiet" :disabled="!cleanupInspected || !cleanup.confirmed" @click="cleanupInvalidMarker">确认清理损坏标识</button></div>
      <p v-if="markerError" class="form-error" role="status">{{ markerError }}</p>
    </section>

    <section v-if="pending && !markerBlocked" class="opening-recovery" aria-live="polite">
      <h3>待核对开放 · #{{ pending.openingId }}</h3>
      <p class="muted">门店 #{{ pending.clinicId }} · 原操作账号 #{{ pending.actorAccountId }} · {{ pending.state === 'conflict' ? '冲突待核对' : '结果待核对' }}</p>
      <p v-if="markerForOtherActor" class="form-error">此标识属于其他操作账号。请切回原账号；不会覆盖该标识。</p>
      <p v-else class="muted">原始备注和请求内容未保存在浏览器存储。刷新后需显式读取当前状态，再由人员重新填写原内容；服务端判断是否为相同请求。</p>
      <div class="opening-actions"><button class="button button-quiet" :disabled="markerForOtherActor || clinicBusy || !clinicInspected || detailBusy" @click="inspectUnknownTarget">显式读取当前开放ID</button><button v-if="markerInspected && openingInspected && clinicInspected && !manualRetypeReady && pending.state !== 'sending'" class="button button-quiet" @click="prepareManualRetype">按原ID人工重填</button><button v-if="exactRetryAllowed" class="button" :disabled="writeBusy" @click="submitCreate(true)">沿原ID和原内容重试</button></div>
      <p v-if="writeMessage" class="muted">{{ writeMessage }}</p>
    </section>

    <div class="opening-grid opening-select">
      <label>门店ID<input v-model="clinicId" inputmode="numeric" autocomplete="off" placeholder="输入当前获权门店ID"></label>
      <div class="opening-actions"><button class="button" :disabled="!access.allowed || clinicBusy" @click="confirmClinic">{{ clinicBusy ? '正在读取…' : '显式读取并确认门店' }}</button><button v-if="confirmedClinic" class="button button-quiet" :disabled="listBusy" @click="loadOpenings(false)">重新读取开放</button></div>
    </div>
    <p v-if="clinicError" class="form-error" role="alert">{{ clinicError }}</p>
    <p v-if="confirmedClinic" class="opening-confirmed">已确认：{{ confirmedClinic.name }} · #{{ confirmedClinic.clinicId }} · {{ confirmedClinic.active ? '门店启用' : '门店停用' }}</p>

    <section v-if="confirmedClinic" class="opening-list">
      <div class="section-heading"><h3>实际开放记录</h3><span class="muted">{{ items.length }} 条</span></div>
      <p v-if="listBusy && !items.length" class="muted">正在读取实际开放…</p><p v-else-if="!items.length && !listError" class="muted">该门店当前没有已读取的开放记录。</p>
      <p v-if="listError" class="form-error" role="alert">{{ listError }}</p>
      <div v-if="items.length" class="opening-table-wrap"><table><thead><tr><th>开放ID</th><th>开始（上海）</th><th>结束（上海）</th><th>状态</th><th>变更计数</th><th></th></tr></thead><tbody><tr v-for="row in items" :key="row.openingId"><td>#{{ row.openingId }}</td><td>{{ row.startAt }}</td><td>{{ row.endAt }}</td><td>{{ row.status }}</td><td>{{ row.mutationCount }}</td><td><button class="button button-quiet" @click="openDetail(row)">查看详情</button></td></tr></tbody></table></div>
      <div v-if="page.hasMore" class="opening-actions"><button class="button button-quiet" :disabled="listBusy" @click="loadOpenings(true)">继续读取</button></div>
    </section>

    <section v-if="confirmedClinic && (selectedOpeningId || detailBusy || detailError)" class="opening-detail">
      <div class="section-heading"><h3>开放详情 · #{{ selectedOpeningId }}</h3><button class="button button-quiet" @click="selectedOpeningId = ''; detail = null; detailLane.invalidate()">关闭详情</button></div>
      <p v-if="detailBusy" class="muted">正在读取当前详情…</p><p v-if="detailError" class="form-error" role="alert">{{ detailError }}</p>
      <p v-if="detail?.status === '撤销'" class="muted">此开放已撤销，仅作历史记录展示，不能作为当前有效开放范围。</p>
      <dl v-if="detail" class="opening-details"><dt>所属门店</dt><dd>#{{ detail.clinicId }}</dd><dt>时间区间</dt><dd>{{ detail.startAt }} – {{ detail.endAt }}</dd><dt>状态</dt><dd>{{ detail.status }}</dd><dt>备注</dt><dd>{{ detail.note || '—' }}</dd><dt>更新账号</dt><dd>#{{ detail.updatedBy }}</dd><dt>更新时间</dt><dd>{{ detail.updatedAt }}</dd><dt>变更计数</dt><dd>{{ detail.mutationCount }}</dd></dl>
      <form v-if="detail?.status==='有效' && !identity.readOnly" :key="detail.openingId" class="opening-grid" @submit.prevent="stopOpening">
        <label>撤销原因<input v-model="stopReason" required maxlength="500" :disabled="stopBusy"></label>
        <button data-readonly-write type="submit" class="secondary" :disabled="stopBusy">撤销开放时段</button>
      </form>
    </section>

    <section v-if="confirmedClinic" class="opening-create">
      <h3>新建实际开放</h3><p class="muted">固定开放ID；时间按上海本地输入保存为 +08:00 整秒。允许跨午夜，最长24小时；最终重叠校验由服务端执行。</p>
      <form @submit.prevent="submitCreate(false)"><fieldset :disabled="createLocked || writeBusy || !!pending && !manualRetypeReady">
        <div class="opening-grid"><label>固定开放ID<input v-model="form.openingId" inputmode="numeric" autocomplete="off" required maxlength="19" :readonly="manualRetryAllowed || exactRetryAllowed"></label><label>开始时间（上海）<input v-model="form.startLocal" type="datetime-local" step="1" required :readonly="exactRetryAllowed"></label><label>结束时间（上海）<input v-model="form.endLocal" type="datetime-local" step="1" required :readonly="exactRetryAllowed"></label><label class="opening-note">备注<textarea v-model="form.note" rows="3" maxlength="8000" :readonly="exactRetryAllowed"></textarea></label></div>
        <p class="opening-private">备注和原始请求只保存在当前页面内存与本次POST请求中。待核对标识只保存操作账号、门店、开放ID、路由和状态。</p>
        <div class="opening-actions"><button data-readonly-write class="button" type="submit" :disabled="createLocked || writeBusy || !!pending && !manualRetypeReady || exactRetryAllowed">{{ writeBusy ? '正在提交…' : '提交实际开放' }}</button><button v-if="exactRetryAllowed" class="button button-quiet" type="button" :disabled="writeBusy" @click="submitCreate(true)">沿原ID和原内容重试</button></div>
      </fieldset></form>
      <p v-if="writeMessage" class="muted" role="status">{{ writeMessage }}</p>
      <section v-if="receipt" class="opening-receipt" aria-live="polite"><h4>POST创建回执</h4><p>{{ receipt.replayed ? '服务端回放了相同原请求。' : '服务端确认创建。' }} · 开放 #{{ receipt.resource.openingId }} · 门店 #{{ receipt.resource.clinicId }} · {{ receipt.resource.startAt }} – {{ receipt.resource.endAt }}</p><p v-if="readAfterPostError" class="form-error">{{ readAfterPostError }}</p><p class="muted">上方详情和列表来自创建后的单独GET读取，不作为POST回执。</p></section>
    </section>
  </section>
</template>

<style scoped>
.opening-workbench{display:grid;gap:16px;padding:20px;min-width:0}.opening-workbench h2,.opening-workbench h3,.opening-workbench h4{margin:0}.opening-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.opening-grid label{display:grid;gap:6px;font-size:13px;font-weight:650}.opening-grid input,.opening-grid textarea{width:100%;border:1px solid var(--line);border-radius:8px;padding:10px;font:inherit;color:var(--ink);background:#fff}.opening-actions{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.opening-select{align-items:end}.opening-confirmed{margin:0;color:#216454;font-weight:650}.opening-list,.opening-detail,.opening-create,.opening-recovery,.opening-receipt{display:grid;gap:12px;padding-top:14px;border-top:1px solid var(--line)}.opening-table-wrap{overflow-x:auto}.opening-table-wrap table{width:100%;border-collapse:collapse;min-width:720px}.opening-table-wrap th,.opening-table-wrap td{text-align:left;padding:10px;border-bottom:1px solid var(--line);white-space:nowrap}.opening-details{display:grid;grid-template-columns:minmax(110px,180px) 1fr;gap:8px;margin:0}.opening-details dt{color:var(--muted)}.opening-details dd{margin:0;overflow-wrap:anywhere}.opening-note{grid-column:1/-1}.opening-private{color:var(--muted);font-size:12px}.opening-check{display:flex;align-items:center;gap:6px}.opening-workbench fieldset{border:0;padding:0;margin:0;min-width:0}.opening-receipt{padding:12px;border:1px solid var(--line);border-radius:10px}.opening-workbench .section-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}@media(max-width:680px){.opening-grid{grid-template-columns:1fr}.opening-note{grid-column:auto}.opening-details{grid-template-columns:1fr}.opening-details dt{font-weight:700}}
</style>
