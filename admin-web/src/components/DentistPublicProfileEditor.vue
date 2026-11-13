<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, shallowRef, watch } from 'vue'
import { api, sessionToken } from '../api'
import type { Identity } from '../api'
import { generatePositive63BitId } from '../followup-core.js'
import { preservePublicProfile, preservePublicProfileReceipt, publicProfileBody, parsePublicProfileMarker, publicProfileContextMatches } from '../dentist-public-profile-core.js'
import type { PublicProfile, PublicProfileBody, PublicProfileMarker } from '../dentist-public-profile-core.js'

const props = defineProps<{ identity: Identity; dentist: { dentistId: string; name: string; active: boolean } }>()
const KEY = 'adc.dentist-public-profile.pending.v1'
const grantKey = computed(() => JSON.stringify(props.identity.grants))
const allowed = computed(() => props.identity.kind === '员工' && props.identity.grants.some((g) => g.role_code === '运营人员／店长' && g.scope_kind === '连锁'))
const snapshot = ref<PublicProfile | null>(null)
const observed = ref<PublicProfile | null>(null)
const form = ref({ specialtyLabel: '', professionalTitle: '', introduction: '', expertise: '', published: false })
const pending = ref<PublicProfileMarker | null>(null)
const storageBlocked = ref(false)
const busy = ref(false)
const reading = ref(false)
const inspected = ref(false)
const manualOperationId = ref('')
const message = ref('')
const error = ref('')
const exactBody = shallowRef<PublicProfileBody | null>(null)
let generation = 0
let readTicket = 0
let writeTicket = 0
let disposed = false
const sameOwner = computed(() => pending.value?.actorAccountId === props.identity.accountId && pending.value?.dentistId === props.dentist.dentistId)
const canEdit = computed(() => allowed.value && !busy.value && !reading.value && !storageBlocked.value && !pending.value && !!snapshot.value)

function clearForm() { form.value = { specialtyLabel: '', professionalTitle: '', introduction: '', expertise: '', published: false } }
function fill(profile: PublicProfile) { form.value = { specialtyLabel: profile.specialtyLabel, professionalTitle: profile.professionalTitle, introduction: profile.introduction, expertise: profile.expertise, published: profile.published } }
function context() { return { accountId: props.identity.accountId, token: sessionToken() ?? '', grantKey: grantKey.value, dentistId: props.dentist.dentistId, generation } }
function current(c: ReturnType<typeof context>) { return !disposed && allowed.value && publicProfileContextMatches(c, context()) }
function persist(marker: PublicProfileMarker | null) {
  try { if (marker) sessionStorage.setItem(KEY, JSON.stringify(marker)); else sessionStorage.removeItem(KEY);pending.value = marker;return true }
  catch {storageBlocked.value = true;error.value = '无法安全保存待核对操作标识；已停止提交。';return false}
}
function demoteSending() { if (pending.value?.state === 'sending') persist({ ...pending.value, state: 'unknown' }) }
function invalidate() {
  demoteSending();generation++;readTicket++;writeTicket++;busy.value = false;reading.value = false;inspected.value = false;exactBody.value = null
  snapshot.value = null;observed.value = null;clearForm();manualOperationId.value = '';message.value = '';error.value = ''
}
function restore() {
  try {
    const saved = parsePublicProfileMarker(sessionStorage.getItem(KEY))
    pending.value = saved
    if (saved?.state === 'sending') persist({ ...saved, state: 'unknown' })
    if (saved) message.value = '上次操作结果待核对；刷新后未保存原请求内容，不能生成新操作或盲目重试。'
  } catch {storageBlocked.value = true;error.value = '待核对标识无法解析或读取。请人工核对原操作，当前编辑已暂停。'}
}
watch(() => [props.identity.accountId, grantKey.value, props.dentist.dentistId], () => {invalidate();restore();if (allowed.value) void readCurrent()}, { flush: 'sync' })
onMounted(() => {restore();if (allowed.value) void readCurrent()})
onUnmounted(() => {invalidate();disposed = true})

async function readCurrent() {
  if (!allowed.value || busy.value || reading.value) return
  const c = context();const ticket = ++readTicket;reading.value = true;error.value = '';inspected.value = false
  try {
    const profile = preservePublicProfile(await api<unknown>(`/staff/dentists/${props.dentist.dentistId}/public-profile`), props.dentist.dentistId)
    if (!current(c) || ticket !== readTicket) return
    observed.value = profile;inspected.value = true
    if (!pending.value) {snapshot.value = profile;fill(profile);message.value = profile.exists ? (profile.published ? '资料已公开。' : '资料已保存，尚未公开。') : '尚未填写公开资料。'}
    else message.value = `已读取当前资料（版本 ${profile.rowVersion}）。这不能证明原操作 #${pending.value.operationId} 成功或失败；请核对原操作。`
  } catch {
    if (current(c) && ticket === readTicket) error.value = '公开资料读取失败；编辑仍保持暂停，请重试读取。'
  } finally {if (current(c) && ticket === readTicket) reading.value = false}
}
function stateAfterFailure(problem: unknown): PublicProfileMarker['state'] {
  const status = typeof problem === 'object' && problem && 'status' in problem ? Number((problem as { status?: unknown }).status) : NaN
  return status === 409 || status === 400 || status === 403 ? 'conflict' : 'unknown'
}
async function send(body: PublicProfileBody, marker: PublicProfileMarker) {
  const c = context();const ticket = ++writeTicket;busy.value = true;error.value = '';message.value = ''
  try {
    const receipt = preservePublicProfileReceipt(await api<unknown>(`/staff/dentists/${marker.dentistId}/public-profile`, { method: 'PUT', body }), marker.dentistId)
    if (!current(c) || ticket !== writeTicket || pending.value?.operationId !== marker.operationId) return
    if (!persist(null)) return
    exactBody.value = null;inspected.value = false;observed.value = receipt.resource;snapshot.value = receipt.resource;fill(receipt.resource)
    message.value = receipt.replayed ? `原操作 #${marker.operationId} 已由服务端精确重放。` : `公开资料已保存，版本 ${receipt.resource.rowVersion}。`
  } catch (problem) {
    if (!current(c) || ticket !== writeTicket || pending.value?.operationId !== marker.operationId) return
    const state = stateAfterFailure(problem);persist({ ...marker, state })
    if (state === 'conflict') exactBody.value = null
    error.value = state === 'unknown'
      ? `操作 #${marker.operationId} 的结果未知。先读取当前资料；若仍在本页且原请求内容未改，才可沿原号精确重试。`
      : `操作 #${marker.operationId} 被拒绝或版本冲突。先读取当前资料并人工核对；不会用新内容覆盖。`
  } finally {if (current(c) && ticket === writeTicket) busy.value = false}
}
async function save() {
  if (!canEdit.value || !snapshot.value) return
  let body: PublicProfileBody
  try {body = publicProfileBody(form.value, snapshot.value, generatePositive63BitId())}
  catch (problem) {error.value = problem instanceof Error ? problem.message : '公开资料输入无效。';return}
  const marker: PublicProfileMarker = { actorAccountId: props.identity.accountId, dentistId: props.dentist.dentistId, operationId: body.operationId, state: 'sending' }
  if (!persist(marker)) return
  exactBody.value = body;inspected.value = false;await send(body, marker)
}
async function retryExact() {
  const marker = pending.value
  if (!marker || marker.state !== 'unknown' || !sameOwner.value || !inspected.value || !exactBody.value || busy.value || reading.value || !allowed.value || storageBlocked.value) return
  if (exactBody.value.operationId !== marker.operationId) return
  if (!persist({ ...marker, state: 'sending' })) return
  await send(exactBody.value, marker)
}
function clearAfterManualReview() {
  const marker = pending.value
  if (!marker || !sameOwner.value || !inspected.value || manualOperationId.value !== marker.operationId || busy.value || reading.value || !observed.value) return
  if (!persist(null)) return
  exactBody.value = null;snapshot.value = observed.value;fill(observed.value);manualOperationId.value = ''
  message.value = '已按人工核对清除此机待办标识。当前资料已重新载入；这不代表原操作已成功。'
}
</script>

<template>
  <section class="profile-editor" aria-label="牙医公开资料编辑">
    <div class="profile-heading"><div><p class="profile-eyebrow">连锁运营 · 对外展示</p><h3>{{ dentist.name }} · 公开资料</h3></div><span>{{ snapshot ? (snapshot.exists ? (snapshot.published ? '已公开' : '未公开') : '未填写') : '待读取' }}</span></div>
    <p class="profile-note">这里只编辑公开专业、职称、简介和擅长。当前没有已审核的本站头像，继续显示默认头像；不会读取牙医生日、住址或电话。</p>
    <p v-if="!allowed" class="profile-error" role="alert">仅当前连锁运营人员可编辑公开资料。</p>
    <p v-if="storageBlocked" class="profile-error" role="alert">待核对标识无法安全读取或保存，编辑已暂停，请先人工核对原操作。</p>
    <p v-if="error" class="profile-error" role="alert">{{ error }}</p><p v-if="message" class="profile-note" role="status">{{ message }}</p>
    <div class="profile-actions"><button class="button button-quiet" type="button" :disabled="!allowed || reading || busy" @click="readCurrent">{{ reading ? '读取中…' : '读取当前公开资料' }}</button></div>
    <div v-if="pending" class="profile-pending">
      <strong>原操作 #{{ pending.operationId }} · 牙医 #{{ pending.dentistId }}</strong>
      <p v-if="!sameOwner">该待办属于其他账号或牙医。请回到原账号与目标核对；当前不可新建操作。</p>
      <p v-else>当前资料即使与表单相同，也不是原操作回执。刷新后原完整请求不会保存在浏览器。</p>
      <div class="profile-actions" v-if="sameOwner"><button class="button button-quiet" type="button" :disabled="pending.state !== 'unknown' || !inspected || !exactBody || busy || reading" @click="retryExact">沿原操作ID及原内容重试</button></div>
      <label v-if="sameOwner && inspected" class="profile-field"><span>人工核对后输入原操作ID以清除本机待办</span><input v-model="manualOperationId" :disabled="busy || reading" inputmode="numeric" autocomplete="off" placeholder="原操作ID"></label>
      <button v-if="sameOwner && inspected" class="button button-quiet" type="button" :disabled="manualOperationId !== pending.operationId || busy || reading" @click="clearAfterManualReview">已人工核对，清除本机待办</button>
    </div>
    <form v-if="snapshot && !pending" class="profile-form" @submit.prevent="save">
      <fieldset :disabled="!canEdit"><label class="profile-field"><span>专业</span><input v-model="form.specialtyLabel" maxlength="80" autocomplete="off" placeholder="录入真实专业"></label>
        <label class="profile-field"><span>职称</span><input v-model="form.professionalTitle" maxlength="80" autocomplete="off" placeholder="录入真实职称"></label>
        <label class="profile-field"><span>简介</span><textarea v-model="form.introduction" maxlength="2000" rows="4" placeholder="录入已核实的公开简介"></textarea></label>
        <label class="profile-field"><span>擅长</span><textarea v-model="form.expertise" maxlength="2000" rows="4" placeholder="录入已核实的擅长内容"></textarea></label>
        <label class="profile-switch"><input v-model="form.published" type="checkbox"><span>公布这份真实资料</span></label>
        <p class="profile-note">当前版本 {{ snapshot.rowVersion }}；公布前四项必须填写。头像保持空值。</p>
        <button data-readonly-write class="button" type="submit" :disabled="!canEdit">{{ busy ? '保存中…' : '保存公开资料' }}</button></fieldset>
    </form>
  </section>
</template>

<style scoped>
.profile-editor{margin-top:18px;padding:18px;border:1px solid var(--line);border-radius:14px;background:#fff;display:grid;gap:12px}
.profile-heading{display:flex;justify-content:space-between;align-items:center;gap:12px}.profile-heading h3{margin:0;font-size:18px}.profile-heading span{font-size:12px;color:var(--muted)}
.profile-eyebrow{margin:0 0 4px;color:#216454;font-size:11px;font-weight:700;letter-spacing:.08em}.profile-note{margin:0;color:var(--muted);font-size:13px;line-height:1.55}.profile-error{margin:0;color:#a22;font-size:13px}.profile-pending{display:grid;gap:10px;padding:14px;background:#fff8ed;border-radius:10px;font-size:13px}
.profile-pending p{margin:0}.profile-actions{display:flex;gap:8px;flex-wrap:wrap}.profile-form fieldset{border:0;padding:0;margin:0;display:grid;gap:12px}.profile-field{display:grid;gap:6px;font-size:13px;font-weight:650}.profile-field input,.profile-field textarea{width:100%;box-sizing:border-box;border:1px solid var(--line);border-radius:9px;padding:10px;background:#fff;color:var(--ink);font:inherit;font-weight:400}.profile-switch{display:flex;gap:8px;align-items:center;font-size:13px}
</style>
