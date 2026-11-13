<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { api, operationId, sessionToken, formatShanghai } from '../api'
import type { ApiProblem, Identity } from '../api'

const props = defineProps<{ identity: Identity }>()
const allowed = computed(() => props.identity.kind === '员工' && props.identity.grants.some(g => g.role_code === '账号管理' && g.scope_kind === '连锁'))
type Patient = { patient_id: string; patient_no: string; name: string; active: boolean; account_id: string | null }
type Detail = { accountId: string; patientId: string; patientNo: string; name: string; active: boolean; generation: string; bound: boolean }
type Pending = { actor: string; account: string; kind: string; path: string; body: Record<string, unknown>; codeDigest: string }
const query = ref(''), patients = ref<Patient[]>([]), selected = ref<Patient | null>(null), detail = ref<Detail | null>(null)
const code = ref(''), evidence = ref(''), confirmed = ref(false), busy = ref(false), error = ref(''), message = ref(''), codeExpiry = ref('')
const pending = ref<Pending | null>(null)
let generation = 0, disposed = false
const key = () => `adc.patient-wechat.pending:${props.identity.accountId}`
function context() { return { generation, token: sessionToken(), actor: props.identity.accountId } }
function current(c: ReturnType<typeof context>) { return !disposed && c.generation === generation && c.token === sessionToken() && c.actor === props.identity.accountId && allowed.value }
async function digest(value: string) { return [...new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value)))].map(x => x.toString(16).padStart(2, '0')).join('') }
function reset() {
  generation++; code.value = ''; evidence.value = ''; confirmed.value = false; codeExpiry.value = ''; detail.value = null; selected.value = null; patients.value = []; busy.value = false; error.value = ''; message.value = ''
  try { const raw = sessionStorage.getItem(key()); pending.value = raw ? JSON.parse(raw) : null; if (pending.value?.actor !== props.identity.accountId) pending.value = null } catch { pending.value = null; error.value = '待核对操作记录无法读取，请联系管理员核对后再办理。' }
}
watch(() => JSON.stringify([props.identity.accountId, props.identity.grants]), reset, { immediate: true })
watch(code, () => { codeExpiry.value = '' })
onUnmounted(() => { disposed = true; generation++; code.value = ''; evidence.value = '' })
async function search() {
  if (busy.value || !allowed.value || pending.value) return
  generation++
  const c = context(); busy.value = true; error.value = ''; selected.value = null; detail.value = null
  try { const rows = await api<Patient[]>(`/staff-admin/patient-wechat/patients?q=${encodeURIComponent(query.value.trim())}`); if (current(c)) patients.value = rows }
  catch (e) { if (current(c)) error.value = e instanceof Error ? e.message : '检索失败' }
  finally { if (current(c)) busy.value = false }
}
async function choose(patient: Patient) {
  if (busy.value || pending.value) return
  generation++
  selected.value = patient; detail.value = null; confirmed.value = false; code.value = ''; codeExpiry.value = ''
  if (!patient.account_id) return
  await readDetail(patient.account_id)
}
async function readDetail(account: string) {
  const c = context(); busy.value = true
  try { const result = await api<Detail>(`/staff-admin/patient-wechat/accounts/${account}`); if (current(c)) detail.value = result }
  catch (e) { if (current(c)) error.value = e instanceof Error ? e.message : '读取失败' }
  finally { if (current(c)) busy.value = false }
}
async function checkCode() {
  if (busy.value || !/^[A-Za-z0-9_-]{22}$/.test(code.value)) return
  const c = context(); busy.value = true; error.value = ''
  try { const result = await api<{ expiresAt: string }>('/staff-admin/patient-wechat/requests/lookup', { method: 'POST', body: { bindingCode: code.value } }); if (current(c)) codeExpiry.value = result.expiresAt }
  catch (e) { if (current(c)) error.value = e instanceof Error ? e.message : '核验码无效' }
  finally { if (current(c)) busy.value = false }
}
function save(value: Pending) { sessionStorage.setItem(key(), JSON.stringify(value)); pending.value = value }
function clear() { sessionStorage.removeItem(key()); pending.value = null }
async function submit(kind: string) {
  if (busy.value || pending.value || !allowed.value || !selected.value || !confirmed.value || !/^[A-Za-z0-9_.:/-]{1,80}$/.test(evidence.value)) return
  const c = context(), operation = operationId('WX').split('-')[1]!
  const account = kind === 'open' ? operationId('PA').split('-')[1]! : detail.value?.accountId
  if (!account) return
  let path: string, body: Record<string, unknown>, codeDigest = ''
  if (kind === 'open') { path = '/staff-admin/patient-wechat/accounts'; body = { accountId: account, patientId: selected.value.patient_id, operationId: operation, evidenceReference: evidence.value, confirmedInPerson: true } }
  else {
    path = `/staff-admin/patient-wechat/accounts/${account}/${kind === 'bind' ? 'binding' : 'unbinding'}`
    body = { operationId: operation, expectedGeneration: detail.value!.generation, evidenceReference: evidence.value }
    if (kind === 'bind') { if (!codeExpiry.value || Date.parse(codeExpiry.value) <= Date.now()) return; body.confirmedInPerson = true; codeDigest = await digest(code.value) }
    else body.confirmed = true
  }
  if (!current(c) || busy.value || pending.value) return
  // The code itself never enters storage. Keep the original operation and non-secret fields for recovery.
  const original: Pending = { actor: c.actor, account, kind, path, body, codeDigest }
  try { save(original) } catch { error.value = '无法保存待核对操作，尚未提交。'; return }
  await send(original)
}
async function send(original: Pending) {
  if (busy.value) return
  const c = context(); busy.value = true; error.value = ''; message.value = ''
  try {
    const body = { ...original.body }
    if (original.kind === 'bind') {
      if (await digest(code.value) !== original.codeDigest) throw new Error('请重新输入原核验码，只能重试同一请求。')
      body.bindingCode = code.value
    }
    await api(original.path, { method: 'POST', body })
    if (!current(c)) return
    clear(); code.value = ''; codeExpiry.value = ''; confirmed.value = false
    message.value = '服务端已确认操作。绑定后请患者重新点击微信登录。'
    busy.value = false; await readDetail(original.account)
  } catch (e) {
    if (!current(c)) return
    const problem = e as ApiProblem
    if (problem.status && problem.status < 500 && problem.code !== 'IDEMPOTENCY_CONFLICT') clear()
    error.value = pending.value ? '结果待核对，请读取原操作回执或重试原请求，不要另建操作。' : (e instanceof Error ? e.message : '操作未完成')
  } finally { if (current(c)) busy.value = false }
}
async function reconcile() {
  if (busy.value || !pending.value) return
  const original = pending.value, c = context(); busy.value = true; error.value = ''
  try {
    const result = await api<{ found: boolean }>(`/staff-admin/patient-wechat/accounts/${original.account}/operations/${original.body.operationId}`)
    if (!current(c)) return
    if (!result.found) { message.value = '尚未读到确认回执，请保留原操作。可重试原请求；绑定时需重新输入原核验码。'; return }
    clear(); code.value = ''; confirmed.value = false; message.value = '已读到原操作确认回执；下方重新读取当前账号状态。'; busy.value = false; await readDetail(original.account)
  } catch (e) { if (current(c)) error.value = e instanceof Error ? e.message : '核对失败' }
  finally { if (current(c)) busy.value = false }
}
</script>

<template>
  <section v-if="allowed" class="panel account-admin-section wechat-binding">
    <h2>患者微信核验绑定</h2>
    <p class="muted">仅由获权员工在患者本人到店核验后办理。核验码只证明当次微信操作，不替代患者身份核验；不按手机号自动关联。</p>
    <div v-if="pending" class="workspace-alert" role="status">
      <p>有一笔患者账号操作待核对，已保留原操作号。请先核对结果。</p>
      <label v-if="pending.kind === 'bind'">原核验码<input v-model="code" maxlength="22" autocomplete="off"></label>
      <button class="button button-quiet" :disabled="busy" @click="reconcile">读取原操作回执</button>
      <button class="button button-quiet" :disabled="busy" @click="send(pending)">重试原请求</button>
    </div>
    <form @submit.prevent="search"><label>患者编号或姓名（精确检索）<input v-model="query" maxlength="80" :disabled="busy || !!pending" required></label><button class="button button-quiet" :disabled="busy || !!pending">检索既有档案</button></form>
    <ul class="account-admin-list"><li v-for="patient in patients" :key="patient.patient_id"><button class="account-admin-row" :disabled="busy || !!pending" @click="choose(patient)"><span>{{ patient.name }} · {{ patient.patient_no }}</span><small>{{ patient.account_id ? '已有自助账号' : '未开通自助账号' }}</small></button></li></ul>
    <div v-if="selected || detail">
      <h3>{{ detail?.name || selected?.name }} · {{ detail?.patientNo || selected?.patient_no }}</h3>
      <p v-if="detail">{{ detail.active ? '账号与患者启用' : '账号或患者已停用' }} · {{ detail.bound ? '微信已绑定' : '微信未绑定' }}</p>
      <label>核验依据编号<input v-model="evidence" maxlength="80" autocomplete="off" placeholder="填写门店核验记录编号，不填证件或病历"></label>
      <label><input v-model="confirmed" type="checkbox">我已当面核验患者本人及所选档案；解绑时已核对原因与授权。</label>
      <template v-if="detail && !detail.bound">
        <label>患者当场出示的核验码<input v-model="code" maxlength="22" autocomplete="off"></label>
        <button class="button button-quiet" :disabled="busy || !!pending" @click="checkCode">核对核验码</button><p v-if="codeExpiry" class="muted">核验码有效至 {{ formatShanghai(codeExpiry) }}</p>
      </template>
      <div class="account-admin-list-actions">
        <button data-readonly-write v-if="selected && !selected.account_id && !detail" class="button button-primary" :disabled="busy || !!pending || !confirmed || !selected.active" @click="submit('open')">核验后开通患者自助账号</button>
        <button data-readonly-write v-if="detail && !detail.bound" class="button button-primary" :disabled="busy || !!pending || !confirmed || !codeExpiry || !detail.active" @click="submit('bind')">确认绑定到上述患者</button>
        <button data-readonly-write v-if="detail?.bound" class="button button-danger" :disabled="busy || !!pending || !confirmed" @click="submit('unbind')">确认解除微信绑定</button>
      </div>
    </div>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p><p v-if="message" role="status">{{ message }}</p>
  </section>
</template>

<style scoped>
.wechat-binding { margin-bottom: 24px; }
.wechat-binding form { display:flex; gap:12px; align-items:end; flex-wrap:wrap; }
.wechat-binding label { display:block; margin:12px 0; }
.wechat-binding input:not([type=checkbox]) { display:block; width:min(100%,480px); padding:10px; border:1px solid #d9e3df; border-radius:8px; }
</style>
