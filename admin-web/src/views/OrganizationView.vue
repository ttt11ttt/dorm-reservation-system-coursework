<script setup lang="ts">
import OrganizationMaintenance from '../components/OrganizationMaintenance.vue'
import DentistPublicProfileEditor from '../components/DentistPublicProfileEditor.vue'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { api, sessionToken } from '../api'
import type { Identity } from '../api'
import { createRequestLane } from '../staff-admin-ui-coordination.js'
import OrganizationOpeningView from './OrganizationOpeningView.vue'
import OrganizationScheduleView from './OrganizationScheduleView.vue'
import {
  clearOrganizationPrivateDetail, organizationClinicDetailRequest, organizationClinicListRequest, organizationDentistDetailRequest,
  organizationDentistListRequest, organizationReadAccess, preserveOrganizationClinicDetail,
  preserveOrganizationClinicList, preserveOrganizationDentistDetail, preserveOrganizationDentistList,
} from '../organization-readonly-core.js'
import type { OrganizationClinicDetail, OrganizationClinicSummary, OrganizationDentistDetail, OrganizationDentistSummary } from '../organization-readonly-core.js'
import {
  clearInvalidOrganizationCreateMarker,
  createOrganizationWriteState,
  loadOrganizationCreateMarker, normalizeOrganizationCreateBody, normalizeOrganizationId, organizationCreateAccess,
  organizationCreateRetryAllowed, organizationCreateRoute, organizationWriteContextMatches, persistOrganizationCreateMarker,
  runOrganizationCreateAttempt,
} from '../organization-create-core.js'
import type { OrganizationCreateBody, OrganizationCreateKind, OrganizationCreateMarker, OrganizationCreateReceipt } from '../organization-create-core.js'

const props = withDefaults(defineProps<{ identity: Identity; initialSection?: 'clinics' | 'dentists' | 'openings' | 'schedules' }>(), { initialSection: 'clinics' })
const profileDentist = ref<OrganizationDentistSummary | null>(null)
const access = computed(() => organizationReadAccess(props.identity))
const createAccess = computed(() => organizationCreateAccess(props.identity))
const canInspectPending = computed(() => {
  const marker = pendingCreate.value
  if (!marker || !access.value.allowed) return false
  if (marker.kind === 'dentist') return access.value.chain
  return access.value.chain || access.value.clinicIds.includes(marker.resourceId)
})
const clinicIds = computed(() => access.value.clinicIds)
const scopeKey = computed(() => JSON.stringify([props.identity.accountId, access.value.chain, clinicIds.value]))
const section = ref<'clinics' | 'dentists' | 'openings' | 'schedules'>(props.initialSection)
const clinics = ref<OrganizationClinicSummary[]>([])
const dentists = ref<OrganizationDentistSummary[]>([])
const clinicPage = ref({ hasMore: false, nextAfterId: null as string | null })
const dentistPage = ref({ hasMore: false, nextAfterId: null as string | null })
const selectedClinicId = ref('')
const selectedDentistId = ref('')
const clinicDetail = ref<OrganizationClinicDetail | null>(null)
const dentistDetail = ref<OrganizationDentistDetail | null>(null)
const clinicLoading = ref(false)
const dentistLoading = ref(false)
const detailLoading = ref(false)
const clinicError = ref('')
const dentistError = ref('')
const detailError = ref('')
const createPanel = ref<OrganizationCreateKind | null>(null)
const clinicCreateForm = ref({ clinicId: '', clinicCode: '', name: '', postalAddress: '', managerName: '', phone: '', email: '', areaM2: '' })
const dentistCreateForm = ref({ dentistId: '', employeeNo: '', name: '', birthDate: '', homeAddress: '', phone: '', email: '' })
const createBusy = ref(false)
const exactRetryAvailable = ref(false)
const createStatus = ref<'idle' | 'sending' | 'unknown' | 'conflict' | 'rejected' | 'confirmed'>('idle')
const createMessage = ref('')
const createReceipt = ref<OrganizationCreateReceipt | null>(null)
const createReadLoading = ref(false)
const createReadError = ref('')
const createClinicCurrent = ref<OrganizationClinicDetail | null>(null)
const createDentistCurrent = ref<OrganizationDentistDetail | null>(null)
const pendingCreate = ref<OrganizationCreateMarker | null>(null)
const markerStorageBlocked = ref(false)
const markerStorageMessage = ref('')
const markerCleanupAcknowledged = ref(false)
const blockedMarkerForm = ref({ actorAccountId: '', kind: 'clinic' as OrganizationCreateKind, resourceId: '' })
const blockedMarkerInspected = ref(false)
const markerInspected = ref(false)
const manualRetypeReady = ref(false)
const actorGeneration = ref(0)
const disposed = ref(false)
const clinicLane = createRequestLane()
const dentistLane = createRequestLane()
const detailLane = createRequestLane()
const createWriteLane = createOrganizationWriteState()
const createReadLane = createOrganizationWriteState()
let retryBodyMemory: { kind: OrganizationCreateKind; body: OrganizationCreateBody } | null = null
const pendingBelongsToAnotherAccount = computed(() => !!pendingCreate.value && pendingCreate.value.actorAccountId !== String(props.identity.accountId))

function clearPrivateDentist() { clearOrganizationPrivateDetail(dentistDetail) }
function clearDetails() {
  profileDentist.value = null
  selectedClinicId.value = ''; selectedDentistId.value = ''
  clinicDetail.value = null; clearPrivateDentist(); detailError.value = ''; detailLoading.value = false
  detailLane.invalidate()
}
function resetReads() {
  clinicLane.invalidate(); dentistLane.invalidate()
  clinicLoading.value = false; dentistLoading.value = false
  clinics.value = []; dentists.value = []
  clinicPage.value = { hasMore: false, nextAfterId: null }; dentistPage.value = { hasMore: false, nextAfterId: null }
  clinicError.value = ''; dentistError.value = ''
  clearDetails()
}
function emptyClinicCreateForm() { return { clinicId: '', clinicCode: '', name: '', postalAddress: '', managerName: '', phone: '', email: '', areaM2: '' } }
function emptyDentistCreateForm() { return { dentistId: '', employeeNo: '', name: '', birthDate: '', homeAddress: '', phone: '', email: '' } }
function storage() { return window.sessionStorage }
function persistPending(marker: OrganizationCreateMarker) {
  try { return persistOrganizationCreateMarker(storage(), marker) }
  catch { return null }
}
function scrubRetryBody() {
  if (retryBodyMemory?.body && typeof retryBodyMemory.body === 'object') {
    for (const key of Object.keys(retryBodyMemory.body)) (retryBodyMemory.body as Record<string, string>)[key] = ''
  }
  retryBodyMemory = null
  exactRetryAvailable.value = false
}
function demoteSendingMarker() {
  if (pendingCreate.value?.state !== 'sending') return
  const safe = { ...pendingCreate.value, state: 'unknown' as const }
  pendingCreate.value = safe
  persistPending(safe)
}
function clearCreatePrivateMemory() {
  scrubRetryBody()
  clinicCreateForm.value = emptyClinicCreateForm()
  dentistCreateForm.value = emptyDentistCreateForm()
  createReceipt.value = null
  createClinicCurrent.value = null
  createDentistCurrent.value = null
  createReadError.value = ''
  createReadLoading.value = false
  markerInspected.value = false
  manualRetypeReady.value = false
}
function invalidateCreateContext(closePanel = true) {
  demoteSendingMarker()
  createWriteLane.invalidate(); createReadLane.invalidate()
  createBusy.value = false
  clearCreatePrivateMemory()
  blockedMarkerInspected.value = false
  markerCleanupAcknowledged.value = false
  if (closePanel) createPanel.value = null
  createStatus.value = pendingCreate.value?.state === 'unknown' ? 'unknown' : pendingCreate.value?.state === 'conflict' ? 'conflict' : 'idle'
  createMessage.value = pendingCreate.value?.state === 'unknown' ? '原请求结果待核对。请先显式读取当前资料。' : ''
}
function restorePendingCreate() {
  try {
    const marker = loadOrganizationCreateMarker(storage())
    markerStorageBlocked.value = false
    markerStorageMessage.value = ''
    pendingCreate.value = marker
    if (marker) {
      persistPending(marker)
      createStatus.value = marker.state === 'conflict' ? 'conflict' : 'unknown'
      createMessage.value = '存在尚待核对的原操作。刷新后未保留表单内容，请先读取当前资料。'
    }
  } catch (error) {
    pendingCreate.value = null
    markerStorageBlocked.value = true
    markerCleanupAcknowledged.value = false
    blockedMarkerInspected.value = false
    const code = typeof error === 'object' && error && 'code' in error ? String((error as { code?: unknown }).code) : ''
    markerStorageMessage.value = code === 'ORGANIZATION_MARKER_INVALID'
      ? '发现无法安全解析的待核对标识。原内容已保留，创建已暂停。请人工核对原账号与固定ID后，再确认清理。'
      : '暂时无法读取待核对标识。原内容已保留，创建已暂停；请检查会话存储后重试核对。'
  }
}
function capturedCreateWrite(kind: OrganizationCreateKind, ticket: number) {
  return { accountId: props.identity.accountId, token: sessionToken() ?? '', scopeKey: scopeKey.value, actorGeneration: actorGeneration.value, kind, laneTicket: ticket, disposed: disposed.value }
}
function currentCreateWrite(c: ReturnType<typeof capturedCreateWrite>, ticket: number) {
  const current = { ...c, accountId: props.identity.accountId, token: sessionToken() ?? '', scopeKey: scopeKey.value, actorGeneration: actorGeneration.value, laneTicket: ticket, disposed: disposed.value }
  return !disposed.value && createPanel.value === c.kind && createWriteLane.isCurrent(ticket) && organizationWriteContextMatches(c, current)
}
function sameMarker(a: OrganizationCreateMarker | null, b: OrganizationCreateMarker) {
  return !!a && a.actorAccountId === b.actorAccountId && a.kind === b.kind && a.resourceId === b.resourceId && a.route === b.route
}
function openCreate(kind: OrganizationCreateKind) {
  if (markerStorageBlocked.value) {
    createMessage.value = '待核对标识尚未完成安全处理，本次不能开始创建。'
    return
  }
  if (!createAccess.value.canCreate || createBusy.value) return
  if (pendingCreate.value && pendingCreate.value.state !== 'sending') {
    createPanel.value = pendingCreate.value.kind
    createStatus.value = pendingCreate.value.state
    createMessage.value = '请先核对原固定ID，暂不能开始另一项创建。'
    return
  }
  if (createPanel.value !== kind) {
    invalidateCreateContext()
    createPanel.value = kind
    createStatus.value = 'idle'
    createMessage.value = ''
  }
}
function closeCreate() {
  invalidateCreateContext()
}
function prepareManualRetype() {
  const marker = pendingCreate.value
  if (!marker || !markerInspected.value || marker.actorAccountId !== String(props.identity.accountId) || !createAccess.value.canCreate || retryBodyMemory) return
  clearCreatePrivateMemory()
  createPanel.value = marker.kind
  if (marker.kind === 'clinic') clinicCreateForm.value = { ...emptyClinicCreateForm(), clinicId: marker.resourceId }
  else dentistCreateForm.value = { ...emptyDentistCreateForm(), dentistId: marker.resourceId }
  manualRetypeReady.value = true
  markerInspected.value = true
  createStatus.value = marker.state
  createMessage.value = '请人工重新填写原请求资料并沿用固定ID；服务端将判定是否为同一请求。当前资料不能证明原请求已成功。'
}
function blockedCheckCurrent(c: { actorAccountId: string; token: string; scopeKey: string; actorGeneration: number }) {
  return markerStorageBlocked.value && !disposed.value && c.actorAccountId === String(props.identity.accountId)
    && c.token === (sessionToken() ?? '') && c.scopeKey === scopeKey.value && c.actorGeneration === actorGeneration.value
}
async function inspectBlockedMarker() {
  if (!markerStorageBlocked.value || !createAccess.value.canCreate || createReadLoading.value) return
  if (blockedMarkerForm.value.actorAccountId !== String(props.identity.accountId)) {
    markerStorageMessage.value = '请切回原操作账号后，再输入原账号与固定ID进行核对。'
    return
  }
  let resourceId: string
  try { resourceId = normalizeOrganizationId(blockedMarkerForm.value.resourceId, '原固定ID') }
  catch { markerStorageMessage.value = '请填写原固定资源ID；未读取或清理任何标识。'; return }
  const kind = blockedMarkerForm.value.kind
  const context = { actorAccountId: String(props.identity.accountId), token: sessionToken() ?? '', scopeKey: scopeKey.value, actorGeneration: actorGeneration.value }
  const ticket = createReadLane.begin()
  createReadLoading.value = true; blockedMarkerInspected.value = false; markerCleanupAcknowledged.value = false
  createClinicCurrent.value = null; createDentistCurrent.value = null
  try {
    if (kind === 'clinic') {
      const request = organizationClinicDetailRequest(access.value, resourceId)
      const raw = await api<unknown>(request.endpoint)
      if (!blockedCheckCurrent(context) || !createReadLane.isCurrent(ticket)) return
      createClinicCurrent.value = preserveOrganizationClinicDetail(raw, request).resource
    } else {
      const request = organizationDentistDetailRequest(access.value, resourceId)
      const raw = await api<unknown>(request.endpoint)
      if (!blockedCheckCurrent(context) || !createReadLane.isCurrent(ticket)) return
      createDentistCurrent.value = preserveOrganizationDentistDetail(raw, request).resource
    }
    if (!blockedCheckCurrent(context) || !createReadLane.isCurrent(ticket)) return
    blockedMarkerInspected.value = true
    markerStorageMessage.value = '已显式读取当前资料。该结果不能证明原POST成功或失败；如确认原ID已核对，可勾选确认后清理损坏标识。'
  } catch (error) {
    if (!blockedCheckCurrent(context) || !createReadLane.isCurrent(ticket)) return
    const status = typeof error === 'object' && error && 'status' in error ? Number((error as { status?: unknown }).status) : NaN
    blockedMarkerInspected.value = status === 404
    markerStorageMessage.value = status === 404
      ? '当前未找到该ID。原POST结果仍未知；只有人工确认已核对原ID后才可清理损坏标识。'
      : '当前资料读取失败。原标识仍保留，创建继续暂停。'
  } finally {
    if (createReadLane.finish(ticket)) createReadLoading.value = false
  }
}
function confirmBlockedMarkerCleanup() {
  if (!markerStorageBlocked.value || !blockedMarkerInspected.value || !markerCleanupAcknowledged.value) return
  try {
    const result = clearInvalidOrganizationCreateMarker(storage(), { manuallyReconciled: true, confirmed: true })
    markerStorageBlocked.value = false
    markerCleanupAcknowledged.value = false
    blockedMarkerInspected.value = false
    if (result.status === 'valid') {
      pendingCreate.value = result.marker
      createStatus.value = result.marker.state === 'conflict' ? 'conflict' : 'unknown'
      markerStorageMessage.value = '重新检查时发现了新的有效待核对标识，已保留。请由原账号沿原ID继续核对。'
    } else {
      pendingCreate.value = null
      createStatus.value = 'idle'
      markerStorageMessage.value = result.status === 'cleared' ? '已按你的确认清理无法解析的旧标识；现在可开始新操作。' : '待核对标识已不存在；现在可开始新操作。'
    }
    clearCreatePrivateMemory()
  } catch (error) {
    const code = typeof error === 'object' && error && 'code' in error ? String((error as { code?: unknown }).code) : ''
    markerStorageMessage.value = code === 'ORGANIZATION_MARKER_CONFIRMATION_REQUIRED'
      ? '请先完成人工核对并勾选确认；原标识未清理。'
      : '重新读取或清理失败。原标识已保留，创建继续暂停。'
  }
}
function captured() {
  return { accountId: props.identity.accountId, token: sessionToken() ?? '', scopeKey: scopeKey.value, actorGeneration: actorGeneration.value, section: section.value }
}
function current(c: ReturnType<typeof captured>, lane: ReturnType<typeof createRequestLane>, ticket: number) {
  return !disposed.value && c.accountId === props.identity.accountId && c.token === (sessionToken() ?? '')
    && c.scopeKey === scopeKey.value && c.actorGeneration === actorGeneration.value && c.section === section.value && lane.isCurrent(ticket)
}

watch(scopeKey, () => {
  actorGeneration.value += 1
  invalidateCreateContext()
  resetReads()
  if (access.value.allowed) void reloadSection()
}, { flush: 'sync' })
watch(() => props.identity, () => {
  actorGeneration.value += 1
  invalidateCreateContext()
  resetReads()
  if (access.value.allowed) void reloadSection()
}, { flush: 'sync' })
watch(section, () => {
  invalidateCreateContext()
  clearDetails()
  clinicLane.invalidate(); dentistLane.invalidate()
  clinicLoading.value = false; dentistLoading.value = false
  if (section.value === 'dentists') dentists.value = []
  else if (section.value === 'clinics') clinics.value = []
  void reloadSection()
}, { flush: 'sync' })
onMounted(() => { restorePendingCreate(); void reloadSection() })
onUnmounted(() => { disposed.value = true; actorGeneration.value += 1; invalidateCreateContext(); resetReads() })

async function reloadSection() {
  if (section.value === 'clinics') await loadClinics(false)
  else if (access.value.chain) await loadDentists(false)
}
async function loadClinics(append: boolean) {
  clinicError.value = ''
  let request
  try { request = organizationClinicListRequest(access.value, { afterId: append ? clinicPage.value.nextAfterId : null, limit: 30 }) }
  catch { clinicError.value = '当前身份不能读取组织门店。'; return }
  if (append && !request.afterId) return
  const c = captured(); const ticket = clinicLane.begin(); clinicLoading.value = true
  try {
    const result = preserveOrganizationClinicList(await api<unknown>(request.endpoint), request)
    if (!current(c, clinicLane, ticket)) return
    clinics.value = append ? [...clinics.value, ...result.items] : result.items
    clinicPage.value = { hasMore: result.hasMore, nextAfterId: result.nextAfterId }
    if (selectedClinicId.value && !clinics.value.some((row) => row.clinicId === selectedClinicId.value)) clearDetails()
  } catch { if (current(c, clinicLane, ticket)) clinicError.value = '门店列表读取失败，请重新读取。' }
  finally { if (clinicLane.finish(ticket)) clinicLoading.value = false }
}
async function loadDentists(append: boolean) {
  dentistError.value = ''
  let request
  try { request = organizationDentistListRequest(access.value, { afterId: append ? dentistPage.value.nextAfterId : null, limit: 30 }) }
  catch { dentistError.value = '牙医全局目录仅对连锁运营开放。'; return }
  if (append && !request.afterId) return
  const c = captured(); const ticket = dentistLane.begin(); dentistLoading.value = true
  try {
    const result = preserveOrganizationDentistList(await api<unknown>(request.endpoint), request)
    if (!current(c, dentistLane, ticket)) return
    dentists.value = append ? [...dentists.value, ...result.items] : result.items
    dentistPage.value = { hasMore: result.hasMore, nextAfterId: result.nextAfterId }
    if (selectedDentistId.value && !dentists.value.some((row) => row.dentistId === selectedDentistId.value)) clearDetails()
  } catch { if (current(c, dentistLane, ticket)) dentistError.value = '牙医摘要列表读取失败，请重新读取。' }
  finally { if (dentistLane.finish(ticket)) dentistLoading.value = false }
}
async function openClinic(row: OrganizationClinicSummary) {
  clearPrivateDentist(); selectedDentistId.value = ''; dentistDetail.value = null
  selectedClinicId.value = row.clinicId; clinicDetail.value = null; detailError.value = ''
  const request = organizationClinicDetailRequest(access.value, row.clinicId)
  const c = captured(); const ticket = detailLane.begin(); detailLoading.value = true
  try {
    const result = preserveOrganizationClinicDetail(await api<unknown>(request.endpoint), request)
    if (!current(c, detailLane, ticket) || selectedClinicId.value !== row.clinicId) return
    clinicDetail.value = result.resource
  } catch { if (current(c, detailLane, ticket)) detailError.value = '门店详情读取失败，请重新读取。' }
  finally { if (detailLane.finish(ticket)) detailLoading.value = false }
}
function openPublicProfile(row: OrganizationDentistSummary) {
  clearDetails()
  profileDentist.value = { ...row }
}

async function openDentist(row: OrganizationDentistSummary) {
  profileDentist.value = null
  clearPrivateDentist(); selectedClinicId.value = ''; clinicDetail.value = null
  selectedDentistId.value = row.dentistId; detailError.value = ''
  let request
  try { request = organizationDentistDetailRequest(access.value, row.dentistId) }
  catch { detailError.value = '当前身份不能读取牙医个人详情。'; return }
  const c = captured(); const ticket = detailLane.begin(); detailLoading.value = true
  try {
    const result = preserveOrganizationDentistDetail(await api<unknown>(request.endpoint), request)
    if (!current(c, detailLane, ticket) || selectedDentistId.value !== row.dentistId) return
    dentistDetail.value = result.resource
  } catch { if (current(c, detailLane, ticket)) detailError.value = '牙医私密详情读取失败，请重新读取。' }
  finally { if (detailLane.finish(ticket)) detailLoading.value = false }
}
function currentCreateReadContext(marker: OrganizationCreateMarker | null = null) {
  return { accountId: props.identity.accountId, token: sessionToken() ?? '', scopeKey: scopeKey.value, actorGeneration: actorGeneration.value, marker }
}
function createReadContextCurrent(c: ReturnType<typeof currentCreateReadContext>, marker: OrganizationCreateMarker | null = null) {
  return !disposed.value && c.accountId === props.identity.accountId && c.token === (sessionToken() ?? '')
    && c.scopeKey === scopeKey.value && c.actorGeneration === actorGeneration.value
    && (!marker || sameMarker(pendingCreate.value, marker))
}
async function readCurrentCreateResource(kind: OrganizationCreateKind, resourceId: string, stillCurrent: () => boolean) {
  const clinicRequest = kind === 'clinic' ? organizationClinicDetailRequest(access.value, resourceId) : null
  const dentistRequest = kind === 'dentist' ? organizationDentistDetailRequest(access.value, resourceId) : null
  const ticket = createReadLane.begin()
  createReadLoading.value = true
  createReadError.value = ''
  createClinicCurrent.value = null; createDentistCurrent.value = null
  try {
    const raw = await api<unknown>((clinicRequest ?? dentistRequest)!.endpoint)
    if (!stillCurrent() || !createReadLane.isCurrent(ticket)) return
    if (clinicRequest) createClinicCurrent.value = preserveOrganizationClinicDetail(raw, clinicRequest).resource
    else createDentistCurrent.value = preserveOrganizationDentistDetail(raw, dentistRequest!).resource
  } catch {
    if (stillCurrent() && createReadLane.isCurrent(ticket)) createReadError.value = '当前资料读取失败。已确认的创建回执仍保留为已确认；读取失败不会改变POST结果。'
    throw new Error('当前资料读取失败。')
  } finally {
    if (createReadLane.finish(ticket)) createReadLoading.value = false
  }
}
async function inspectPendingCreate() {
  const marker = pendingCreate.value
  if (!marker || createReadLoading.value) return
  const context = currentCreateReadContext(marker)
  const ticket = createReadLane.begin()
  createReadLoading.value = true; createReadError.value = ''; markerInspected.value = false
  createClinicCurrent.value = null; createDentistCurrent.value = null
  try {
    if (marker.kind === 'clinic') {
      const request = organizationClinicDetailRequest(access.value, marker.resourceId)
      const raw = await api<unknown>(request.endpoint)
      if (!createReadContextCurrent(context, marker) || !createReadLane.isCurrent(ticket)) return
      createClinicCurrent.value = preserveOrganizationClinicDetail(raw, request).resource
    } else {
      const request = organizationDentistDetailRequest(access.value, marker.resourceId)
      const raw = await api<unknown>(request.endpoint)
      if (!createReadContextCurrent(context, marker) || !createReadLane.isCurrent(ticket)) return
      createDentistCurrent.value = preserveOrganizationDentistDetail(raw, request).resource
    }
    if (!createReadContextCurrent(context, marker) || !createReadLane.isCurrent(ticket)) return
    markerInspected.value = true
    createReadError.value = '这是本次显式读取到的当前资料，不能证明原POST是否成功或请求内容是否相同。'
  } catch (error) {
    if (!createReadContextCurrent(context, marker) || !createReadLane.isCurrent(ticket)) return
    const status = typeof error === 'object' && error && 'status' in error ? Number((error as { status?: unknown }).status) : NaN
    markerInspected.value = status === 404
    createReadError.value = status === 404
      ? '当前没有查到该ID的资料。原POST仍未被证明成功或失败；如需重试，请沿用原ID并由服务端核对。'
      : '未能读取当前资料。原POST结果仍待核对；当前读取失败不代表原创建成功或失败。'
  } finally {
    if (createReadLane.finish(ticket)) createReadLoading.value = false
  }
}
function markerFor(kind: OrganizationCreateKind, resourceId: string, state: OrganizationCreateMarker['state']): OrganizationCreateMarker {
  const route = organizationCreateRoute(kind, resourceId)
  return { actorAccountId: String(props.identity.accountId), kind, resourceId: route.resourceId, route: route.route, state, scope: 'chain' }
}
function errorText(error: unknown) {
  if (typeof error === 'object' && error && 'status' in error) {
    const status = Number((error as { status?: unknown }).status)
    if (status === 400) return '资料未通过校验，未确认创建。请修正表单后沿用原ID重新提交。'
    if (status === 401) return '登录状态已失效，请重新登录后核对。'
    if (status === 403) return '当前账号没有创建权限，服务端未确认本次创建。'
    if (status === 409) return '固定ID或唯一编号冲突。请先读取并核对当前资料，不要改用新ID。'
    if (status >= 500) return '服务端暂未给出明确结果。原请求已保留在本页内存，可显式沿原ID和原内容重试。'
  }
  return '网络或响应异常，结果暂未确认。原请求已保留在本页内存，可显式沿原ID和原内容重试。'
}
async function submitCreate(kind: OrganizationCreateKind, suppliedBody?: OrganizationCreateBody) {
  if (markerStorageBlocked.value) {
    createMessage.value = '待核对标识尚未完成安全处理，本次没有发送。'
    return
  }
  if (!createAccess.value.canCreate || createBusy.value) return
  let body: OrganizationCreateBody
  const exactRetry = suppliedBody !== undefined
  try {
    body = exactRetry ? suppliedBody : normalizeOrganizationCreateBody(kind, kind === 'clinic' ? clinicCreateForm.value : dentistCreateForm.value)
    if (exactRetry && (retryBodyMemory?.kind !== kind || retryBodyMemory.body !== suppliedBody)) throw new Error('原请求内容已不可用，请读取当前资料并人工重填。')
  } catch (error) { createStatus.value = 'rejected'; createMessage.value = error instanceof Error ? error.message : '资料格式无效。'; return }
  const idField = kind === 'clinic' ? 'clinicId' : 'dentistId'
  const resourceId = String(body[idField as keyof typeof body])
  const existing = pendingCreate.value
  const retryAllowed = organizationCreateRetryAllowed({
    pendingMarker: existing, actorAccountId: String(props.identity.accountId), kind, resourceId, exactRetry,
    manualRetypeReady: manualRetypeReady.value, markerInspected: markerInspected.value,
  })
  if (!retryAllowed || (exactRetry && (retryBodyMemory?.kind !== kind || retryBodyMemory.body !== suppliedBody))) {
    createMessage.value = pendingBelongsToAnotherAccount.value
      ? '此待核对操作属于原账号，请切回原账号读取并处理；当前账号不能覆盖。'
      : '请先核对原固定ID；未知请求不能编辑、换号或开始另一项创建。'
    return
  }
  const ticket = createWriteLane.begin()
  const context = capturedCreateWrite(kind, ticket)
  const marker = existing ? { ...existing, state: 'sending' as const } : markerFor(kind, resourceId, 'sending')
  if (marker.actorAccountId !== String(props.identity.accountId) || marker.resourceId !== resourceId || marker.kind !== kind) {
    createWriteLane.finish(ticket)
    createMessage.value = '请切回原操作账号并沿用原固定ID后再核对。'
    return
  }
  let markerStorage: Storage
  try { markerStorage = storage() }
  catch {
    createWriteLane.finish(ticket)
    createMessage.value = '无法保存安全核对标识，本次没有发送。请检查浏览器会话存储后重试。'
    return
  }
  markerInspected.value = false
  manualRetypeReady.value = false
  retryBodyMemory = { kind, body }
  exactRetryAvailable.value = true
  createStatus.value = 'sending'; createMessage.value = ''; createReceipt.value = null
  createReadError.value = ''; createClinicCurrent.value = null; createDentistCurrent.value = null
  createBusy.value = true
  try {
    await runOrganizationCreateAttempt({
      storage: markerStorage, marker, body,
      post: (route, requestBody) => api<unknown>(route, { method: 'POST', body: requestBody }),
      readCurrent: (readKind, readId) => readCurrentCreateResource(readKind, readId, () => currentCreateWrite(context, ticket)),
      isCurrent: () => currentCreateWrite(context, ticket),
      onPhase: (phase, detail, nextMarker) => {
        if (phase === 'blocked') {
          scrubRetryBody()
          const code = typeof detail === 'object' && detail && 'code' in detail ? String((detail as { code?: unknown }).code) : ''
          if (code === 'ORGANIZATION_MARKER_INVALID' || code === 'ORGANIZATION_MARKER_UNREADABLE') {
            markerStorageBlocked.value = true
            markerCleanupAcknowledged.value = false; blockedMarkerInspected.value = false
            clearCreatePrivateMemory(); createPanel.value = null
            markerStorageMessage.value = code === 'ORGANIZATION_MARKER_INVALID'
              ? '发现无法安全解析的待核对标识。原内容已保留，创建已暂停。请人工核对原账号与固定ID后，再确认清理。'
              : '暂时无法读取待核对标识。原内容已保留，创建已暂停；请检查会话存储后重试核对。'
            createStatus.value = 'rejected'; createMessage.value = '待核对标识异常，本次没有发送。'
          } else if (code === 'ORGANIZATION_MARKER_PENDING') {
            try {
              pendingCreate.value = loadOrganizationCreateMarker(markerStorage)
              clearCreatePrivateMemory(); createPanel.value = null
              createStatus.value = pendingCreate.value?.state === 'conflict' ? 'conflict' : 'unknown'
              createMessage.value = '检测到其他待核对操作，原标识已保留；请由原账号沿原ID处理。'
            } catch {
              markerStorageBlocked.value = true
              clearCreatePrivateMemory(); createPanel.value = null
              markerStorageMessage.value = '待核对标识无法安全读取，原内容已保留；创建已暂停。'
              createMessage.value = '待核对标识异常，本次没有发送。'
            }
          } else {
            createStatus.value = 'rejected'; createMessage.value = '无法保存安全核对标识，本次没有发送。请检查浏览器会话存储后重试。'
          }
        } else if (phase === 'sending' && nextMarker) {
          pendingCreate.value = nextMarker; createStatus.value = 'sending'; createMessage.value = ''
        } else if (phase === 'unknown' && nextMarker) {
          pendingCreate.value = nextMarker; createStatus.value = 'unknown'
          createMessage.value = detail instanceof Error && detail.message.includes('回执')
            ? '服务端响应无法核对，创建结果暂未确认。原请求仍保留在本页内存。'
            : errorText(detail)
        } else if (phase === 'conflict' && nextMarker) {
          pendingCreate.value = nextMarker; scrubRetryBody()
          clinicCreateForm.value = emptyClinicCreateForm(); dentistCreateForm.value = emptyDentistCreateForm()
          createStatus.value = 'conflict'; createMessage.value = errorText(detail)
        } else if (phase === 'rejected') {
          pendingCreate.value = null; scrubRetryBody()
          createStatus.value = 'rejected'; createMessage.value = errorText(detail)
        } else if (phase === 'confirmed' && detail && typeof detail === 'object') {
          createReceipt.value = detail as OrganizationCreateReceipt
          createStatus.value = 'confirmed'
          createMessage.value = createReceipt.value.replayed ? '服务端回放了同一原请求的安全回执。' : '服务端已确认创建。'
          scrubRetryBody()
          clinicCreateForm.value = emptyClinicCreateForm(); dentistCreateForm.value = emptyDentistCreateForm()
          pendingCreate.value = nextMarker ?? null
          markerInspected.value = false; manualRetypeReady.value = false
        }
      },
      onReadFailure: () => {
        if (currentCreateWrite(context, ticket)) createReadError.value = '创建回执已确认；单独读取当前资料失败，不会改变POST结果。'
      },
    })
  } finally {
    if (createWriteLane.finish(ticket)) createBusy.value = false
  }
}
function submitClinicCreate() { void submitCreate('clinic') }
function submitDentistCreate() { void submitCreate('dentist') }
function retryExactCreate() {
  if (retryBodyMemory) void submitCreate(retryBodyMemory.kind, retryBodyMemory.body)
}
function closeDetail() { clearDetails(); invalidateCreateContext() }
function clinicMore() { void loadClinics(true) }
function dentistMore() { void loadDentists(true) }
</script>

<template>
  <div class="organization-page">
    <header class="page-heading"><div><p class="eyebrow">运营工作台 · 组织资料</p><h1>组织资料</h1><p class="muted">查看授权范围内的门店与牙医资料；连锁运营人员可创建基础资料。</p></div><span class="organization-scope">{{ access.chain ? '连锁运营范围' : `获权门店：${clinicIds.map((id) => `#${id}`).join('、')}` }}</span></header>
    <div class="organization-tabs" role="tablist" aria-label="组织资料类别">
      <button :class="{ active: section === 'clinics' }" @click="section = 'clinics'">门店</button>
      <button v-if="access.chain" :class="{ active: section === 'dentists' }" @click="section = 'dentists'">牙医摘要</button>
      <button v-else disabled title="全局牙医目录仅限连锁运营查看">牙医目录（连锁运营）</button>
      <button :class="{ active: section === 'openings' }" @click="section = 'openings'">开放时段</button>
      <button :class="{ active: section === 'schedules' }" @click="section = 'schedules'">牙医出诊</button>
      <button data-readonly-write v-if="createAccess.canCreate" class="button button-quiet" :disabled="markerStorageBlocked || pendingBelongsToAnotherAccount" @click="openCreate('clinic')">新建门店</button>
      <button data-readonly-write v-if="createAccess.canCreate" class="button button-quiet" :disabled="markerStorageBlocked || pendingBelongsToAnotherAccount" @click="openCreate('dentist')">新建牙医</button>
      <button class="button button-quiet" @click="reloadSection">重新读取</button>
    </div>

    <section v-if="markerStorageBlocked" class="panel organization-pending organization-marker-blocked" aria-live="polite">
      <div class="section-heading"><div><p class="eyebrow">安全核对暂停</p><h2>待核对标识无法读取</h2></div><span class="organization-scope">创建已暂停</span></div>
      <p class="muted">原标识仍保留。系统不会显示其原文、自动清理、发送新请求或覆盖它。请切回原操作账号，并用你掌握的原账号与固定ID显式读取当前资料。</p>
      <div class="organization-create-grid">
        <label class="organization-field"><span>原操作账号ID</span><input v-model="blockedMarkerForm.actorAccountId" inputmode="numeric" autocomplete="off" placeholder="输入原账号ID"></label>
        <label class="organization-field"><span>资源类型</span><select v-model="blockedMarkerForm.kind"><option value="clinic">门店</option><option value="dentist">牙医</option></select></label>
        <label class="organization-field"><span>原固定资源ID</span><input v-model="blockedMarkerForm.resourceId" inputmode="numeric" autocomplete="off" placeholder="输入原固定ID"></label>
      </div>
      <div class="organization-actions"><button class="button button-quiet" :disabled="!createAccess.canCreate || createReadLoading" @click="inspectBlockedMarker">{{ createReadLoading ? '正在读取…' : '显式读取当前资料' }}</button></div>
      <p class="organization-private-note">读取到的当前资料不能证明原POST成功或请求内容相同。</p>
      <p v-if="markerStorageMessage" class="form-error" role="status">{{ markerStorageMessage }}</p>
      <dl v-if="createClinicCurrent" class="organization-details organization-current"><dt>当前门店</dt><dd>{{ createClinicCurrent.name }} · {{ createClinicCurrent.clinicCode }} · #{{ createClinicCurrent.clinicId }}</dd><dt>地址</dt><dd>{{ createClinicCurrent.postalAddress }}</dd><dt>负责人</dt><dd>{{ createClinicCurrent.managerName }}</dd><dt>电话</dt><dd>{{ createClinicCurrent.phone }}</dd><dt>邮箱</dt><dd>{{ createClinicCurrent.email }}</dd><dt>面积（平方米）</dt><dd>{{ createClinicCurrent.areaM2 }}</dd></dl>
      <dl v-if="createDentistCurrent" class="organization-details organization-current"><dt>当前牙医</dt><dd>{{ createDentistCurrent.name }} · {{ createDentistCurrent.employeeNo }} · #{{ createDentistCurrent.dentistId }}</dd><dt>生日</dt><dd>{{ createDentistCurrent.birthDate }}</dd><dt>家庭住址</dt><dd>{{ createDentistCurrent.homeAddress }}</dd><dt>电话</dt><dd>{{ createDentistCurrent.phone }}</dd><dt>邮箱</dt><dd>{{ createDentistCurrent.email }}</dd></dl>
      <label v-if="blockedMarkerInspected" class="organization-confirm-cleanup"><input v-model="markerCleanupAcknowledged" type="checkbox">我已人工核对原账号和固定ID，并确认清理当前仍无法解析的旧标识。</label>
      <div class="organization-actions"><button class="button" :disabled="!blockedMarkerInspected || !markerCleanupAcknowledged" @click="confirmBlockedMarkerCleanup">确认清理已核对的损坏标识</button></div>
    </section>

    <section v-if="pendingCreate" class="panel organization-pending" aria-live="polite">
      <div class="section-heading"><div><p class="eyebrow">待核对操作</p><h2>{{ pendingCreate.kind === 'clinic' ? '门店' : '牙医' }} · #{{ pendingCreate.resourceId }}</h2></div><span class="organization-scope">{{ pendingCreate.state === 'conflict' ? '冲突待核对' : '结果待核对' }}</span></div>
      <p class="muted">{{ pendingBelongsToAnotherAccount ? '此待核对操作属于原账号 #' + pendingCreate.actorAccountId + '。请切回原账号核对；当前账号不能重试或覆盖。' : '原操作账号 #' + pendingCreate.actorAccountId + '。这里只保存固定ID和核对标识，不保留表单内容。请按原权限显式读取当前资料；读取结果不能证明原请求成功。' }}</p>
      <div class="organization-actions">
        <button class="button button-quiet" :disabled="!canInspectPending || createReadLoading" @click="inspectPendingCreate">{{ createReadLoading ? '正在读取…' : '显式读取当前资料' }}</button>
        <button v-if="createAccess.canCreate && pendingCreate.actorAccountId === String(props.identity.accountId) && markerInspected && !exactRetryAvailable" class="button button-quiet" :disabled="createReadLoading" @click="prepareManualRetype">按原ID人工重填</button>
      </div>
      <p v-if="!canInspectPending" class="form-error" role="status">当前身份不能读取这项资料；请切回原授权范围核对。</p>
      <p v-if="createReadError" class="form-error" role="status">{{ createReadError }}</p>
      <dl v-if="createClinicCurrent" class="organization-details organization-current"><dt>当前门店</dt><dd>{{ createClinicCurrent.name }} · {{ createClinicCurrent.clinicCode }} · #{{ createClinicCurrent.clinicId }}</dd><dt>邮政地址</dt><dd>{{ createClinicCurrent.postalAddress }}</dd><dt>负责人</dt><dd>{{ createClinicCurrent.managerName }}</dd><dt>电话</dt><dd>{{ createClinicCurrent.phone }}</dd><dt>邮箱</dt><dd>{{ createClinicCurrent.email }}</dd><dt>面积（平方米）</dt><dd>{{ createClinicCurrent.areaM2 }}</dd></dl>
      <dl v-if="createDentistCurrent" class="organization-details organization-current"><dt>当前牙医</dt><dd>{{ createDentistCurrent.name }} · {{ createDentistCurrent.employeeNo }} · #{{ createDentistCurrent.dentistId }}</dd><dt>生日</dt><dd>{{ createDentistCurrent.birthDate }}</dd><dt>家庭住址</dt><dd>{{ createDentistCurrent.homeAddress }}</dd><dt>电话</dt><dd>{{ createDentistCurrent.phone }}</dd><dt>邮箱</dt><dd>{{ createDentistCurrent.email }}</dd></dl>
      <p v-if="markerInspected" class="organization-private-note">上方是本次单独读取到的当前资料。若决定再次提交，请人工重填并保留原ID；由服务端判断是否与原请求相同。</p>
    </section>

    <section v-if="createPanel" class="panel organization-create-panel">
      <div class="section-heading"><div><p class="eyebrow">仅限连锁运营人员／店长</p><h2>新建{{ createPanel === 'clinic' ? '门店' : '牙医' }}</h2></div><button class="button button-quiet" @click="closeCreate">关闭并清除表单</button></div>
      <p class="muted">一次只创建一项基础资料。填写固定ID；本页不会自动建立账号、授权或其他业务记录。</p>
      <p v-if="createMessage" :class="createStatus === 'rejected' ? 'form-error' : 'organization-private-note'" role="status">{{ createMessage }}</p>
      <p v-if="createStatus === 'unknown' && exactRetryAvailable" class="form-error" role="alert">请求结果未知。表单已冻结；只能显式沿原ID和原内容重试。</p>
      <form v-if="createPanel === 'clinic'" class="organization-create-form" @submit.prevent="submitClinicCreate">
        <fieldset :disabled="markerStorageBlocked || createBusy || (!!pendingCreate && !manualRetypeReady && !exactRetryAvailable)">
          <div class="organization-create-grid">
            <label class="organization-field"><span>固定门店ID</span><input v-model="clinicCreateForm.clinicId" :readonly="createStatus === 'unknown' && exactRetryAvailable || manualRetypeReady" inputmode="numeric" autocomplete="off" required maxlength="19" placeholder="正63位范围内的十进制字符串"></label>
            <label class="organization-field"><span>门店编号</span><input v-model="clinicCreateForm.clinicCode" :readonly="createStatus === 'unknown' && exactRetryAvailable" autocomplete="off" required maxlength="32"></label>
            <label class="organization-field"><span>门店名称</span><input v-model="clinicCreateForm.name" :readonly="createStatus === 'unknown' && exactRetryAvailable" autocomplete="off" required maxlength="100"></label>
            <label class="organization-field"><span>邮政地址</span><input v-model="clinicCreateForm.postalAddress" :readonly="createStatus === 'unknown' && exactRetryAvailable" autocomplete="off" required maxlength="255"></label>
            <label class="organization-field"><span>负责人</span><input v-model="clinicCreateForm.managerName" :readonly="createStatus === 'unknown' && exactRetryAvailable" autocomplete="off" required maxlength="80"></label>
            <label class="organization-field"><span>联系电话</span><input v-model="clinicCreateForm.phone" :readonly="createStatus === 'unknown' && exactRetryAvailable" autocomplete="off" required maxlength="32"></label>
            <label class="organization-field"><span>邮箱</span><input v-model="clinicCreateForm.email" :readonly="createStatus === 'unknown' && exactRetryAvailable" inputmode="email" autocomplete="off" required maxlength="254"></label>
            <label class="organization-field"><span>面积（平方米）</span><input v-model="clinicCreateForm.areaM2" :readonly="createStatus === 'unknown' && exactRetryAvailable" inputmode="decimal" autocomplete="off" required placeholder="最多8位整数、2位小数"></label>
          </div>
          <div class="organization-actions"><button data-readonly-write class="button" type="submit" :disabled="markerStorageBlocked || pendingBelongsToAnotherAccount || createBusy || (!!pendingCreate && !manualRetypeReady) || (createStatus === 'unknown' && exactRetryAvailable)">{{ createBusy ? '正在提交…' : '提交门店创建' }}</button><button v-if="createStatus === 'unknown' && exactRetryAvailable" class="button button-quiet" type="button" :disabled="markerStorageBlocked || createBusy || createReadLoading" @click="retryExactCreate">沿原ID和原内容重试</button></div>
        </fieldset>
      </form>
      <form v-else class="organization-create-form" @submit.prevent="submitDentistCreate">
        <fieldset :disabled="markerStorageBlocked || createBusy || (!!pendingCreate && !manualRetypeReady && !exactRetryAvailable)">
          <div class="organization-create-grid">
            <label class="organization-field"><span>固定牙医ID</span><input v-model="dentistCreateForm.dentistId" :readonly="createStatus === 'unknown' && exactRetryAvailable || manualRetypeReady" inputmode="numeric" autocomplete="off" required maxlength="19" placeholder="正63位范围内的十进制字符串"></label>
            <label class="organization-field"><span>员工编号</span><input v-model="dentistCreateForm.employeeNo" :readonly="createStatus === 'unknown' && exactRetryAvailable" autocomplete="off" required maxlength="32"></label>
            <label class="organization-field"><span>牙医姓名</span><input v-model="dentistCreateForm.name" :readonly="createStatus === 'unknown' && exactRetryAvailable" autocomplete="off" required maxlength="80"></label>
            <label class="organization-field"><span>出生日期</span><input v-model="dentistCreateForm.birthDate" :readonly="createStatus === 'unknown' && exactRetryAvailable" inputmode="numeric" autocomplete="off" required placeholder="YYYY-MM-DD"></label>
            <label class="organization-field"><span>家庭住址</span><input v-model="dentistCreateForm.homeAddress" :readonly="createStatus === 'unknown' && exactRetryAvailable" autocomplete="off" required maxlength="255"></label>
            <label class="organization-field"><span>联系电话</span><input v-model="dentistCreateForm.phone" :readonly="createStatus === 'unknown' && exactRetryAvailable" autocomplete="off" required maxlength="32"></label>
            <label class="organization-field"><span>邮箱</span><input v-model="dentistCreateForm.email" :readonly="createStatus === 'unknown' && exactRetryAvailable" inputmode="email" autocomplete="off" required maxlength="254"></label>
          </div>
          <p class="organization-private-note">出生日期、家庭住址、电话和邮箱只在当前页面及本次请求内存中使用，不会进入待核对标识。</p>
          <div class="organization-actions"><button data-readonly-write class="button" type="submit" :disabled="markerStorageBlocked || pendingBelongsToAnotherAccount || createBusy || (!!pendingCreate && !manualRetypeReady) || (createStatus === 'unknown' && exactRetryAvailable)">{{ createBusy ? '正在提交…' : '提交牙医创建' }}</button><button v-if="createStatus === 'unknown' && exactRetryAvailable" class="button button-quiet" type="button" :disabled="markerStorageBlocked || createBusy || createReadLoading" @click="retryExactCreate">沿原ID和原内容重试</button></div>
        </fieldset>
      </form>
      <section v-if="createReceipt" class="organization-create-result" aria-live="polite">
        <h3>创建回执</h3><p>{{ createReceipt.replayed ? '服务端回放了同一原请求的固定回执。' : '服务端已确认创建。' }}</p>
        <p v-if="createPanel === 'clinic'">门店 #{{ createReceipt.resource.clinicId }} · {{ createReceipt.resource.clinicCode }} · {{ createReceipt.resource.name }}</p>
        <p v-else>牙医 #{{ createReceipt.resource.dentistId }} · {{ createReceipt.resource.employeeNo }} · {{ createReceipt.resource.name }}</p>
        <p class="muted">此回执只包含安全摘要；下方当前资料来自单独的详情读取。</p>
        <p v-if="createReadLoading" class="muted">正在单独读取当前资料…</p>
        <p v-if="createReadError" class="form-error" role="status">{{ createReadError }}</p>
        <dl v-if="createClinicCurrent" class="organization-details"><dt>当前地址</dt><dd>{{ createClinicCurrent.postalAddress }}</dd><dt>负责人</dt><dd>{{ createClinicCurrent.managerName }}</dd><dt>电话</dt><dd>{{ createClinicCurrent.phone }}</dd><dt>邮箱</dt><dd>{{ createClinicCurrent.email }}</dd><dt>面积（平方米）</dt><dd>{{ createClinicCurrent.areaM2 }}</dd></dl>
        <dl v-if="createDentistCurrent" class="organization-details"><dt>当前生日</dt><dd>{{ createDentistCurrent.birthDate }}</dd><dt>家庭住址</dt><dd>{{ createDentistCurrent.homeAddress }}</dd><dt>电话</dt><dd>{{ createDentistCurrent.phone }}</dd><dt>邮箱</dt><dd>{{ createDentistCurrent.email }}</dd></dl>
      </section>
    </section>

    <OrganizationOpeningView v-if="section === 'openings'" :identity="props.identity" />
    <OrganizationScheduleView v-else-if="section === 'schedules'" :identity="props.identity" />

    <section v-else-if="section === 'clinics'" class="panel organization-panel">
      <div class="section-heading"><div><p class="eyebrow">{{ access.chain ? '连锁范围' : '获权门店范围' }}</p><h2>门店列表</h2></div><span class="muted">{{ clinics.length }} 条</span></div>
      <p v-if="clinicError" class="form-error" role="alert">{{ clinicError }}</p>
      <p v-if="clinicLoading && !clinics.length" class="muted">正在读取门店摘要…</p>
      <p v-else-if="!clinics.length" class="muted">当前运营范围没有可读门店。</p>
      <div v-else class="organization-table-wrap"><table class="organization-table"><thead><tr><th>门店</th><th>门店编号</th><th>状态</th><th>变更计数</th><th></th></tr></thead><tbody><tr v-for="row in clinics" :key="row.clinicId"><td>{{ row.name }} · #{{ row.clinicId }}</td><td>{{ row.clinicCode }}</td><td>{{ row.active ? '启用' : '停用' }}</td><td>{{ row.mutationCount }}</td><td><button class="button button-quiet" @click="openClinic(row)">查看详情</button></td></tr></tbody></table></div>
      <div v-if="clinicPage.hasMore" class="organization-actions"><button class="button button-quiet" :disabled="clinicLoading" @click="clinicMore">继续加载</button></div>
    </section>

    <section v-else-if="section === 'dentists'" class="panel organization-panel">
      <div class="section-heading"><div><p class="eyebrow">连锁运营授权</p><h2>牙医摘要</h2></div><span class="muted">{{ dentists.length }} 条</span></div>
      <p v-if="dentistError" class="form-error" role="alert">{{ dentistError }}</p>
      <p v-if="dentistLoading && !dentists.length" class="muted">正在读取牙医安全摘要…</p>
      <p v-else-if="!dentists.length" class="muted">当前目录没有牙医摘要。</p>
      <div v-else class="organization-table-wrap"><table class="organization-table"><thead><tr><th>牙医</th><th>员工编号</th><th>状态</th><th>变更计数</th><th></th></tr></thead><tbody><tr v-for="row in dentists" :key="row.dentistId"><td>{{ row.name }} · #{{ row.dentistId }}</td><td>{{ row.employeeNo }}</td><td>{{ row.active ? '启用' : '停用' }}</td><td>{{ row.mutationCount }}</td><td><button class="button button-quiet" @click="openPublicProfile(row)">编辑公开资料</button><button class="button button-quiet" @click="openDentist(row)">显式读取个人详情</button></td></tr></tbody></table></div>
      <div v-if="dentistPage.hasMore" class="organization-actions"><button class="button button-quiet" :disabled="dentistLoading" @click="dentistMore">继续加载</button></div>
      <p class="organization-private-note">生日、住址、电话和邮箱仅在点击个人详情后请求，并仅显示在此页内存中；关闭详情、切换范围、账号或页面时立即清除。</p>
      <div v-if="profileDentist && access.chain"><button class="button button-quiet" @click="profileDentist = null">关闭公开资料编辑</button><DentistPublicProfileEditor :key="profileDentist.dentistId" :identity="identity" :dentist="profileDentist" /></div>
    </section>

    <section v-if="selectedClinicId || selectedDentistId" class="panel organization-detail">
      <div class="section-heading"><div><p class="eyebrow">显式详情读取</p><h2>{{ selectedClinicId ? '门店详情' : '牙医个人详情' }}</h2></div><button class="button button-quiet" @click="closeDetail">关闭并清除</button></div>
      <p v-if="detailLoading" class="muted">正在读取详情…</p><p v-if="detailError" class="form-error" role="alert">{{ detailError }}</p>
      <dl v-if="clinicDetail" class="organization-details"><dt>门店地址</dt><dd>{{ clinicDetail.postalAddress }}</dd><dt>负责人</dt><dd>{{ clinicDetail.managerName }}</dd><dt>电话</dt><dd>{{ clinicDetail.phone }}</dd><dt>邮箱</dt><dd>{{ clinicDetail.email }}</dd><dt>面积（平方米）</dt><dd>{{ clinicDetail.areaM2 }}</dd><dt>mutationCount</dt><dd>{{ clinicDetail.mutationCount }}</dd></dl>
      <OrganizationMaintenance v-if="clinicDetail" :key="clinicDetail.clinicId" :clinic="clinicDetail" :read-only="identity.readOnly" @changed="openClinic(clinicDetail)" />
      <dl v-if="dentistDetail" class="organization-details"><dt>牙医</dt><dd>{{ dentistDetail.name }} · #{{ dentistDetail.dentistId }}</dd><dt>生日</dt><dd>{{ dentistDetail.birthDate }}</dd><dt>家庭住址</dt><dd>{{ dentistDetail.homeAddress }}</dd><dt>电话</dt><dd>{{ dentistDetail.phone }}</dd><dt>邮箱</dt><dd>{{ dentistDetail.email }}</dd><dt>mutationCount</dt><dd>{{ dentistDetail.mutationCount }}</dd></dl>
      <OrganizationMaintenance v-if="dentistDetail" :key="dentistDetail.dentistId" :dentist="dentistDetail" :read-only="identity.readOnly" @changed="openDentist(dentistDetail)" />
    </section>
    <p class="organization-footer">资料更正、启停、出诊更正/撤销和自动预约仍未提供。门店开放不代表牙医出诊或可预约时间。</p>
  </div>
</template>

<style scoped>
.organization-page { display: grid; gap: 16px; }
.organization-scope { align-self: start; border-radius: 999px; padding: 8px 12px; background: #e7f5ef; color: #216454; font-size: 12px; font-weight: 700; }
.organization-tabs { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.organization-tabs button { border: 1px solid var(--line); border-radius: 9px; padding: 9px 13px; background: #fff; color: var(--ink); cursor: pointer; }
.organization-tabs button.active { border-color: #367a68; background: #e7f5ef; color: #216454; font-weight: 700; }
.organization-tabs button:disabled { color: var(--muted); cursor: not-allowed; }
.organization-tabs .button { margin-left: auto; }
.organization-panel,.organization-detail { padding: 20px; min-width: 0; }
.organization-create-panel,.organization-pending { padding: 20px; min-width: 0; }
.organization-create-form fieldset { border: 0; padding: 0; margin: 0; min-width: 0; }
.organization-create-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.organization-field { display: grid; gap: 6px; color: var(--ink); font-size: 13px; font-weight: 650; }
.organization-field input { width: 100%; min-width: 0; border: 1px solid var(--line); border-radius: 9px; padding: 10px 11px; background: #fff; color: var(--ink); font: inherit; font-weight: 400; }
.organization-field input:focus { outline: 2px solid #367a68; outline-offset: 1px; }
.organization-field input[readonly] { background: #f3f6f5; color: var(--muted); }
.organization-field select { width: 100%; min-width: 0; border: 1px solid var(--line); border-radius: 9px; padding: 10px 11px; background: #fff; color: var(--ink); font: inherit; }
.organization-confirm-cleanup { display: flex; align-items: flex-start; gap: 8px; color: var(--ink); font-size: 13px; line-height: 1.5; }
.organization-confirm-cleanup input { margin-top: 3px; flex: 0 0 auto; }
.organization-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.organization-pending,.organization-create-result { display: grid; gap: 10px; }
.organization-create-result { margin-top: 18px; padding-top: 16px; border-top: 1px solid var(--line); }
.organization-create-result h3 { margin: 0; }
.organization-create-result p { margin: 0; }
.organization-current { margin-top: 12px; }
.organization-table-wrap { overflow-x: auto; }
.organization-table { width: 100%; border-collapse: collapse; min-width: 620px; }
.organization-table th,.organization-table td { padding: 11px 9px; border-bottom: 1px solid var(--line); text-align: left; white-space: nowrap; }
.organization-table th { color: var(--muted); font-size: 12px; }
.organization-actions { margin-top: 12px; }
.organization-private-note,.organization-footer { color: var(--muted); font-size: 13px; line-height: 1.65; margin: 12px 0 0; }
.organization-details { display: grid; grid-template-columns: minmax(120px,.4fr) minmax(0,1fr); gap: 10px 16px; margin: 0; }
.organization-details dt { color: var(--muted); }
.organization-details dd { margin: 0; overflow-wrap: anywhere; }
.organization-footer { margin: 0; }
@media (max-width: 600px) { .organization-panel,.organization-detail,.organization-create-panel,.organization-pending { padding: 14px; } .organization-create-grid { grid-template-columns: 1fr; gap: 11px; } .organization-details { grid-template-columns: 1fr; gap: 4px; } .organization-details dd { margin-bottom: 8px; } }
</style>
