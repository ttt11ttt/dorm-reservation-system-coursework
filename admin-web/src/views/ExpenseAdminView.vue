<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { api, formatShanghai, operationId, sessionToken } from '../api'
import ExpenseAdjustments from '../components/ExpenseAdjustments.vue'
import type { Clinic, Identity } from '../api'
import { loadExpensePendingWrites, type PendingWrite } from '../fulfillment-core.js'
import { createExpenseWriteQueue } from '../fulfillment-write-queue.js'
import { assertExpenseList, assertExpenseResponse, canConfirmExpense, canReadExpenses, canWriteExpenses, expenseAllocationsMatchTotal, expenseListEndpoint, expenseMoneyCents, expensePendingScope, makeExpenseAllocation, makeExpenseConfirmationPayload, makeExpenseCreationPayload } from '../expense-core.js'
import type { ExpenseEnvelope, ExpenseSource, ExpenseSnapshot } from '../expense-core.js'
import { createScopedBusyState } from '../reminder-core.js'

type Row = { allocationId: string; dentistId: string; treatmentId: string; amount: string; allocationBasis: string }
const props = defineProps<{ clinics: Clinic[]; identity: Identity }>()
const authorizedClinics = computed(() => props.clinics.filter((item) => canReadExpenses(props.identity, item.clinic_id)))
const clinicId = ref(authorizedClinics.value[0]?.clinic_id ?? '')
const statusFilter = ref('')
const items = ref<ExpenseSource[]>([])
const selected = ref<ExpenseEnvelope | null>(null)
const pendingWrites = ref<PendingWrite[]>([])
const loading = ref(false)
const busy = ref(false)
const storageReadable = ref(true)
const listError = ref('')
const detailError = ref('')
const writeError = ref('')
const notice = ref('')
const hasMore = ref(false)
const nextAfterId = ref<string | null>(null)
const expenseId = ref(operationId('EXP').slice(4))
const documentNo = ref('')
const lineNo = ref('')
const kind = ref<'医疗用品' | '运营费'>('医疗用品')
const occurredOn = ref('')
const amount = ref('')
const registrationReason = ref('')
const allocationDrafts = ref<Row[]>([])
const confirmationReason = ref('')
let generation = 0
let listRevision = 0
let disposed = false
const activity = createScopedBusyState(scopeKey())
const canWrite = computed(() => canWriteExpenses(props.identity, clinicId.value))
const selectedClinicName = computed(() => authorizedClinics.value.find((item) => item.clinic_id === clinicId.value)?.name ?? `门店 #${clinicId.value}`)
const canEditAllocations = computed(() => !!selected.value && canWrite.value && selected.value.expense.adjustsExpenseId === null && selected.value.expense.status === '待分摊' && selected.value.allocations.length === 0)
const canConfirmExpenseSnapshot = computed(() => !!selected.value && (canEditAllocations.value || canConfirmExpense(selected.value.snapshot)))
const canConfirm = computed(() => !!selected.value && canWrite.value && selected.value.expense.adjustsExpenseId === null && ['待分摊', '待确认'].includes(selected.value.expense.status) && selected.value.expense.confirmedBy === null && selected.value.expense.confirmedAt === null && (selected.value.allocations.length > 0 || allocationDrafts.value.length > 0))
const draftTotal = computed(() => {
  try { return allocationDrafts.value.reduce((sum, row) => sum + expenseMoneyCents(row.amount, '归属金额'), 0n) }
  catch { return null }
})
const totalMatches = computed(() => {
  if (!selected.value) return false
  try {
    const rows = selected.value.allocations.length ? selected.value.allocations : allocationDrafts.value.map((row) => makeExpenseAllocation({ ...row, treatmentId: row.treatmentId || null }))
    return expenseAllocationsMatchTotal(selected.value.expense.amount, rows)
  } catch { return false }
})
const queue = createExpenseWriteQueue(() => window.sessionStorage, async (record) => {
  const response = await api<unknown>(record.endpoint, { method: record.method, body: record.payload })
  const expected = record.action === 'expense-create'
    ? { creation: record.payload }
    : { confirmation: record.payload }
  return assertExpenseResponse(record.sourceClinicId, record.operationId, record.accountId, response, expected)
})

onMounted(() => { loadPending(); void loadFirstPage() })
onUnmounted(() => { disposed = true; generation += 1; activity.reset(`disposed:${scopeKey()}`); loading.value = false; busy.value = false })
watch(authorizedClinics, (list) => { if (!list.some((item) => item.clinic_id === clinicId.value)) clinicId.value = list[0]?.clinic_id ?? '' })
watch([clinicId, () => props.identity.accountId], () => {
  generation += 1; listRevision += 1; activity.reset(scopeKey()); loading.value = false; busy.value = false
  items.value = []; selected.value = null; allocationDrafts.value = []; pendingWrites.value = []; hasMore.value = false; nextAfterId.value = null
  listError.value = ''; detailError.value = ''; writeError.value = ''; notice.value = ''; storageReadable.value = true
  resetCreationForm(); loadPending(); if (clinicId.value) void loadFirstPage()
})
watch(statusFilter, () => { listRevision += 1; loading.value = false; if (clinicId.value) void loadFirstPage() })

function scopeKey() { return `${props.identity.accountId}:${clinicId.value}` }
function current(token: string | null, accountId: string, clinic: string, revision: number, write = false) {
  return !disposed && generation === revision && token === sessionToken() && accountId === props.identity.accountId && clinic === clinicId.value && canReadExpenses(props.identity, clinic) && (!write || canWriteExpenses(props.identity, clinic))
}
function resetCreationForm() {
  expenseId.value = operationId('EXP').slice(4); documentNo.value = ''; lineNo.value = ''; kind.value = '医疗用品'
  occurredOn.value = ''; amount.value = ''; registrationReason.value = ''
}
function formatCents(value: bigint | null) { return value === null ? '输入未完成' : `${value / 100n}.${String(value % 100n).padStart(2, '0')}` }
function loadPending() {
  if (!clinicId.value || !authorizedClinics.value.some((item) => item.clinic_id === clinicId.value)) { pendingWrites.value = []; storageReadable.value = true; return }
  try {
    pendingWrites.value = loadExpensePendingWrites(window.sessionStorage, { accountId: props.identity.accountId, sourceClinicId: clinicId.value, appointmentId: `clinic:${clinicId.value}` })
    storageReadable.value = true
  } catch {
    pendingWrites.value = []; storageReadable.value = false
    writeError.value = '无法读取本店原始费用请求；已停用费用写入。请恢复会话存储后重新读取。'
  }
}
async function loadFirstPage() { items.value = []; hasMore.value = false; nextAfterId.value = null; selected.value = null; allocationDrafts.value = []; await loadPage(null, false) }
async function refreshAdjustment(id: string) {
  await loadFirstPage()
  await openExpense(id)
}
async function loadPage(afterId: string | null, append: boolean) {
  if (!clinicId.value || !canReadExpenses(props.identity, clinicId.value) || loading.value) return
  const token = sessionToken(); const accountId = props.identity.accountId; const clinic = clinicId.value; const revision = generation; const pageRevision = listRevision; const filter = statusFilter.value
  const ticket = activity.begin('list')
  loading.value = true; listError.value = ''
  try {
    const raw = await api<unknown>(expenseListEndpoint(clinic, { status: filter, afterId, limit: 20 }))
    if (!current(token, accountId, clinic, revision) || pageRevision !== listRevision || filter !== statusFilter.value) return
    const page = assertExpenseList(clinic, raw)
    items.value = append ? [...items.value, ...page.items] : page.items
    hasMore.value = page.hasMore; nextAfterId.value = page.nextAfterId
  } catch (cause) { if (current(token, accountId, clinic, revision) && pageRevision === listRevision && filter === statusFilter.value) listError.value = message(cause) }
  finally { if (activity.finish(ticket)) loading.value = false }
}
async function loadNextPage() { if (hasMore.value && nextAfterId.value && !loading.value) await loadPage(nextAfterId.value, true) }
async function openExpense(id: string) {
  if (!/^\d+$/.test(id) || !clinicId.value || !canReadExpenses(props.identity, clinicId.value) || busy.value) return
  const token = sessionToken(); const accountId = props.identity.accountId; const clinic = clinicId.value; const revision = generation
  const ticket = activity.begin('detail')
  busy.value = true; selected.value = null; allocationDrafts.value = []; detailError.value = ''; notice.value = ''; confirmationReason.value = ''
  try {
    const raw = await api<unknown>(`/finance/expenses/${encodeURIComponent(id)}`)
    if (!current(token, accountId, clinic, revision)) return
    const result = assertExpenseResponse(clinic, id, accountId, raw)
    selected.value = result
    allocationDrafts.value = result.allocations.map((row) => ({ ...row, treatmentId: row.treatmentId ?? '' }))
  } catch (cause) { if (current(token, accountId, clinic, revision)) detailError.value = message(cause) }
  finally { if (activity.finish(ticket)) busy.value = false }
}
function addAllocation() {
  if (!canEditAllocations.value || allocationDrafts.value.length >= 100) return
  allocationDrafts.value.push({ allocationId: operationId('ALC').slice(4), dentistId: '', treatmentId: '', amount: '', allocationBasis: '' })
}
function removeAllocation(index: number) {
  if (canEditAllocations.value) allocationDrafts.value.splice(index, 1)
}
function message(cause: unknown) { return cause instanceof Error ? cause.message : '原始费用操作未完成。' }
function describeWriteError(cause: unknown) {
  const code = String((cause as { code?: string } | undefined)?.code ?? '')
  if (code === 'IDEMPOTENCY_CONFLICT') return '固定费用ID或单据行已绑定不同原始请求；原信封保留待核对，不能换号覆盖。'
  if (code === 'EXPENSE_SNAPSHOT_CONFLICT' || code === 'RETRY_TRANSACTION') return '完整费用expected快照已变化；原请求保留，请读取当前整笔费用和全部归属后再核对。'
  if (code === 'EXPENSE_ALLOCATION_SET_CONFLICT') return '服务端归属集合已变化；不能漏行或改挂，请重新读取完整详情。'
  if (code === 'EXPENSE_STATE_INVALID') return '费用已确认或处于终态，当前确认未被接受。'
  if (code === 'EXPENSE_ADJUSTMENT_NOT_SUPPORTED') return '调整来源仅供查看；本页不能确认或修改调整记录。'
  if (code === 'EXPENSE_ALLOCATION_LIMIT') return '归属超过100项，服务端未提交部分数据。'
  if (code === 'EXPENSE_AUDIT_TOO_LARGE') return '审计内容超过服务端上限，整笔费用操作未提交。'
  return message(cause)
}
function loadCreatePayload() {
  return makeExpenseCreationPayload({ expenseId: expenseId.value, documentNo: documentNo.value, lineNo: lineNo.value, kind: kind.value, occurredOn: occurredOn.value, amount: amount.value, reason: registrationReason.value })
}
async function registerExpense() {
  if (!canWrite.value || !storageReadable.value || pendingWrites.value.length || busy.value || loading.value) return
  let payload: ReturnType<typeof makeExpenseCreationPayload>
  try { payload = loadCreatePayload() } catch (cause) { writeError.value = message(cause); return }
  await submitWrite('expense-create', `/finance/clinics/${encodeURIComponent(clinicId.value)}/expenses`, payload, false)
}
function buildConfirmation() {
  if (!selected.value) throw new Error('请先读取费用完整详情。')
  const rows = selected.value.allocations.length ? selected.value.allocations : allocationDrafts.value.map((row) => makeExpenseAllocation({ ...row, treatmentId: row.treatmentId || null }))
  return makeExpenseConfirmationPayload(selected.value.snapshot, rows, confirmationReason.value)
}
async function confirmExpense() {
  if (!canConfirm.value || !storageReadable.value || pendingWrites.value.length || busy.value || loading.value) return
  let payload: ReturnType<typeof makeExpenseConfirmationPayload>
  try { payload = buildConfirmation() } catch (cause) { writeError.value = message(cause); return }
  await submitWrite('expense-confirm', `/finance/expenses/${encodeURIComponent(payload.expected.source.expenseId)}/confirmation`, payload, false)
}
async function retryOriginal(record: PendingWrite) {
  if (!canWrite.value || !storageReadable.value || busy.value || loading.value) return
  if (!pendingWrites.value.some((item) => item.key === record.key)) { writeError.value = '原始费用信封已变化或不存在；未发送重试。'; return }
  await submitWrite(record.action as 'expense-create' | 'expense-confirm', record.endpoint, record.payload, true, record)
}
async function submitWrite(action: 'expense-create' | 'expense-confirm', endpoint: string, payload: Record<string, unknown>, retry: boolean, record?: PendingWrite) {
  const token = sessionToken(); const accountId = props.identity.accountId; const clinic = clinicId.value; const revision = generation
  const ticket = activity.begin('write')
  busy.value = true; writeError.value = ''; notice.value = ''
  try {
    const outcome = retry && record
      ? await queue.retry(expensePendingScope(accountId, clinic, action), record)
      : await queue.submit(expensePendingScope(accountId, clinic, action), { endpoint, method: 'POST', payload, operationId: String(payload.expenseId ?? (payload.expected as ExpenseSnapshot).source.expenseId) })
    if (!current(token, accountId, clinic, revision, true)) return
    if (outcome.kind === 'success' || (outcome.kind === 'preserved' && outcome.result)) {
      const result = outcome.result as unknown as ExpenseEnvelope
      const parsed = assertExpenseResponse(clinic, String(payload.expenseId ?? (payload.expected as ExpenseSnapshot).source.expenseId), accountId, result, action === 'expense-create' ? { creation: payload } : { confirmation: payload })
      loadPending(); selected.value = parsed; allocationDrafts.value = parsed.allocations.map((row) => ({ ...row, treatmentId: row.treatmentId ?? '' }))
      if (action === 'expense-create') resetCreationForm()
      notice.value = outcome.kind === 'preserved'
        ? '服务端响应已确认费用身份，但本机未能清除原请求信封；请保留原记录并再次读取确认。'
        : action === 'expense-create' ? '服务端已确认原始费用登记；这不是分摊或采购入耗用成本。' : '服务端已确认整笔费用归属；确认状态与归属只读。'
      void loadFirstPage()
    } else if (outcome.kind === 'unknown' || outcome.kind === 'preserved') {
      loadPending(); writeError.value = `${describeWriteError(outcome.error) || '费用请求结果未知。'}原费用ID、账号、门店、完整body与expected已保留；只能沿用原请求核对/重试，不能换号。`
    } else if (outcome.kind === 'rejected') {
      loadPending(); writeError.value = describeWriteError(outcome.error)
    } else if (outcome.kind === 'storage-error') {
      storageReadable.value = false; writeError.value = '无法安全保存或读取原费用请求；没有发送请求。请恢复会话存储。'
    } else {
      loadPending(); writeError.value = outcome.kind === 'pending-exists' ? '本店已有费用原请求待核对；已阻止新ID或不同body。' : outcome.kind === 'busy' ? '费用写入正在处理中。' : '原费用信封已变化；未发送。'
    }
  } catch (cause) { if (current(token, accountId, clinic, revision, true)) writeError.value = describeWriteError(cause) }
  finally { if (activity.finish(ticket)) busy.value = false }
}
</script>

<template>
  <section v-if="!authorizedClinics.length" class="panel empty-state"><div class="empty-icon">⌂</div><strong>当前没有可访问门店</strong><span>费用登记入口仍保留。请由账号管理员确认财务门店范围；本页不会读取、登记或确认门店费用。</span></section>
  <section v-else class="page-stack expense-workspace">
    <div class="page-heading"><div><p class="eyebrow">员工工作台 · 原始费用与整笔归属</p><h1>费用登记</h1><p class="muted">只记录原始凭证费用并按财务依据整笔确认归属；不计算采购成本、自动分摊或支付。</p></div><label class="compact-label">授权门店<select v-model="clinicId"><option v-for="clinic in authorizedClinics" :key="clinic.clinic_id" :value="clinic.clinic_id">{{ clinic.name }} · #{{ clinic.clinic_id }}</option></select></label></div>
    <p v-if="writeError" class="followup-alert followup-alert-error" role="alert">{{ writeError }}</p><p v-if="notice" class="followup-alert followup-alert-info">{{ notice }}</p>
    <section v-if="pendingWrites.length" class="panel expense-pending"><strong>费用原请求待核对 · 不得更换费用ID</strong><article v-for="record in pendingWrites" :key="record.key"><span>{{ record.action === 'expense-create' ? '登记' : '归属确认' }} · 原费用ID {{ record.operationId }} · 原账号 #{{ record.accountId }} · 原门店 #{{ record.sourceClinicId }}</span><small>原endpoint {{ record.endpoint }} · 原payload/expected完整保留。</small><button class="button button-small button-secondary" :disabled="busy || !canWrite || !storageReadable" @click="retryOriginal(record)">沿用原ID和完整请求重试</button></article></section>
    <section class="panel expense-register">
      <div class="section-heading"><div><p class="eyebrow">{{ selectedClinicName }}</p><h2>登记原始费用</h2><p class="muted">记录凭证来源，不生成采购、库存、治疗成本或收入比例分摊。</p></div></div>
      <p v-if="!canWrite" class="followup-alert followup-alert-warn">总部管理只读；写入仅向该店或连锁授权的财务显示。</p>
      <form v-else class="form-grid form-grid-2 expense-form" @submit.prevent="registerExpense">
        <label>固定费用ID（字符串）<input :value="expenseId" readonly /></label>
        <label>原始单据号<input v-model="documentNo" maxlength="64" required :disabled="busy || pendingWrites.length > 0 || !storageReadable" /></label>
        <label>单据行号（正整数）<input v-model="lineNo" inputmode="numeric" required placeholder="原凭证行号" :disabled="busy || pendingWrites.length > 0 || !storageReadable" /></label>
        <label>费用类别<select v-model="kind" :disabled="busy || pendingWrites.length > 0 || !storageReadable"><option value="医疗用品">医疗用品</option><option value="运营费">运营费</option></select></label>
        <label>费用发生日期（明确填写）<input v-model="occurredOn" type="date" required :disabled="busy || pendingWrites.length > 0 || !storageReadable" /></label>
        <label>原始金额（字符串，恰两位小数）<input v-model="amount" type="text" inputmode="decimal" maxlength="15" placeholder="例如 125.00" required :disabled="busy || pendingWrites.length > 0 || !storageReadable" /></label>
        <label class="span-2">登记原因（可空，提供时最多1000字）<textarea v-model="registrationReason" maxlength="1000" rows="2" :disabled="busy || pendingWrites.length > 0 || !storageReadable" /></label>
        <div class="span-2 form-actions"><small>仅POST原始费用登记；状态、登记人和时间由服务端确定。</small><button class="button button-primary" :disabled="busy || loading || pendingWrites.length > 0 || !storageReadable">{{ busy ? '正在提交…' : '登记原始费用' }}</button></div>
      </form>
    </section>
    <section class="panel expense-list">
      <div class="section-heading"><div><p class="eyebrow">有界分页 · 最多20项/页</p><h2>本店费用记录</h2><p class="muted">本列表不是完整会计期间或季度汇总。</p></div><label class="compact-label">状态筛选<select v-model="statusFilter"><option value="">全部状态</option><option value="待分摊">待分摊</option><option value="待确认">待确认</option><option value="确认">确认</option><option value="作废">作废</option></select></label></div>
      <p v-if="listError" class="followup-alert followup-alert-error">{{ listError }}</p><div v-if="loading && !items.length" class="loading-state"><span class="spinner"></span>正在读取费用分页…</div>
      <div v-else-if="!items.length" class="empty-inline">本页没有匹配记录。</div>
      <div v-else class="table-wrap"><table><thead><tr><th>费用ID</th><th>原始单据/行</th><th>类别</th><th>发生日</th><th>金额</th><th>状态</th><th>来源</th><th></th></tr></thead><tbody><tr v-for="item in items" :key="item.expenseId" :class="{ 'row-selected': selected?.expense.expenseId === item.expenseId }"><td>{{ item.expenseId }}</td><td>{{ item.documentNo }}<small class="cell-sub">第{{ item.lineNo }}行</small></td><td>{{ item.kind }}</td><td>{{ item.occurredOn }}</td><td>{{ item.amount }}</td><td>{{ item.status }}</td><td>{{ item.adjustsExpenseId === null ? '原始费用' : `调整来源 #${item.adjustsExpenseId} · 只读` }}</td><td><button class="button button-small button-secondary" :disabled="busy" @click="openExpense(item.expenseId)">读取整笔详情</button></td></tr></tbody></table></div>
      <div class="expense-pagination"><small>已载入 {{ items.length }} 项；下一游标 {{ nextAfterId ?? '无' }}</small><button v-if="hasMore" class="button button-small button-secondary" :disabled="loading" @click="loadNextPage">{{ loading ? '读取中…' : '继续读取下一页' }}</button></div>
    </section>
    <section v-if="selected" class="panel expense-detail">
      <div class="section-heading"><div><p class="eyebrow">来源店 #{{ selected.expense.clinicId }} · 完整快照mutationCount {{ selected.snapshot.mutationCount }}</p><h2>费用 #{{ selected.expense.expenseId }} · {{ selected.expense.status }}</h2><p class="muted">完整来源及全部归属；不加载患者或无权病历。</p></div><button class="button button-quiet" :disabled="busy" @click="openExpense(selected.expense.expenseId)">重新读取详情</button></div>
      <p v-if="detailError" class="followup-alert followup-alert-error">{{ detailError }}</p>
      <dl class="expense-source-grid"><div><dt>凭证</dt><dd>{{ selected.expense.documentNo }} · 第{{ selected.expense.lineNo }}行</dd></div><div><dt>费用类别</dt><dd>{{ selected.expense.kind }}</dd></div><div><dt>发生日期</dt><dd>{{ selected.expense.occurredOn }}</dd></div><div><dt>原始金额</dt><dd>{{ selected.expense.amount }}</dd></div><div><dt>来源</dt><dd>{{ selected.expense.adjustsExpenseId === null ? '原始费用' : `调整自 #${selected.expense.adjustsExpenseId} · 只读` }}</dd></div><div><dt>登记人/时间</dt><dd>#{{ selected.expense.recordedBy }} · {{ formatShanghai(selected.expense.recordedAt) }}</dd></div><div><dt>确认人/时间</dt><dd>{{ selected.expense.confirmedBy === null ? '未确认' : `#${selected.expense.confirmedBy} · ${formatShanghai(selected.expense.confirmedAt)}` }}</dd></div><div class="span-2"><dt>登记原因</dt><dd>{{ selected.expense.reason ?? '未提供' }}</dd></div></dl>
      <div v-if="selected.allocations.length" class="expense-allocation-list"><h3>完整已登记归属（只读核对）</h3><article v-for="row in selected.allocations" :key="row.allocationId"><strong>归属 #{{ row.allocationId }} · 牙医 #{{ row.dentistId }} · {{ row.amount }}</strong><small>实际治疗ID：{{ row.treatmentId ?? '未直接关联' }} · 依据：{{ row.allocationBasis }}</small></article></div>
      <ExpenseAdjustments v-if="canWrite && !identity.readOnly" :key="selected.expense.expenseId" :expense="selected.expense" :allocations="selected.allocations" @changed="refreshAdjustment" />
      <p v-if="selected.expense.adjustsExpenseId !== null" class="followup-alert followup-alert-warn">调整来源只读，本页不提供调整、作废或恢复操作。</p>
      <template v-else-if="canEditAllocations">
        <div class="expense-allocation-edit"><div class="section-heading"><div><h3>登记完整归属集合</h3><p class="muted">金额必须按分精确合计原费用。当前归属合计 {{ formatCents(draftTotal) }} / {{ selected.expense.amount }}。实际治疗ID由财务手工填写已知值，服务端核验；没有候选发现接口，不生成候选、不读取病历。</p></div><button class="button button-small button-secondary" :disabled="allocationDrafts.length >= 100" @click="addAllocation">添加归属行</button></div>
          <article v-for="(row, index) in allocationDrafts" :key="row.allocationId" class="allocation-row"><header><strong>归属ID {{ row.allocationId }} · 第{{ index + 1 }}行</strong><button class="button button-small button-quiet" @click="removeAllocation(index)">移除</button></header><label>牙医ID（手工输入）<input v-model="row.dentistId" inputmode="numeric" /></label><label>已知实际治疗ID（可空）<input v-model="row.treatmentId" inputmode="numeric" placeholder="留空表示无直接治疗关联" /></label><label>精确金额字符串<input v-model="row.amount" type="text" inputmode="decimal" placeholder="例如 125.00" /></label><label>分配依据（必填）<input v-model="row.allocationBasis" maxlength="255" /></label></article>
          <button v-if="!allocationDrafts.length" class="button button-small button-secondary" @click="addAllocation">添加第一项归属</button>
        </div>
      </template>
      <template v-if="canWrite && canConfirmExpenseSnapshot">
        <div class="expense-confirm"><p class="followup-alert followup-alert-info">整笔归属精确合计：{{ selected.expense.amount }}；{{ totalMatches ? '与原费用一致' : '未匹配或输入尚不完整' }}。确认将一次性保存全部归属并锁定来源，不支持删行、调整或再次编辑。</p><label>确认原因（必填）<textarea v-model="confirmationReason" maxlength="1000" rows="2" :disabled="busy || pendingWrites.length > 0" /></label><button class="button button-primary" :disabled="busy || loading || pendingWrites.length > 0 || !storageReadable || !canConfirm || !totalMatches" @click="confirmExpense">{{ busy ? '正在确认…' : '确认整笔归属' }}</button></div>
      </template>
      <p v-else-if="selected.expense.status === '确认' || selected.expense.status === '作废'" class="followup-alert followup-alert-warn">确认/作废状态为终态，只读。</p>
      <p v-else-if="!canWrite" class="followup-alert followup-alert-warn">当前为只读访问；只有财务角色可以写入或确认费用。</p>
      <p v-else-if="selected.expense.status === '待确认' && !selected.allocations.length" class="followup-alert followup-alert-warn">历史待确认记录没有完整原归属集合；不能借确认入口补行或重建来源。</p>
      <p v-if="selected.quarterReport" class="muted">季度报告：{{ selected.quarterReport }}。当前没有季度汇总功能。</p>
    </section>
    <p v-if="!storageReadable" class="followup-alert followup-alert-error">本机费用原请求存储不可读，所有写入已停用。</p>
  </section>
</template>

<style scoped>
.expense-workspace{gap:16px}.expense-pending article,.expense-allocation-list article{display:grid;gap:5px;padding:10px 12px;border:1px solid var(--line);border-radius:8px;margin-top:8px}.expense-pending article small,.expense-allocation-list article small{color:var(--muted);overflow-wrap:anywhere}.expense-pagination{display:flex;align-items:center;justify-content:space-between;gap:10px;padding-top:12px}.expense-source-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin:0}.expense-source-grid>div{padding:10px;border-radius:8px;background:#f7f9fa}.expense-source-grid dt{font-size:10px;color:var(--muted)}.expense-source-grid dd{margin:4px 0 0;font-size:12px;overflow-wrap:anywhere}.expense-allocation-edit,.expense-confirm,.expense-allocation-list{display:grid;gap:10px;margin-top:18px;padding-top:14px;border-top:1px solid var(--line)}.allocation-row{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;padding:12px;border:1px solid var(--line);border-radius:9px}.allocation-row header{grid-column:1/-1;display:flex;justify-content:space-between;align-items:center}.expense-form .form-actions{display:flex;justify-content:space-between;align-items:center;gap:10px}.expense-form small{color:var(--muted)}@media(max-width:760px){.expense-source-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.allocation-row{grid-template-columns:1fr}.expense-pagination{align-items:flex-start;flex-direction:column}}
</style>
