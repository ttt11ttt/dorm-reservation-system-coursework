<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { api, formatShanghai, sessionToken } from '../api'
import type { Identity } from '../api'
import { preserveOrganizationClinicDetail } from '../organization-readonly-core.js'
import { createRequestLane } from '../staff-admin-ui-coordination.js'
import { createOrganizationWriteState } from '../organization-create-core.js'
import {
  classifyOrganizationScheduleError, clearOrganizationScheduleMarker,
  loadOrganizationScheduleMarker, normalizeOrganizationScheduleBody, normalizeScheduleId,
  organizationScheduleAccess, organizationScheduleClinicRequest, organizationScheduleContextMatches,
  organizationScheduleDetailRequest, organizationScheduleListRequest, organizationScheduleOpeningCovers,
  organizationScheduleOpeningRequest, organizationSchedulePostReadOutcome, persistOrganizationScheduleMarker,
  preserveExactOrganizationScheduleRetryBody, preserveOrganizationScheduleDetail, preserveOrganizationScheduleList,
  preserveOrganizationScheduleReceipt, preserveScheduleOpening, scheduleManualRetryAllowed,
} from '../organization-schedule-core.js'
import type { OrganizationScheduleBody, OrganizationScheduleMarker, OrganizationScheduleSnapshot } from '../organization-schedule-core.js'
import type { OrganizationOpeningSnapshot } from '../organization-opening-core.js'

const props = defineProps<{ identity: Identity; selectedClinicId?: string }>()
const access = computed(() => organizationScheduleAccess(props.identity))
const scope = computed(() => access.value.chain ? 'chain' : 'clinic')
const clinicId = ref('')
const confirmedClinic = ref<Record<string, unknown> | null>(null)
const clinicInspected = ref(false)
const clinicBusy = ref(false)
const clinicError = ref('')
const items = ref<OrganizationScheduleSnapshot[]>([])
const page = ref({ hasMore: false, nextAfterId: null as string | null })
const listBusy = ref(false)
const listError = ref('')
const selectedId = ref('')
const detail = ref<OrganizationScheduleSnapshot | null>(null)
const detailBusy = ref(false)
const detailError = ref('')
const opening = ref<OrganizationOpeningSnapshot | null>(null)
const openingInspected = ref(false)
const openingBusy = ref(false)
const openingError = ref('')
const form = ref({ scheduleId: '', dentistId: '', openingId: '', startLocal: '', endLocal: '' })
const pending = ref<OrganizationScheduleMarker | null>(null)
const markerBlocked = ref(false)
const markerMessage = ref('')
const scheduleInspected = ref(false)
const manualRetypeReady = ref(false)
const writeBusy = ref(false)
const writeState = ref<'idle' | 'sending' | 'unknown' | 'conflict' | 'rejected' | 'confirmed'>('idle')
const writeMessage = ref('')
const receipt = ref<{ replayed: boolean; resource: OrganizationScheduleSnapshot; snapshot: OrganizationScheduleSnapshot } | null>(null)
const getAfterPostError = ref('')
const actorGeneration = ref(0)
const disposed = ref(false)
let retryBody: OrganizationScheduleBody | null = null
const clinicLane = createRequestLane()
const listLane = createRequestLane()
const detailLane = createRequestLane()
const openingLane = createRequestLane()
const writeLane = createOrganizationWriteState()
const otherActor = computed(() => !!pending.value && pending.value.actorAccountId !== String(props.identity.accountId))
const exactRetry = computed(() => !!retryBody && !!pending.value && pending.value.state === 'unknown' && pending.value.actorAccountId === String(props.identity.accountId) && pending.value.clinicId === clinicId.value && pending.value.scheduleId === retryBody.scheduleId && pending.value.dentistId === retryBody.dentistId && pending.value.openingId === retryBody.openingId && pending.value.scope === scope.value)
const manualRetry = computed(() => scheduleManualRetryAllowed({ marker: pending.value, actorAccountId: String(props.identity.accountId), clinicId: clinicId.value, scheduleId: form.value.scheduleId, dentistId: form.value.dentistId, openingId: form.value.openingId, scope: scope.value, clinicInspected: clinicInspected.value && manualRetypeReady.value, scheduleInspected: scheduleInspected.value, openingInspected: openingInspected.value }))
const createLocked = computed(() => markerBlocked.value || otherActor.value || !!pending.value && !manualRetry.value && !exactRetry.value && !manualRetypeReady.value)

function storage() { return window.sessionStorage }
function wipeRetryBody() { if (retryBody) { retryBody.startAt = ''; retryBody.endAt = '' }; retryBody = null }
function clearPrivate() { wipeRetryBody(); form.value = { scheduleId: '', dentistId: '', openingId: '', startLocal: '', endLocal: '' }; detail.value = null; opening.value = null; receipt.value = null; getAfterPostError.value = '' }
function resetReads() {
  clinicLane.invalidate(); listLane.invalidate(); detailLane.invalidate(); openingLane.invalidate()
  confirmedClinic.value = null; clinicInspected.value = false; openingInspected.value = false; scheduleInspected.value = false
  items.value = []; page.value = { hasMore: false, nextAfterId: null }; selectedId.value = ''; detail.value = null; opening.value = null
  clinicBusy.value = false; listBusy.value = false; detailBusy.value = false; openingBusy.value = false
  clinicError.value = ''; listError.value = ''; detailError.value = ''; openingError.value = ''; manualRetypeReady.value = false
  clearPrivate()
}
function restoreMarker() {
  try {
    pending.value = loadOrganizationScheduleMarker(storage()); markerBlocked.value = false; markerMessage.value = ''
    if (pending.value) {
      writeState.value = pending.value.state === 'conflict' ? 'conflict' : 'unknown'
      writeMessage.value = '有待核对出诊。不会自动重试；请显式读取门店、出诊ID和原开放ID。'
      if (pending.value.actorAccountId === String(props.identity.accountId)) {
        clinicId.value = pending.value.clinicId
        form.value.scheduleId = pending.value.scheduleId; form.value.dentistId = pending.value.dentistId; form.value.openingId = pending.value.openingId
      }
    }
  } catch { pending.value = null; markerBlocked.value = true; markerMessage.value = '待核对标识无法安全读取或格式无效，原值已保留；出诊创建已暂停。' }
}
function captured() { return { accountId: String(props.identity.accountId), token: sessionToken() ?? '', scope: JSON.stringify([scope.value, access.value.clinicIds]), clinicId: clinicId.value, actorGeneration: actorGeneration.value, disposed: disposed.value } }
function current(c: ReturnType<typeof captured>, lane: ReturnType<typeof createRequestLane>, ticket: number) { return organizationScheduleContextMatches(c, captured()) && lane.isCurrent(ticket) }
function targetClinicId() { if (!clinicId.value.trim()) throw new Error('请填写门店ID。'); return normalizeScheduleId(clinicId.value.trim(), '门店ID') }

async function confirmClinic() {
  let request
  try { request = organizationScheduleClinicRequest(access.value, targetClinicId()) } catch { clinicError.value = '门店ID无效或超出当前运营授权范围。'; return }
  const c = captured(); const ticket = clinicLane.begin(); clinicBusy.value = true; clinicError.value = ''; clinicInspected.value = false
  try {
    const result = await api<unknown>(request.endpoint)
    if (!current(c, clinicLane, ticket)) return
    const clinic = preserveOrganizationClinicDetail(result, request).resource
    confirmedClinic.value = clinic; clinicInspected.value = true; items.value = []; page.value = { hasMore: false, nextAfterId: null }
    await loadSchedules(false)
  } catch { if (current(c, clinicLane, ticket)) clinicError.value = '门店详情读取失败，请确认门店存在且属于当前运营授权范围。' }
  finally { if (clinicLane.finish(ticket)) clinicBusy.value = false }
}
async function loadSchedules(append: boolean) {
  if (!confirmedClinic.value || !clinicInspected.value) return false
  let request
  try { request = organizationScheduleListRequest(access.value, String(confirmedClinic.value.clinicId), { afterId: append ? page.value.nextAfterId : null, limit: 30 }) } catch { listError.value = '当前身份不能读取该门店出诊。'; return false }
  if (append && !request.afterId) return false
  const c = captured(); const ticket = listLane.begin(); listBusy.value = true; listError.value = ''
  try {
    const result = preserveOrganizationScheduleList(await api<unknown>(request.endpoint), request)
    if (!current(c, listLane, ticket)) return false
    items.value = append ? [...items.value, ...result.items] : result.items; page.value = { hasMore: result.hasMore, nextAfterId: result.nextAfterId }; return true
  } catch { if (current(c, listLane, ticket)) listError.value = '出诊列表读取失败，请重新读取。'; return false }
  finally { if (listLane.finish(ticket)) listBusy.value = false }
}
async function readSchedule(id: string, allowMissing = false) {
  let request
  try { request = organizationScheduleDetailRequest(access.value, id) } catch { detailError.value = '当前身份不能读取出诊详情。'; return false }
  const c = captured(); const ticket = detailLane.begin(); detailBusy.value = true; scheduleInspected.value = false; detailError.value = ''
  try {
    const result = preserveOrganizationScheduleDetail(await api<unknown>(request.endpoint), request)
    if (!current(c, detailLane, ticket)) return false
    if (result.resource.clinicId !== String(confirmedClinic.value?.clinicId)) throw new Error('门店不匹配。')
    selectedId.value = id; detail.value = result.resource; scheduleInspected.value = true; return true
  } catch (error) {
    if (!current(c, detailLane, ticket)) return false
    if (allowMissing && Number((error as { status?: unknown })?.status) === 404) { selectedId.value = id; detail.value = null; scheduleInspected.value = true; return true }
    detailError.value = '出诊详情读取失败或与当前门店不一致。'; return false
  } finally { if (detailLane.finish(ticket)) detailBusy.value = false }
}
async function openDetail(row: OrganizationScheduleSnapshot) { await readSchedule(row.scheduleId); }
async function readOpening(id = form.value.openingId) {
  let request
  try { request = organizationScheduleOpeningRequest(access.value, normalizeScheduleId(id, '开放ID')) } catch { openingError.value = '开放ID无效或当前身份没有读取权限。'; return false }
  const c = captured(); const ticket = openingLane.begin(); openingBusy.value = true; openingInspected.value = false; openingError.value = ''
  try {
    const result = preserveScheduleOpening(await api<unknown>(request.endpoint), request)
    if (!current(c, openingLane, ticket)) return false
    if (result.clinicId !== String(confirmedClinic.value?.clinicId)) throw new Error('开放所属门店与当前门店不同。')
    opening.value = result; openingInspected.value = true; return true
  } catch (error) {
    if (!current(c, openingLane, ticket)) return false
    if (pending.value && Number((error as { status?: unknown })?.status) === 404) { opening.value = null; openingInspected.value = true; openingError.value = '当前开放详情未找到；服务端仍负责原请求回放判定。'; return true }
    openingError.value = '开放详情读取失败或不属于当前门店。'; return false
  } finally { if (openingLane.finish(ticket)) openingBusy.value = false }
}
async function inspectPending() {
  const marker = pending.value
  if (!marker || marker.actorAccountId !== String(props.identity.accountId) || marker.clinicId !== clinicId.value || !clinicInspected.value || !confirmedClinic.value) { writeMessage.value = '请先切回原操作账号并显式确认原门店。'; return }
  if (form.value.scheduleId !== marker.scheduleId || form.value.dentistId !== marker.dentistId || form.value.openingId !== marker.openingId) { writeMessage.value = '请填写待核对标识中的原出诊、牙医和开放ID。'; return }
  await readSchedule(marker.scheduleId, true)
  if (!clinicInspected.value || !confirmedClinic.value) return
  const openResult = await readOpening(marker.openingId)
  if (!openResult || !scheduleInspected.value || !openingInspected.value) return
  writeMessage.value = '已分别读取当前出诊与原开放。GET结果不证明原POST成功；请沿原ID重填时间，由服务端判定是否为原请求。'
}
function prepareManualRetype() {
  const marker = pending.value
  if (!marker || otherActor.value || !scheduleInspected.value || !openingInspected.value || !clinicInspected.value) return
  form.value = { scheduleId: '', dentistId: '', openingId: '', startLocal: '', endLocal: '' }
  manualRetypeReady.value = true; writeState.value = 'unknown'; writeMessage.value = '请重新填写原始时间并保留原ID；当前门店或开放状态不在客户端替服务端判定。'
}
function makeMarker(body: OrganizationScheduleBody, state: OrganizationScheduleMarker['state']): OrganizationScheduleMarker {
  return { actorAccountId: String(props.identity.accountId), kind: 'schedule', clinicId: body.clinicId, scheduleId: body.scheduleId, dentistId: body.dentistId, openingId: body.openingId, route: `/org/clinics/${body.clinicId}/schedules`, scope: scope.value, state }
}
async function submit(exact = false) {
  if (writeBusy.value || createLocked.value || otherActor.value) return
  let body: OrganizationScheduleBody
  try {
    if (!confirmedClinic.value || !clinicInspected.value || String(confirmedClinic.value.clinicId) !== clinicId.value) throw new Error('请先显式读取并确认目标门店。')
    if (exact) {
      if (!retryBody || !exactRetry.value) throw new Error('没有可用的原始出诊请求。')
      body = preserveExactOrganizationScheduleRetryBody(retryBody)
    } else {
      if (pending.value && !manualRetry.value) throw new Error('请先显式读取原门店、原出诊和原开放，并按原ID重填。')
      if (!pending.value && !confirmedClinic.value.active) throw new Error('新建出诊要求门店当前启用。')
      body = normalizeOrganizationScheduleBody({ ...form.value, clinicId: String(confirmedClinic.value.clinicId) }, { allowPast: !!pending.value && manualRetry.value })
      if (!pending.value && (!openingInspected.value || !opening.value || opening.value.openingId !== body.openingId || !organizationScheduleOpeningCovers(opening.value, body))) throw new Error('请先显式读取同店有效开放，并确认其完整覆盖出诊区间。')
    }
  } catch (error) { writeMessage.value = error instanceof Error ? error.message : '出诊资料无效。'; writeState.value = 'rejected'; return }
  const marker = makeMarker(body, 'sending')
  try { pending.value = persistOrganizationScheduleMarker(storage(), marker) }
  catch { writeMessage.value = '无法安全写入待核对标识，未发送POST；原标识已保留。'; writeState.value = 'unknown'; return }
  const c = captured(); const ticket = writeLane.begin(); writeBusy.value = true; writeState.value = 'sending'; writeMessage.value = '正在提交固定出诊ID…'; receipt.value = null; getAfterPostError.value = ''
  try {
    let raw: unknown
    try { raw = await api<unknown>(marker.route, { method: 'POST', body: { scheduleId: body.scheduleId, dentistId: body.dentistId, openingId: body.openingId, startAt: body.startAt, endAt: body.endAt } }) }
    catch (error) {
      if (!current(c, writeLane, ticket)) return
      const classification = classifyOrganizationScheduleError(error)
      if (classification === 'unknown') {
        const next = makeMarker(body, 'unknown'); try { pending.value = persistOrganizationScheduleMarker(storage(), next) } catch { pending.value = marker }
        retryBody = { ...body }; writeState.value = 'unknown'; writeMessage.value = '创建结果未知。原请求只留在本页内存，可显式沿原ID与原内容重试。'
      } else if (classification === 'conflict') {
        const next = makeMarker(body, 'conflict'); try { pending.value = persistOrganizationScheduleMarker(storage(), next) } catch { pending.value = marker }
        wipeRetryBody(); writeState.value = 'conflict'; writeMessage.value = '服务端返回冲突。固定原ID已保留，请读取当前记录后核对。'
      } else {
        let cleared = false; try { clearOrganizationScheduleMarker(storage(), marker); cleared = true } catch { /* retain marker */ }
        pending.value = cleared ? null : marker; wipeRetryBody(); writeState.value = 'rejected'; writeMessage.value = '服务端拒绝了出诊创建；请检查ID、时间、门店和开放范围。'
      }
      return
    }
    let saved
    try { saved = preserveOrganizationScheduleReceipt(raw, body) }
    catch {
      if (!current(c, writeLane, ticket)) return
      const next = makeMarker(body, 'unknown'); try { pending.value = persistOrganizationScheduleMarker(storage(), next) } catch { pending.value = marker }
      retryBody = { ...body }; writeState.value = 'unknown'; writeMessage.value = 'POST响应无法安全核对；原请求结果待核对并保留固定ID。'; return
    }
    if (!current(c, writeLane, ticket)) return
    receipt.value = saved; writeState.value = 'confirmed'; writeMessage.value = saved.replayed ? '服务端回放了相同原请求的固定回执。' : '服务端确认出诊已创建。'
    try { clearOrganizationScheduleMarker(storage(), marker); pending.value = null } catch { pending.value = makeMarker(body, 'unknown') }
    wipeRetryBody(); manualRetypeReady.value = false
    const readOk = await readSchedule(body.scheduleId)
    if (!current(c, writeLane, ticket)) return
    const listOk = await loadSchedules(false)
    if (!current(c, writeLane, ticket)) return
    const outcome = organizationSchedulePostReadOutcome(true, !!readOk && !!listOk)
    writeState.value = outcome.writeState
    if (outcome.readFailed) getAfterPostError.value = 'POST安全回执已确认；单独GET当前详情或列表失败。'
  } finally { if (writeLane.finish(ticket)) writeBusy.value = false }
}

watch(clinicId, (value) => { if (confirmedClinic.value && value.trim() !== String(confirmedClinic.value.clinicId)) resetReads() })
watch(() => props.identity, () => { actorGeneration.value += 1; writeLane.invalidate(); writeBusy.value = false; resetReads(); clinicId.value = ''; restoreMarker() }, { flush: 'sync' })
function onSessionExpired() { actorGeneration.value += 1; writeLane.invalidate(); writeBusy.value = false; resetReads(); clinicId.value = '' }
watch(() => props.selectedClinicId, async value => { if(value && value!==clinicId.value){ clinicId.value=value; await confirmClinic() } })
onMounted(() => { restoreMarker(); if(props.selectedClinicId && !pending.value){ clinicId.value=props.selectedClinicId; void confirmClinic() }; window.addEventListener('adc:session-expired', onSessionExpired) })
onUnmounted(() => { window.removeEventListener('adc:session-expired', onSessionExpired); disposed.value = true; actorGeneration.value += 1; clinicLane.invalidate(); listLane.invalidate(); detailLane.invalidate(); openingLane.invalidate(); writeLane.invalidate(); clearPrivate(); confirmedClinic.value = null; items.value = [] })
</script>

<template>
  <section class="panel opening-workbench schedule-workbench" aria-label="牙医既定出诊">
    <div class="section-heading"><div><p class="eyebrow">门店运营 · 既定出诊</p><h2>牙医出诊安排</h2></div><span class="muted">仅记录已确定的出诊</span></div>
    <p class="muted">出诊不表示牙医已获得登录/岗位权限、资质授权或可预约资格。门店人员输入牙医ID，不读取牙医私密主档。</p>

    <section v-if="markerBlocked" class="opening-recovery" role="alert"><h3>待核对标识读取失败</h3><p class="muted">创建已暂停。原标识不会显示、自动删除或覆盖；请切回原操作账号并按原ID人工核对后再继续。</p><p class="form-error">{{ markerMessage }}</p></section>
    <section v-if="pending && !markerBlocked" class="opening-recovery" aria-live="polite">
      <h3>待核对出诊 · #{{ pending.scheduleId }}</h3><p class="muted">门店 #{{ pending.clinicId }} · 牙医 #{{ pending.dentistId }} · 开放 #{{ pending.openingId }} · {{ pending.state === 'conflict' ? '冲突待核对' : '请求结果待核对' }}</p>
      <p v-if="otherActor" class="form-error">此标识属于其他账号。请切回原账号；不会覆盖该标识。</p>
      <p v-else class="muted">不保存原始时间或请求正文。刷新后需先GET核对原门店、出诊和开放，再由人员沿原ID重填交服务端判定。</p>
      <div class="opening-actions"><button class="button button-quiet" :disabled="otherActor || clinicBusy || !clinicInspected || detailBusy || openingBusy" @click="inspectPending">显式读取原出诊与开放</button><button v-if="scheduleInspected && openingInspected && clinicInspected && !manualRetypeReady && pending.state !== 'sending' && !otherActor" class="button button-quiet" @click="prepareManualRetype">按原ID人工重填</button><button v-if="exactRetry" class="button" :disabled="writeBusy" @click="submit(true)">沿原ID和原请求重试</button></div>
    </section>

    <div class="opening-grid opening-select"><label>门店ID<input v-model="clinicId" inputmode="numeric" autocomplete="off" placeholder="输入当前获权门店ID"></label><div class="opening-actions"><button class="button" :disabled="!access.allowed || clinicBusy" @click="confirmClinic">{{ clinicBusy ? '正在读取…' : '显式读取并确认门店' }}</button><button v-if="confirmedClinic" class="button button-quiet" :disabled="listBusy" @click="loadSchedules(false)">重新读取出诊</button></div></div>
    <p v-if="clinicError" class="form-error" role="alert">{{ clinicError }}</p>
    <p v-if="confirmedClinic" class="opening-confirmed">已确认：{{ confirmedClinic.name }} · #{{ confirmedClinic.clinicId }} · {{ confirmedClinic.active ? '门店启用' : '门店停用' }}</p>

    <section v-if="confirmedClinic" class="opening-list"><div class="section-heading"><h3>出诊记录</h3><span class="muted">{{ items.length }} 条</span></div><p v-if="listBusy && !items.length" class="muted">正在读取出诊…</p><p v-else-if="!items.length && !listError" class="muted">该门店当前没有已读取的出诊记录。</p><p v-if="listError" class="form-error" role="alert">{{ listError }}</p>
      <div v-if="items.length" class="opening-table-wrap"><table><thead><tr><th>出诊ID</th><th>牙医ID</th><th>开放ID</th><th>开始（上海）</th><th>结束（上海）</th><th>状态</th><th>计数</th><th></th></tr></thead><tbody><tr v-for="row in items" :key="row.scheduleId"><td><span class="mono">#{{ row.scheduleId }}</span></td><td><span class="mono">#{{ row.dentistId }}</span></td><td><span class="mono">#{{ row.openingId }}</span></td><td>{{ formatShanghai(row.startAt) }}</td><td>{{ formatShanghai(row.endAt) }}</td><td><span class="badge">{{ row.status }}</span></td><td>{{ row.mutationCount }}</td><td><button class="button button-quiet" @click="openDetail(row)">查看详情</button></td></tr></tbody></table></div>
      <div v-if="page.hasMore" class="opening-actions"><button class="button button-quiet" :disabled="listBusy" @click="loadSchedules(true)">继续读取</button></div>
    </section>

    <section v-if="selectedId || detailBusy || detailError" class="opening-detail"><div class="section-heading"><h3>出诊详情 · #{{ selectedId }}</h3><button class="button button-quiet" @click="selectedId = ''; detail = null; detailLane.invalidate()">关闭详情</button></div><p v-if="detailBusy" class="muted">正在读取详情…</p><p v-if="detailError" class="form-error">{{ detailError }}</p><p v-if="detail?.status === '撤销'" class="muted">此记录已撤销，仅作历史展示。</p><dl v-if="detail" class="opening-details"><dt>门店</dt><dd>#{{ detail.clinicId }}</dd><dt>牙医</dt><dd>#{{ detail.dentistId }}</dd><dt>关联开放</dt><dd>#{{ detail.openingId }}</dd><dt>时间区间</dt><dd>{{ detail.startAt }} – {{ detail.endAt }}</dd><dt>状态</dt><dd>{{ detail.status }}</dd><dt>rowVersion</dt><dd>{{ detail.rowVersion }}</dd><dt>变更计数</dt><dd>{{ detail.mutationCount }}</dd></dl></section>

    <section v-if="confirmedClinic" class="opening-create"><h3>新建出诊</h3><p class="muted">固定出诊、牙医和开放ID。请显式读取同店开放并确认完整覆盖；最终未来时间、资源状态及冲突由服务端裁定。</p>
      <form @submit.prevent="submit(false)"><fieldset :disabled="createLocked || writeBusy">
        <div class="opening-grid"><label>固定出诊ID<input v-model="form.scheduleId" inputmode="numeric" autocomplete="off" required maxlength="19" :readonly="exactRetry"></label><label>牙医ID<input v-model="form.dentistId" inputmode="numeric" autocomplete="off" required maxlength="19" :readonly="exactRetry"></label><label>开放ID<input v-model="form.openingId" inputmode="numeric" autocomplete="off" required maxlength="19" :readonly="exactRetry"></label><label>开始时间（上海）<input v-model="form.startLocal" type="datetime-local" step="1" required :readonly="exactRetry"></label><label>结束时间（上海）<input v-model="form.endLocal" type="datetime-local" step="1" required :readonly="exactRetry"></label></div>
        <div class="opening-actions"><button class="button button-quiet" type="button" :disabled="!form.openingId || openingBusy || !clinicInspected || !!pending && !manualRetypeReady" @click="readOpening()">GET核对同店开放</button><button class="button" type="submit" :disabled="writeBusy || !!pending && !manualRetypeReady || !!pending && manualRetypeReady && !manualRetry || exactRetry">{{ writeBusy ? '正在提交…' : '提交出诊创建' }}</button><button v-if="exactRetry" class="button button-quiet" type="button" :disabled="writeBusy" @click="submit(true)">沿原ID和原请求重试</button></div>
      </fieldset></form>
      <p v-if="openingError" class="form-error" role="status">{{ openingError }}</p><div v-if="opening" class="opening-confirmed">已GET核对开放 #{{ opening.openingId }} · 门店 #{{ opening.clinicId }} · {{ opening.status }} · {{ opening.startAt }} – {{ opening.endAt }}<span v-if="opening.status !== '有效'">；当前开放已撤销</span></div>
      <p class="opening-private">牙医ID由操作人员提供；本页不查询牙医全局目录或个人主档。原始时间与请求仅留在页面内存，不进入sessionStorage。</p><p v-if="writeMessage" class="muted" role="status">{{ writeMessage }}</p>
      <section v-if="receipt" class="opening-receipt" aria-live="polite"><h4>POST创建回执</h4><p>{{ receipt.replayed ? '服务端回放了同一原请求。' : '服务端确认创建。' }} · 出诊 #{{ receipt.resource.scheduleId }} · 牙医 #{{ receipt.resource.dentistId }} · 开放 #{{ receipt.resource.openingId }} · {{ receipt.resource.startAt }} – {{ receipt.resource.endAt }}</p><p v-if="getAfterPostError" class="form-error">{{ getAfterPostError }}</p><p class="muted">当前详情/列表来自POST之后单独GET读取，与创建回执分开呈现。</p></section>
    </section>
  </section>
</template>

<style scoped>
.schedule-workbench{display:grid;gap:16px;padding:20px;min-width:0}.schedule-workbench h2,.schedule-workbench h3,.schedule-workbench h4{margin:0}.opening-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.opening-grid label{display:grid;gap:6px;font-size:13px;font-weight:650}.opening-actions{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.opening-select{align-items:end}.opening-confirmed{margin:0;color:#216454;font-weight:650;line-height:1.55}.opening-list,.opening-detail,.opening-create,.opening-recovery,.opening-receipt{display:grid;gap:12px;padding-top:14px;border-top:1px solid var(--line)}.opening-table-wrap{overflow-x:auto}.opening-table-wrap table{width:100%;border-collapse:collapse;min-width:980px}.opening-table-wrap th,.opening-table-wrap td{text-align:left;padding:10px;border-bottom:1px solid var(--line);white-space:nowrap}.opening-details{display:grid;grid-template-columns:minmax(110px,180px) 1fr;gap:8px;margin:0}.opening-details dt{color:var(--muted)}.opening-details dd{margin:0;overflow-wrap:anywhere}.opening-private{color:var(--muted);font-size:12px}.schedule-workbench fieldset{border:0;padding:0;margin:0;min-width:0}.opening-receipt{padding:12px;border:1px solid var(--line);border-radius:10px}.schedule-workbench .section-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}@media(max-width:680px){.opening-grid{grid-template-columns:1fr}.opening-details{grid-template-columns:1fr}.opening-details dt{font-weight:700}}
</style>


