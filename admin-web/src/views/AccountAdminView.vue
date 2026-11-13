<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { api, sessionToken } from '../api'
import type { Clinic, Identity } from '../api'
import {
  buildStaffAdminCreate, buildStaffAdminCredentialReset, buildStaffAdminGrant, buildStaffAdminRevoke, buildStaffAdminState,
  createStaffAdminOperationId, makeStaffAdminPending, preserveStaffAdminDetail, preserveStaffAdminList,
  preserveStaffAdminMutation, readStaffAdminPending, removeStaffAdminPending, replaceStaffAdminPending,
  staffAdminAccess, staffAdminDetailRequest, staffAdminFailureDisposition,
  staffAdminListRequest, staffAdminPendingKey, staffAdminRetryMutation, staffAdminScopeKey, writeStaffAdminPending,
} from '../staff-admin-core.js'
import { clearStaffAdminSecrets, createRequestLane, staffAdminCanApplyWriteDetail, staffAdminWriteContextMatches } from '../staff-admin-ui-coordination.js'

type Snapshot = {
  accountId: string; loginName: string; displayName: string; active: boolean; dentistId: string | null
  grants: Array<{ grantId: string; roleCode: string; scopeKind: string; clinicId: string | null }>
  mutationCount: string
}
type Pending = ReturnType<typeof makeStaffAdminPending>
type ActorContext = { disposed: boolean; accountId: string; token: string; scopeKey: string; actorGeneration: number }
type WriteContext = ActorContext & { writeGeneration: number }

const props = defineProps<{ identity: Identity; clinics: Clinic[] }>()
const emit = defineEmits<{ (event: 'identity-refresh'): void }>()
const access = computed(() => staffAdminAccess(props.identity))
const clinicFilter = ref(access.value.chain ? '' : access.value.clinicIds[0] ?? '')
const clinicOptions = computed(() => access.value.clinicIds.map((id) => ({ id, label: props.clinics.find((clinic) => clinic.clinic_id === id)?.name ?? `门店 #${id}` })))
const viewClinicId = computed(() => String(clinicFilter.value ?? '').trim() || null)
const scopeKey = computed(() => {
  try { return staffAdminScopeKey(access.value, viewClinicId.value) } catch { return 'no-authorized-scope' }
})
const items = ref<Snapshot[]>([])
const hasMore = ref(false)
const nextAfterId = ref<string | null>(null)
const selectedAccountId = ref('')
const detail = ref<Snapshot | null>(null)
const loadingList = ref(false)
const loadingDetail = ref(false)
const submitting = ref(false)
const listError = ref('')
const detailError = ref('')
const message = ref('')
const pendingEntries = ref<Pending[]>([])
const pendingError = ref('')
const actorGeneration = ref(0)
const disposed = ref(false)
const listLane = createRequestLane()
const detailLane = createRequestLane()
const writeLane = createRequestLane()
const retrySecrets = ref<Record<string, string>>({})
const roleOptions = ['前台', '牙医', '财务', '运营人员／店长', '总部管理', '账号管理']
const createForm = ref({ accountId: '', grantId: '', loginName: '', displayName: '', dentistId: '', roleCode: '前台', scopeKind: '门店', clinicId: viewClinicId.value ?? '', password: '' })
const grantForm = ref({ grantId: '', roleCode: '前台', scopeKind: '门店', clinicId: viewClinicId.value ?? '', reason: '' })
const reason = ref('')
const resetPassword = ref('')

function actorContext(): ActorContext {
  return { disposed: disposed.value, accountId: props.identity.accountId, token: sessionToken() ?? '', scopeKey: scopeKey.value, actorGeneration: actorGeneration.value }
}
function actorContextMatches(captured: ActorContext) {
  const current = actorContext()
  return !captured.disposed && !current.disposed && captured.accountId === current.accountId && captured.token === current.token
    && captured.scopeKey === current.scopeKey && captured.actorGeneration === current.actorGeneration
}
function writeContextMatches(captured: WriteContext) {
  return writeLane.isCurrent(captured.writeGeneration)
    && staffAdminWriteContextMatches(captured, { ...actorContext(), writeGeneration: captured.writeGeneration })
}
function clearSensitiveInputs() {
  clearStaffAdminSecrets(createForm, resetPassword, retrySecrets)
}
function pendingKeyFor(clinicId: string | null) {
  return staffAdminPendingKey(access.value, clinicId)
}
function readBucket(key: string): Pending[] {
  const safe = readStaffAdminPending(sessionStorage, key) as Pending[]
  const serialized = JSON.stringify(safe)
  if (sessionStorage.getItem(key) !== serialized) writeStaffAdminPending(sessionStorage, key, safe)
  return safe
}
function loadPending() {
  pendingError.value = ''
  try { pendingEntries.value = readBucket(pendingKeyFor(viewClinicId.value)) }
  catch { pendingEntries.value = []; pendingError.value = '本范围待核对记录无法安全读取；暂不提交新操作。' }
}
function savePending(key: string, pending: Pending, syncVisible = true) {
  const list = replaceStaffAdminPending(readBucket(key), pending) as Pending[]
  writeStaffAdminPending(sessionStorage, key, list)
  if (syncVisible && key === pendingKeyFor(viewClinicId.value)) pendingEntries.value = list
}
function dropPending(key: string, pending: Pending, syncVisible = true) {
  const list = removeStaffAdminPending(readBucket(key), pending) as Pending[]
  writeStaffAdminPending(sessionStorage, key, list)
  if (syncVisible && key === pendingKeyFor(viewClinicId.value)) pendingEntries.value = list
}
watch(scopeKey, () => {
  actorGeneration.value += 1
  listLane.invalidate(); detailLane.invalidate(); writeLane.invalidate()
  items.value = []; detail.value = null; selectedAccountId.value = ''; hasMore.value = false; nextAfterId.value = null
  loadingList.value = false; loadingDetail.value = false; submitting.value = false
  clearSensitiveInputs()
  if (!access.value.chain) { createForm.value.clinicId = viewClinicId.value ?? ''; grantForm.value.clinicId = viewClinicId.value ?? '' }
  listError.value = ''; detailError.value = ''; message.value = ''; loadPending()
}, { immediate: true, flush: 'sync' })
watch(selectedAccountId, () => {
  listLane.invalidate(); detailLane.invalidate()
  loadingList.value = false; loadingDetail.value = false
  clearSensitiveInputs()
  detail.value = null; detailError.value = ''; message.value = ''
}, { flush: 'sync' })
watch(() => props.identity, () => {
  actorGeneration.value += 1
  listLane.invalidate(); detailLane.invalidate(); writeLane.invalidate()
  clinicFilter.value = access.value.chain ? '' : access.value.clinicIds[0] ?? ''
  items.value = []; detail.value = null; selectedAccountId.value = ''; loadingList.value = false; loadingDetail.value = false; submitting.value = false; clearSensitiveInputs(); loadPending()
}, { flush: 'sync' })
onMounted(() => { void loadList(false) })
onUnmounted(() => { disposed.value = true; actorGeneration.value += 1; listLane.invalidate(); detailLane.invalidate(); writeLane.invalidate(); clearSensitiveInputs() })

async function loadList(append: boolean) {
  listError.value = ''
  let request
  try { request = staffAdminListRequest(access.value, { clinicId: viewClinicId.value, afterId: append ? nextAfterId.value : null, limit: 30 }) }
  catch (error) { listError.value = safeError(error); return }
  if (append && !request.afterId) return
  const captured = actorContext()
  const requestGeneration = listLane.begin()
  loadingList.value = true
  try {
    const raw = await api<unknown>(request.endpoint)
    const result = preserveStaffAdminList(raw, request)
    if (!actorContextMatches(captured) || !listLane.isCurrent(requestGeneration)) return
    items.value = append ? [...items.value, ...result.items as Snapshot[]] : result.items as Snapshot[]
    hasMore.value = result.hasMore
    nextAfterId.value = result.nextAfterId
    if (selectedAccountId.value && !items.value.some((item) => item.accountId === selectedAccountId.value)) {
      selectedAccountId.value = ''; detail.value = null
    }
  } catch (error) {
    if (actorContextMatches(captured) && listLane.isCurrent(requestGeneration)) listError.value = safeError(error)
  } finally {
    if (listLane.finish(requestGeneration)) loadingList.value = false
  }
}
async function loadDetail(accountId: string) {
  selectedAccountId.value = accountId
  detailError.value = ''
  const captured = actorContext()
  const requestGeneration = detailLane.begin()
  let request
  try { request = staffAdminDetailRequest(access.value, accountId, viewClinicId.value) }
  catch (error) { detailError.value = safeError(error); detailLane.finish(requestGeneration); return }
  loadingDetail.value = true
  try {
    const raw = await api<unknown>(request.endpoint)
    const result = preserveStaffAdminDetail(raw, request)
    if (!actorContextMatches(captured) || !detailLane.isCurrent(requestGeneration) || selectedAccountId.value !== accountId) return
    detail.value = result.account as Snapshot
  } catch (error) {
    if (actorContextMatches(captured) && detailLane.isCurrent(requestGeneration) && selectedAccountId.value === accountId) detailError.value = safeError(error)
  } finally {
    if (detailLane.finish(requestGeneration)) loadingDetail.value = false
  }
}
function safeError(error: unknown) {
  return error instanceof Error ? error.message : '请求失败。'
}
function pendingKeyForMutation(mutation: { scope: { clinicId: string | null } }) {
  return pendingKeyFor(mutation.scope.clinicId)
}
function scrubMutation(mutation: { body: Record<string, unknown> }) {
  if ('password' in mutation.body) mutation.body.password = ''
  if ('newPassword' in mutation.body) mutation.body.newPassword = ''
}
async function sendMutation(mutation: any) {
  const key = pendingKeyForMutation(mutation)
  const pending = makeStaffAdminPending(mutation, 'sending') as Pending
  const writeGeneration = writeLane.begin()
  const captured: WriteContext = { ...actorContext(), writeGeneration }
  submitting.value = true
  message.value = ''
  try {
    savePending(key, pending)
  } catch {
    message.value = '无法安全保存待核对信息；未发送请求。'
    scrubMutation(mutation)
    if (writeLane.finish(writeGeneration)) submitting.value = false
    return
  }
  try {
    const raw = await api<unknown>(mutation.endpoint, { method: mutation.method, body: mutation.body })
    const result = preserveStaffAdminMutation(raw, mutation)
    if (!writeContextMatches(captured)) {
      dropPending(key, pending, false)
      return
    }
    dropPending(key, pending)
    if (staffAdminCanApplyWriteDetail(result.account.accountId, selectedAccountId.value)) detail.value = result.account as Snapshot
    items.value = items.value.map((item) => item.accountId === result.account.accountId ? result.account as Snapshot : item)
    const targetLabel = `${result.account.displayName} · #${result.account.accountId}`
    message.value = result.replayed ? `服务端确认原操作已完成（回放）：${targetLabel}。` : `服务端已确认操作完成：${targetLabel}。`
    if (result.account.accountId === props.identity.accountId) emit('identity-refresh')
    await loadList(false)
  } catch (error) {
    const disposition = staffAdminFailureDisposition(error)
    if (disposition === 'unknown') {
      const current = writeContextMatches(captured)
      try { savePending(key, makeStaffAdminPending(mutation, 'unknown') as Pending, current) }
      catch { if (writeContextMatches(captured)) pendingError.value = '操作结果未知，且待核对记录未能保存；请联系连锁管理员人工核对。' }
      if (current) message.value = '操作结果未知。请先核对服务端状态；若重试，必须使用原操作身份并重新输入密码。'
    } else if (disposition === 'conflict') {
      const current = writeContextMatches(captured)
      try { savePending(key, makeStaffAdminPending(mutation, 'conflict') as Pending, current) } catch { /* keep response classification visible */ }
      if (current) {
        message.value = '记录已变化（409）。原操作未按旧快照重试，请重新读取后人工核对。'
        if (selectedAccountId.value === mutation.targetAccountId) await loadDetail(mutation.targetAccountId)
      }
    } else {
      try { dropPending(key, pending, writeContextMatches(captured)) } catch { /* known rejection does not become success */ }
      if (writeContextMatches(captured)) {
        const problem = error as { status?: number; code?: string }
        if (disposition === 'unauthorized') message.value = '会话已失效，操作未显示为成功。'
        else message.value = `服务端拒绝操作（HTTP ${Number(problem?.status ?? 0)}，${String(problem?.code ?? '请求无效')}）。`
      }
    }
  } finally {
    scrubMutation(mutation)
    if (writeLane.finish(writeGeneration)) submitting.value = false
  }
}
function runCreate() {
  try {
    const mutation = buildStaffAdminCreate(access.value, { ...createForm.value, viewClinicId: viewClinicId.value })
    void sendMutation(mutation)
  } catch (error) { message.value = safeError(error) }
  finally { createForm.value.password = '' }
}
function runGrant() {
  if (!detail.value) return
  try {
    const mutation = buildStaffAdminGrant(access.value, detail.value, { ...grantForm.value, viewClinicId: viewClinicId.value })
    void sendMutation(mutation)
    grantForm.value.reason = ''
  } catch (error) { message.value = safeError(error) }
}
function runRevoke(grantId: string) {
  if (!detail.value) return
  try {
    const operation = createStaffAdminOperationId()
    const mutation = buildStaffAdminRevoke(access.value, detail.value, grantId, operation, reason.value, viewClinicId.value)
    void sendMutation(mutation)
    reason.value = ''
  } catch (error) { message.value = safeError(error) }
}
function runState(active: boolean) {
  if (!detail.value) return
  try {
    const mutation = buildStaffAdminState(access.value, detail.value, active, createStaffAdminOperationId(), reason.value, viewClinicId.value)
    void sendMutation(mutation)
    reason.value = ''
  } catch (error) { message.value = safeError(error) }
}
function runReset() {
  if (!detail.value) return
  try {
    const mutation = buildStaffAdminCredentialReset(access.value, detail.value, resetPassword.value, createStaffAdminOperationId(), reason.value, viewClinicId.value)
    void sendMutation(mutation)
    resetPassword.value = ''; reason.value = ''
  } catch (error) { message.value = safeError(error); resetPassword.value = '' }
}
function retryKey(entry: Pending) { return `${entry.action}:${entry.targetAccountId}:${entry.operationId ?? ''}:${entry.grantId ?? ''}` }
function retry(entry: Pending) {
  const key = retryKey(entry)
  const secret = retrySecrets.value[key] ?? ''
  try {
    const mutation = staffAdminRetryMutation(access.value, entry, secret)
    delete retrySecrets.value[key]
    void sendMutation(mutation)
  } catch (error) {
    delete retrySecrets.value[key]
    message.value = safeError(error)
  } finally { retrySecrets.value[key] = '' }
}
function forgetPending(entry: Pending) {
  if (!window.confirm('已人工核对原操作状态？清除后不再保留该待核对记录。')) return
  try { dropPending(pendingKeyFor(entry.scope.clinicId), entry) }
  catch { pendingError.value = '待核对记录无法更新，未清除。' }
}
function clinicName(id: string | null) {
  if (id === null) return '连锁'
  return props.clinics.find((clinic) => clinic.clinic_id === id)?.name ?? `门店 #${id}`
}
function onRoleChanged() {
  if (createForm.value.roleCode === '总部管理') createForm.value.scopeKind = '连锁'
  if (createForm.value.scopeKind === '门店') createForm.value.clinicId = viewClinicId.value ?? ''
}
function onGrantRoleChanged() {
  if (grantForm.value.roleCode === '总部管理') grantForm.value.scopeKind = '连锁'
  if (grantForm.value.scopeKind === '门店') grantForm.value.clinicId = viewClinicId.value ?? ''
}
</script>

<template>
  <div class="account-admin-page">
    <header class="page-heading">
      <div><p class="eyebrow">员工工作台 · 账号治理</p><h1>账号管理</h1><p class="muted">办理员工账号治理及患者本人核验后的微信账号绑定。</p></div>
      <span class="account-admin-status"><span class="dot dot-green"></span>员工会话有效</span>
    </header>

    <section class="panel account-admin-identity">
      <div><p class="eyebrow">当前操作者</p><h2>员工账号 #{{ identity.accountId }}</h2><p class="muted">授权范围来自当前会话；提交仍由服务端复核。</p></div>
      <div v-if="access.chain" class="field account-admin-filter"><label for="admin-clinic-filter">查询门店（可选）</label><input id="admin-clinic-filter" v-model="clinicFilter" inputmode="numeric" placeholder="连锁管理员可输入现有门店ID" @change="loadList(false)"><small>留空时查询连锁范围；ID必须来自现有组织资料。</small></div>
      <div v-else class="field account-admin-filter"><label for="admin-clinic-select">管理门店</label><select id="admin-clinic-select" v-model="clinicFilter" @change="loadList(false)"><option v-for="clinic in clinicOptions" :key="clinic.id" :value="clinic.id">{{ clinic.label }} · #{{ clinic.id }}</option></select></div>
      <button class="button button-quiet" :disabled="loadingList" @click="loadList(false)">重新读取</button>
    </section>

    <div v-if="message" class="workspace-alert" role="status"><span>{{ message }}</span><button class="icon-button" aria-label="关闭提示" @click="message = ''">×</button></div>
    <div v-if="pendingError" class="workspace-alert" role="alert">{{ pendingError }}</div>
    <p v-if="listError" class="form-error" role="alert">{{ listError }}</p>

    <section v-if="!access.allowed" class="account-admin-setup" role="status"><span class="account-admin-setup-mark">!</span><div><h2>当前身份没有有效账号管理范围</h2><p>本页需要真实员工“账号管理”门店或连锁授权。其他岗位不会开放账号治理操作。</p></div></section>

    <section v-else class="account-admin-columns">
      <article class="panel account-admin-section">
        <div class="section-heading"><div><p class="eyebrow">服务端账号快照</p><h2>员工账号</h2></div><span class="muted">{{ items.length }} 条</span></div>
        <div v-if="loadingList && !items.length" class="muted">正在读取账号…</div>
        <div v-else-if="!items.length" class="account-admin-empty">当前范围没有返回员工账号。</div>
        <ul v-else class="account-admin-list">
          <li v-for="item in items" :key="item.accountId">
            <button class="account-admin-row" :class="{ selected: selectedAccountId === item.accountId }" @click="loadDetail(item.accountId)">
              <span><strong>{{ item.displayName }}</strong><small>{{ item.loginName }} · #{{ item.accountId }}</small></span><span class="account-admin-state" :class="{ inactive: !item.active }">{{ item.active ? '启用' : '停用' }}</span>
            </button>
          </li>
        </ul>
        <div class="account-admin-list-actions"><button v-if="hasMore" class="button button-quiet" :disabled="loadingList" @click="loadList(true)">继续加载</button><span v-if="loadingList" class="muted">读取中…</span></div>

        <form class="account-admin-create" @submit.prevent="runCreate">
          <h3>创建员工账号</h3><p class="muted">账号ID与初始授权ID由获权管理员从现有编号规则提供；密码只保留在表单与请求内存。</p>
          <div class="account-admin-form-grid">
            <label>员工账号ID<input v-model="createForm.accountId" inputmode="numeric" required></label>
            <label>初始授权ID<input v-model="createForm.grantId" inputmode="numeric" required></label>
            <label>登录名<input v-model="createForm.loginName" maxlength="64" required></label>
            <label>显示名<input v-model="createForm.displayName" maxlength="80" required></label>
            <label>牙医ID（仅连锁管理员）<input v-model="createForm.dentistId" inputmode="numeric" :disabled="!access.chain" placeholder="牙医角色需填写已有牙医ID"></label>
            <label>初始岗位<select v-model="createForm.roleCode" @change="onRoleChanged"><option v-for="role in roleOptions" :key="role" :value="role" :disabled="role === '总部管理' && !access.chain">{{ role }}</option></select></label>
            <label>范围<select v-model="createForm.scopeKind" :disabled="!access.chain || createForm.roleCode === '总部管理'" @change="onRoleChanged"><option value="门店">门店</option><option value="连锁">连锁</option></select></label>
            <label v-if="createForm.scopeKind === '门店'">授权门店ID<input v-model="createForm.clinicId" inputmode="numeric" :disabled="!access.chain" required></label>
            <label class="account-admin-secret">初始密码<input v-model="createForm.password" type="password" minlength="16" maxlength="256" autocomplete="new-password" required><small>16–256字符；结果未知时重放要求重新输入，若首次创建已完成，服务端不会以重放密码重置凭据。</small></label>
          </div>
          <button class="button button-primary" :disabled="submitting || !!pendingError">创建账号</button>
        </form>
      </article>

      <article class="panel account-admin-section account-admin-detail">
        <div class="section-heading"><div><p class="eyebrow">完整快照与治理动作</p><h2>账号详情</h2></div><button v-if="selectedAccountId" class="button button-quiet" :disabled="loadingDetail" @click="loadDetail(selectedAccountId)">重读详情</button></div>
        <p v-if="detailError" class="form-error" role="alert">{{ detailError }}</p>
        <p v-if="loadingDetail" class="muted">正在读取完整快照…</p>
        <div v-else-if="!detail" class="account-admin-empty">从左侧选择账号读取详情。</div>
        <template v-else>
          <div class="account-admin-profile"><strong>{{ detail.displayName }}</strong><span>{{ detail.loginName }} · #{{ detail.accountId }}</span><span>牙医ID：{{ detail.dentistId ?? '未绑定' }}</span><span>mutationCount：{{ detail.mutationCount }}</span><span :class="{ 'text-danger': !detail.active }">{{ detail.active ? '当前启用' : '当前停用' }}</span></div>
          <h3>当前有效授权</h3>
          <ul class="account-admin-grants">
            <li v-for="grant in detail.grants" :key="grant.grantId"><span><strong>{{ grant.roleCode }}</strong> · {{ grant.scopeKind }} · {{ clinicName(grant.clinicId) }} · grant #{{ grant.grantId }}</span><button class="button button-danger-quiet" :disabled="submitting || !reason.trim()" @click="runRevoke(grant.grantId)">撤销</button></li>
            <li v-if="!detail.grants.length" class="muted">当前范围没有有效授权。</li>
          </ul>
          <form class="account-admin-create" @submit.prevent="runGrant">
            <h3>新增岗位授权</h3><div class="account-admin-form-grid">
              <label>授权ID<input v-model="grantForm.grantId" inputmode="numeric" required></label>
              <label>岗位<select v-model="grantForm.roleCode" @change="onGrantRoleChanged"><option v-for="role in roleOptions" :key="role" :value="role" :disabled="role === '总部管理' && !access.chain">{{ role }}</option></select></label>
              <label>范围<select v-model="grantForm.scopeKind" :disabled="!access.chain || grantForm.roleCode === '总部管理'"><option value="门店">门店</option><option value="连锁">连锁</option></select></label>
              <label v-if="grantForm.scopeKind === '门店'">门店ID<input v-model="grantForm.clinicId" inputmode="numeric" :disabled="!access.chain" required></label>
              <label class="account-admin-wide">操作原因<input v-model="grantForm.reason" maxlength="1000" required></label>
            </div><button class="button button-primary" :disabled="submitting">新增授权</button>
          </form>

          <div class="account-admin-create">
            <h3>启停与凭据</h3>
            <p v-if="!access.chain" class="muted">门店范围快照无法证明目标账号的全部有效范围；全局启停和凭据重置仅开放给连锁账号管理员。</p>
            <label class="account-admin-reason">操作原因<input v-model="reason" maxlength="1000" required></label>
            <div class="account-admin-buttons"><button class="button button-quiet" :disabled="submitting || !access.chain || !reason.trim()" @click="runState(!detail.active)">{{ detail.active ? '全局停用账号' : '全局启用账号' }}</button></div>
            <label class="account-admin-secret">新密码<input v-model="resetPassword" type="password" minlength="16" maxlength="256" autocomplete="new-password" :disabled="!access.chain" placeholder="16–256字符"></label>
            <div class="account-admin-buttons"><button class="button button-danger-quiet" :disabled="submitting || !access.chain || !reason.trim() || resetPassword.length < 16" @click="runReset">重置凭据</button></div>
            <p class="muted">重置只发送新密码；不会读取、回显或保存现有/新密码。若结果未知，沿原操作ID重放不会再次更改密码；确认首次请求状态后再办理新重置。</p>
          </div>
        </template>
      </article>
    </section>

    <section v-if="pendingEntries.length" class="panel account-admin-section">
      <div class="section-heading"><div><p class="eyebrow">本操作者与当前范围</p><h2>待核对操作（{{ pendingEntries.length }}）</h2></div></div>
      <p class="muted">未知结果不会自动重试。冲突只能重读并由人员核对；重试沿用原目标、固定ID、快照和原因。密码需重新输入且只进入本次请求；服务端已执行的创建/重置在同ID回放时不会再次改变凭据。</p>
      <ul class="account-admin-pending">
        <li v-for="entry in pendingEntries" :key="retryKey(entry)">
          <div><strong>{{ entry.action }} · 账号 #{{ entry.targetAccountId }}</strong><span>状态：{{ entry.state === 'unknown' ? '结果未知' : entry.state === 'conflict' ? '版本冲突' : '正在发送' }} · 范围：{{ clinicName(entry.scope.clinicId) }}</span><small>操作ID：{{ entry.operationId ?? entry.grantId ?? entry.request.grantId }}</small></div>
          <div class="account-admin-pending-actions">
            <label v-if="entry.state === 'unknown' && (entry.action === 'create' || entry.action === 'reset')" class="account-admin-secret">重新输入密码<input v-model="retrySecrets[retryKey(entry)]" type="password" minlength="16" maxlength="256" autocomplete="new-password"></label>
            <button v-if="entry.state === 'unknown'" class="button button-quiet" :disabled="submitting || !!pendingError" @click="retry(entry)">沿原操作重试</button>
            <button class="button button-quiet" :disabled="submitting" @click="forgetPending(entry)">已核对，清除此记录</button>
          </div>
        </li>
      </ul>
    </section>
    <p class="account-admin-footer">组织资源与业务数据保持只读。没有组织查询接口时仅允许输入服务端契约要求的显式编号，不生成门店/牙医/排班选项。</p>
  </div>
</template>

<style scoped>
.account-admin-page { display: grid; gap: 18px; }
.account-admin-status { display: inline-flex; align-items: center; gap: 8px; align-self: start; border-radius: 999px; padding: 8px 12px; background: #e7f5ef; color: #216454; font-size: 12px; font-weight: 700; white-space: nowrap; }
.account-admin-identity { display: flex; justify-content: space-between; align-items: end; gap: 16px; padding: 20px; flex-wrap: wrap; }
.account-admin-identity h2,.account-admin-section h2,.account-admin-setup h2 { margin: 4px 0; }
.account-admin-filter { min-width: 220px; }
.account-admin-filter small,.account-admin-secret small { display: block; color: var(--muted); font-size: 12px; margin-top: 5px; }
.account-admin-columns { display: grid; grid-template-columns: minmax(300px,.9fr) minmax(360px,1.1fr); gap: 18px; align-items: start; }
.account-admin-section { padding: 20px; min-width: 0; }
.account-admin-list,.account-admin-grants,.account-admin-pending { list-style: none; padding: 0; margin: 0; display: grid; gap: 8px; }
.account-admin-row { width: 100%; border: 1px solid var(--line); border-radius: 10px; padding: 12px; background: white; display: flex; justify-content: space-between; gap: 10px; align-items: center; text-align: left; cursor: pointer; color: var(--ink); }
.account-admin-row.selected { border-color: #367a68; box-shadow: 0 0 0 2px #367a6820; }
.account-admin-row span:first-child { display: grid; gap: 4px; min-width: 0; }
.account-admin-row small,.account-admin-profile span,.account-admin-pending small { color: var(--muted); overflow-wrap: anywhere; }
.account-admin-state { background: #e7f5ef; color: #216454; border-radius: 99px; padding: 4px 8px; font-size: 12px; white-space: nowrap; }
.account-admin-state.inactive { background: #fbeaea; color: #a44242; }
.account-admin-list-actions,.account-admin-buttons { display: flex; gap: 8px; align-items: center; margin-top: 10px; flex-wrap: wrap; }
.account-admin-empty { padding: 20px 0; color: var(--muted); }
.account-admin-create { border-top: 1px solid var(--line); margin-top: 18px; padding-top: 18px; display: grid; gap: 12px; }
.account-admin-create h3,.account-admin-detail h3 { margin: 0; }
.account-admin-form-grid { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 10px; }
.account-admin-form-grid label,.account-admin-secret,.account-admin-reason { display: grid; gap: 6px; font-size: 13px; color: var(--muted); }
.account-admin-form-grid input,.account-admin-form-grid select,.account-admin-secret input,.account-admin-reason input { width: 100%; min-width: 0; }
.account-admin-wide { grid-column: 1 / -1; }
.account-admin-profile { display: grid; gap: 6px; padding: 14px; border-radius: 10px; background: #f7faf9; margin-bottom: 16px; }
.account-admin-grants { margin: 10px 0 18px; }
.account-admin-grants li,.account-admin-pending li { display: flex; justify-content: space-between; align-items: center; gap: 12px; border: 1px solid var(--line); border-radius: 10px; padding: 11px; }
.account-admin-grants li span { overflow-wrap: anywhere; }
.account-admin-pending li { align-items: flex-start; }
.account-admin-pending li > div:first-child { display: grid; gap: 5px; min-width: 0; }
.account-admin-pending-actions { display: grid; gap: 8px; min-width: 190px; }
.account-admin-setup { display: flex; align-items: flex-start; gap: 14px; border: 1px solid #d8e6e2; border-radius: 14px; padding: 22px; background: #f1f7f5; }
.account-admin-setup-mark { display: grid; flex: none; place-items: center; width: 30px; height: 30px; border-radius: 50%; color: #fff; background: #367a68; font-weight: 800; }
.account-admin-setup p,.account-admin-footer { margin: 7px 0 0; color: var(--muted); font-size: 13px; line-height: 1.7; }
.account-admin-footer { margin: 0; }
.text-danger { color: #a44242; }
.form-error { color: #a44242; }
@media (max-width: 920px) { .account-admin-columns { grid-template-columns: 1fr; } }
@media (max-width: 560px) { .account-admin-identity { align-items: stretch; flex-direction: column; } .account-admin-form-grid { grid-template-columns: 1fr; } .account-admin-pending li,.account-admin-grants li { align-items: stretch; flex-direction: column; } .account-admin-pending-actions { min-width: 0; } }
</style>
