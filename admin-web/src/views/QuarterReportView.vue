<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { api, formatShanghai, sessionToken } from '../api'
import type { Identity } from '../api'
import { preserveQuarterReport, quarterReadAccess, quarterReadRequest, quarterRequestMatches, quarterScopeKey } from '../quarter-core.js'
import type { QuarterMode, QuarterReport } from '../quarter-core.js'

const props = defineProps<{ identity: Identity }>()
const access = computed(() => quarterReadAccess(props.identity))
const mode = ref<QuarterMode>(access.value.finance ? 'finance' : 'headquarters')
const shanghaiParts = Object.fromEntries(new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit' }).formatToParts(new Date()).map((part) => [part.type, part.value]))
const year = ref(shanghaiParts.year ?? '2026')
const quarter = ref(String(Math.ceil(Number(shanghaiParts.month ?? '1') / 3)))
const clinicId = ref('')
const dentistId = ref('')
const limit = ref('20')
const report = ref<QuarterReport | null>(null)
const loading = ref(false)
const error = ref('')
const confirmationToken = ref('')
const confirmation = ref<{ at: string; actor: string; matches: boolean }>()
const confirmationReason = ref('')
let generation = 0

const scopeFingerprint = computed(() => JSON.stringify([mode.value, year.value, quarter.value, clinicId.value, dentistId.value, limit.value, props.identity.accountId, props.identity.kind, props.identity.grants]))
watch(scopeFingerprint, () => {
  generation += 1
  report.value = null
  error.value = ''
  loading.value = false
  confirmationToken.value = ''; confirmation.value = undefined; confirmationReason.value = ''
}, { flush: 'sync' })
onUnmounted(() => { generation += 1 })

const selectedModeLabel = computed(() => mode.value === 'finance' ? '财务入口' : '总部连锁入口')
const fundLabel = computed(() => report.value?.fundReason === 'PARTIAL_SCOPE' ? '财务范围不提供公司基金' : report.value?.fundReason === 'KNOWN_BLOCKING_SOURCES' ? '存在已知阻断异常，暂不估算' : report.value?.fundReason === 'REGISTERED_SOURCES_ESTIMATE' ? '按全链已登记来源估算' : report.value?.fundReason ?? '—')

function currentRequest() {
  return quarterReadRequest(mode.value, { year: year.value, quarter: quarter.value, clinicId: clinicId.value, dentistId: dentistId.value, limit: Number(limit.value) })
}
function currentContext(request: ReturnType<typeof currentRequest>) {
  return { scopeKey: quarterScopeKey(mode.value, request), accountId: String(props.identity.accountId), token: sessionToken() ?? '' }
}
async function runQuery() {
  error.value = ''
  report.value = null
  confirmationToken.value = ''; confirmation.value = undefined
  let request: ReturnType<typeof currentRequest>
  try {
    if ((mode.value === 'finance' && !access.value.finance) || (mode.value === 'headquarters' && !access.value.headquarters)) throw new Error('当前账号没有所选入口的对应员工授权。')
    request = currentRequest()
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '季度查询参数无效。'
    return
  }
  const serial = ++generation
  const captured = currentContext(request)
  loading.value = true
  try {
    const raw = await api<{ confirmationToken?: string; confirmation?: { at: string; actor: string; matches: boolean } }>(request.endpoint)
    const parsed = preserveQuarterReport(raw, request, mode.value)
    let current: ReturnType<typeof currentContext> | null = null
    try { current = currentContext(currentRequest()) } catch { /* changed to invalid parameters while request was in flight */ }
    if (serial !== generation || !quarterRequestMatches(captured, current)) return
    report.value = parsed
    confirmationToken.value = raw.confirmationToken || ''
    confirmation.value = raw.confirmation
  } catch (reason) {
    let current: ReturnType<typeof currentContext> | null = null
    try { current = currentContext(currentRequest()) } catch { /* changed to invalid parameters while request was in flight */ }
    if (serial === generation && quarterRequestMatches(captured, current)) error.value = reason instanceof Error ? reason.message : '季度读取失败，未展示部分结果。'
  } finally {
    if (serial === generation) loading.value = false
  }
}

async function confirmQuarter() {
  if (!report.value || !confirmationToken.value || !confirmationReason.value.trim() || loading.value || props.identity.readOnly) return
  const serial = generation
  const token = sessionToken()
  loading.value = true
  error.value = ''
  try {
    await api('/headquarters/quarter/confirmation', { method: 'POST', body: {
      year: report.value.year, quarter: report.value.quarter, token: confirmationToken.value, reason: confirmationReason.value.trim()
    } })
    if (serial === generation && token === sessionToken()) {
      confirmationReason.value = ''
      await runQuery()
    }
  } catch (e) {
    if (serial === generation) error.value = (e as Error).message
  } finally {
    if (serial === generation) loading.value = false
  }
}

function amountCards(value: QuarterReport['scopeTotals']) {
  return [
    { label: '有效收入', amount: value.revenue },
    { label: '医疗用品', amount: value.supplies },
    { label: '运营费', amount: value.operations },
    { label: '经营贡献', amount: value.profit },
  ]
}
function reasonLabel(reason: string) {
  const labels: Record<string, string> = {
    COMPLETION_DATE_MISSING: '缺少完成日期', COMPLETION_INVALID: '完成来源无效', INVOICE_NOT_ISSUED: '结算单未有效发出', INVOICE_SOURCE_INVALID: '结算来源或明细无效',
    EXPENSE_DATE_MISSING: '缺少费用发生日期', EXPENSE_NOT_CONFIRMED: '费用尚未确认', EXPENSE_SOURCE_INVALID: '费用确认、归属或调整来源无效',
  }
  return labels[reason] ?? reason
}
</script>

<template>
  <div class="quarter-page">
    <div class="page-heading">
      <div><p class="eyebrow">员工工作台 · 登记来源实时汇总</p><h1>季度经营报表</h1><p class="muted">仅查看本次读取结果。此报表检查已登记来源，不代表无漏登记、财务核对或季度封账。</p></div>
      <div v-if="access.finance && access.headquarters" class="quarter-entry-switch" aria-label="季度报表入口">
        <button type="button" :class="{ active: mode === 'finance' }" @click="mode = 'finance'">财务入口</button>
        <button type="button" :class="{ active: mode === 'headquarters' }" @click="mode = 'headquarters'">总部连锁入口</button>
      </div>
      <span v-else class="quarter-entry-badge">{{ selectedModeLabel }}</span>
    </div>

    <section class="panel quarter-query-panel">
      <div class="section-heading"><div><p class="eyebrow">显式查询范围</p><h2>选择季度与展示筛选</h2><p class="muted">{{ mode === 'finance' ? '财务查询使用当前账号的服务端授权门店范围；门店可选筛选，基金始终不可用。' : '总部入口要求精确的连锁总部授权；门店或牙医仅筛展示贡献，不改变完整性检查及全链基金范围。' }}</p></div></div>
      <div class="quarter-form-grid">
        <label>年份<input v-model="year" inputmode="numeric" maxlength="4" placeholder="2026"></label>
        <label>季度<select v-model="quarter"><option value="1">第一季度</option><option value="2">第二季度</option><option value="3">第三季度</option><option value="4">第四季度</option></select></label>
        <label>展示门店ID（可选）<input v-model="clinicId" inputmode="numeric" placeholder="不填表示入口授权范围"></label>
        <label>展示牙医ID（可选）<input v-model="dentistId" inputmode="numeric" placeholder="不填表示不按牙医筛选"></label>
        <label>每类首屏数量<select v-model="limit"><option value="20">20</option><option value="50">50</option><option value="100">100</option></select></label>
        <div class="quarter-query-action"><button class="button button-primary" type="button" :disabled="loading" @click="runQuery">{{ loading ? '正在读取…' : '读取季度报表' }}</button><span class="muted">仅GET只读查询</span></div>
      </div>
      <p v-if="error" class="followup-alert followup-alert-warn" role="alert">{{ error }} 未显示本次查询的部分结果。</p>
      <p v-if="loading" class="quarter-loading" role="status">正在读取完整来源检查、全量范围总计与首屏明细；此入口不会写入季度确认、支付或导出记录。</p>
    </section>

    <template v-if="report">
      <section class="quarter-period-banner">
        <div><span class="eyebrow">{{ report.year }} 年第 {{ report.quarter }} 季度 · {{ report.scopeMode }}</span><strong>{{ report.startDate }} 起 — {{ report.endDateExclusive }} 止（结束日不含）</strong></div>
        <div><span>上海季度对应UTC半开区间</span><strong>{{ report.utcStart }} — {{ report.utcEndExclusive }}</strong></div>
        <div><span>读取开始 / 计算完成</span><strong>{{ report.readStartedAt }} / {{ report.computedAt }}</strong></div>
      </section>

      <section class="panel quarter-integrity-panel">
        <div class="quarter-integrity-title"><div><p class="eyebrow">完整性检查范围：{{ report.integrityClinicIds.length }} 家门店</p><h2>{{ report.integrity.status === 'INCOMPLETE' ? '发现已知阻断来源' : '已检查已登记来源' }}</h2></div><span class="quarter-status" :class="report.integrity.status === 'INCOMPLETE' ? 'is-incomplete' : 'is-checked'">{{ report.integrity.blockingSources }} 个异常来源</span></div>
        <p class="muted">此状态仅表示对已登记来源的检查结果，不证明没有遗漏来源，也不构成财务核对或封账。knownSourcesComplete={{ String(report.integrity.knownSourcesComplete) }}。检查门店：{{ report.integrityClinicIds.join('、') || '无' }}。牙医筛选只影响展示贡献，不缩小完整性检查范围。</p>
        <div class="quarter-count-grid"><div><span>完成来源</span><strong>{{ report.sourceCounts.completion_sources }}</strong></div><div><span>有效结算来源</span><strong>{{ report.sourceCounts.valid_invoice_sources }}</strong></div><div><span>费用来源（不含作废）</span><strong>{{ report.sourceCounts.expense_sources }}</strong></div><div><span>有效确认归属费用</span><strong>{{ report.sourceCounts.valid_expense_sources }}</strong></div></div>
      </section>

      <section class="quarter-total-grid">
        <article v-for="item in amountCards(report.scopeTotals)" :key="item.label" class="panel quarter-total-card"><span>{{ item.label }} · 当前筛选范围</span><strong>{{ item.amount }}</strong></article>
      </section>
      <section v-if="mode === 'headquarters' && report.chainTotals" class="panel quarter-chain-panel">
        <div class="section-heading"><div><p class="eyebrow">全链完整范围，不受展示筛选影响</p><h2>总部全链总额</h2></div></div>
        <div class="quarter-total-grid quarter-total-grid-inner"><article v-for="item in amountCards(report.chainTotals)" :key="item.label" class="quarter-chain-total"><span>{{ item.label }}</span><strong>{{ item.amount }}</strong></article></div>
        <p class="muted">上方“当前筛选范围”总额与本组全链总额分别来自服务端字段，不能混为一组或将当前页面明细相加代替总额。</p>
      </section>
      <section class="panel quarter-fund-panel">
        <div><p class="eyebrow">公司基金</p><h2>{{ report.companyFund === null ? '—' : report.companyFund }}</h2></div>
        <p>{{ fundLabel }}。{{ report.fundReason === 'REGISTERED_SOURCES_ESTIMATE' ? '该数值是按已登记来源估算，仍不是封账或付款指令。' : '空值代表不可用，不按零元展示。' }}</p>
      </section>

      <section class="panel quarter-list-panel">
        <div class="section-heading"><div><p class="eyebrow">服务端完整贡献总额 {{ report.contributionCount }} 组</p><h2>牙医贡献首屏</h2></div><span class="quarter-page-chip">仅首屏 · 每页 {{ report.contributions.limit }}</span></div>
        <p v-if="report.contributions.hasMore" class="muted">此列表只显示排序后的前 {{ report.contributions.limit }} 组。hasMore=true 仅表示首屏外仍有展示行，不表示来源未扫描或完整性检查漏项；接口没有后续页或固定快照令牌，未提供下一页操作。</p>
        <p v-else-if="!report.contributions.items.length" class="muted">当前筛选范围没有贡献展示行；以上全量范围总计仍按服务端返回值展示。</p>
        <div v-if="report.contributions.items.length" class="table-scroll"><table><thead><tr><th>门店ID</th><th>牙医ID</th><th>收入</th><th>医疗用品</th><th>运营费</th><th>经营贡献</th></tr></thead><tbody><tr v-for="(item, index) in report.contributions.items" :key="`${item.clinicId}:${item.dentistId}:${index}`"><td>{{ item.clinicId }}</td><td>{{ item.dentistId }}</td><td>{{ item.amounts.revenue }}</td><td>{{ item.amounts.supplies }}</td><td>{{ item.amounts.operations }}</td><td>{{ item.amounts.profit }}</td></tr></tbody></table></div>
      </section>

      <section class="panel quarter-list-panel">
        <div class="section-heading"><div><p class="eyebrow">完整异常分组计数，共 {{ report.anomalyCounts.reduce((sum, row) => sum + BigInt(row.source_count), 0n).toString() }} 个来源</p><h2>异常来源首屏</h2></div><span class="quarter-page-chip">仅首屏 · 每页 {{ report.anomalies.limit }}</span></div>
        <h3 class="quarter-subheading">全范围异常计数（每个来源仅计首要原因）</h3>
        <div v-if="report.anomalyCounts.length" class="table-scroll"><table><thead><tr><th>来源类型</th><th>首要原因</th><th>来源数</th></tr></thead><tbody><tr v-for="item in report.anomalyCounts" :key="`${item.source_type}:${item.reason}`"><td>{{ item.source_type }}</td><td>{{ reasonLabel(item.reason) }} <code>{{ item.reason }}</code></td><td>{{ item.source_count }}</td></tr></tbody></table></div>
        <p v-else class="muted">服务端返回全范围异常分组计数为空；这仍仅表示已登记来源检查。</p>
        <p v-if="report.anomalies.hasMore" class="muted">下表只显示排序后的前 {{ report.anomalies.limit }} 个异常来源；hasMore=true 表示还有异常行未显示，完整异常计数已由服务端返回。接口没有下一页或固定快照令牌。</p>
        <p v-else-if="!report.anomalies.items.length" class="muted">异常明细首屏无项；其含义以完整异常计数和完整性字段为准，不称为季度封账。</p>
        <div v-if="report.anomalies.items.length" class="table-scroll"><table><thead><tr><th>来源</th><th>来源ID</th><th>门店ID</th><th>完成时间</th><th>发生日期</th><th>首要原因</th></tr></thead><tbody><tr v-for="(item, index) in report.anomalies.items" :key="`${item.sourceType}:${item.clinicId}:${item.sourceId}:${index}`"><td>{{ item.sourceType }}</td><td>{{ item.sourceId }}</td><td>{{ item.clinicId }}</td><td>{{ item.completedAt ?? '—' }}</td><td>{{ item.occurredOn ?? '—' }}</td><td>{{ reasonLabel(item.reason) }} <code>{{ item.reason }}</code></td></tr></tbody></table></div>
      </section>
      <section v-if="mode==='headquarters' && !report.displayScope.clinicId && !report.displayScope.dentistId" class="panel">
        <h3>总部确认</h3>
        <p v-if="confirmation">最近确认 {{ formatShanghai(confirmation.at) }} · 操作人 #{{ confirmation.actor }} · {{ confirmation.matches ? '与当前汇总一致' : '汇总已变化，需重新核对' }}</p>
        <form v-if="confirmationToken && !confirmation?.matches && !identity.readOnly" class="form-actions" @submit.prevent="confirmQuarter">
          <label>核对说明 <input v-model="confirmationReason" maxlength="500" required :disabled="loading"></label>
          <button data-readonly-write :disabled="loading" type="submit">确认本季汇总</button>
        </form>
        <p v-else-if="!confirmationToken" class="muted">季度结束且已登记来源核对齐全后可确认。</p>
      </section>
      <p class="quarter-footnote">按上海季度归集。每次查询读取实时数据，总部确认记录保留当时的汇总与核对说明。</p>
    </template>
  </div>
</template>

<style scoped>
.quarter-page { display: grid; gap: 20px; }
.quarter-entry-switch { display: inline-flex; padding: 4px; gap: 4px; border: 1px solid var(--line); border-radius: 12px; background: var(--surface-soft); }
.quarter-entry-switch button { border: 0; border-radius: 8px; padding: 9px 12px; color: var(--muted); background: transparent; cursor: pointer; font: inherit; }
.quarter-entry-switch button.active { color: var(--ink); background: white; box-shadow: 0 1px 5px #18324618; font-weight: 700; }
.quarter-entry-badge,.quarter-page-chip { align-self: start; border-radius: 999px; padding: 7px 11px; background: var(--surface-soft); color: var(--muted); font-size: 12px; font-weight: 700; }
.quarter-query-panel,.quarter-integrity-panel,.quarter-list-panel,.quarter-chain-panel { padding: 22px; }
.quarter-form-grid { display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); gap: 15px; margin-top: 18px; }
.quarter-form-grid label { display: grid; gap: 7px; color: var(--muted); font-size: 12px; font-weight: 700; }
.quarter-form-grid input,.quarter-form-grid select { width: 100%; min-height: 42px; border: 1px solid var(--line); border-radius: 9px; padding: 9px 11px; color: var(--ink); background: white; font: inherit; }
.quarter-query-action { display: flex; align-items: center; gap: 12px; }
.quarter-loading { margin: 16px 0 0; color: var(--muted); font-size: 13px; }
.quarter-period-banner { display: grid; grid-template-columns: 1.2fr 1fr 1fr; gap: 12px; border-radius: 14px; padding: 18px 20px; background: #edf5f4; color: var(--ink); }
.quarter-period-banner div { display: grid; align-content: start; gap: 7px; min-width: 0; }
.quarter-period-banner span { color: var(--muted); font-size: 12px; }
.quarter-period-banner strong { overflow-wrap: anywhere; font-size: 14px; }
.quarter-integrity-title { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.quarter-integrity-title h2,.quarter-chain-panel h2,.quarter-fund-panel h2 { margin: 3px 0 0; }
.quarter-status { flex: none; border-radius: 999px; padding: 8px 12px; font-size: 13px; font-weight: 700; }
.quarter-status.is-checked { color: #216454; background: #e7f5ef; }
.quarter-status.is-incomplete { color: #8e4c26; background: #fff1e5; }
.quarter-count-grid { display: grid; grid-template-columns: repeat(4,minmax(0,1fr)); gap: 10px; margin-top: 17px; }
.quarter-count-grid div { display: grid; gap: 7px; border: 1px solid var(--line); border-radius: 10px; padding: 13px; }
.quarter-count-grid span,.quarter-total-card span,.quarter-chain-total span { color: var(--muted); font-size: 12px; }
.quarter-count-grid strong { font-size: 19px; }
.quarter-total-grid { display: grid; grid-template-columns: repeat(4,minmax(0,1fr)); gap: 12px; }
.quarter-total-card { display: grid; gap: 12px; padding: 17px; }
.quarter-total-card strong,.quarter-chain-total strong { font-size: clamp(19px,2vw,25px); overflow-wrap: anywhere; }
.quarter-total-grid-inner { margin-top: 14px; }
.quarter-chain-total { display: grid; gap: 10px; border-radius: 10px; padding: 14px; background: var(--surface-soft); }
.quarter-fund-panel { display: flex; align-items: center; justify-content: space-between; gap: 20px; padding: 19px 22px; border-color: #d9e6e1; background: #f3f8f5; }
.quarter-fund-panel p { max-width: 620px; margin: 0; color: var(--muted); font-size: 13px; }
.quarter-subheading { margin: 18px 0 10px; font-size: 14px; }
.table-scroll { overflow-x: auto; margin-top: 12px; }
.table-scroll table { width: 100%; border-collapse: collapse; font-size: 13px; }
.table-scroll th,.table-scroll td { padding: 11px 10px; border-bottom: 1px solid var(--line); text-align: left; white-space: nowrap; }
.table-scroll th { color: var(--muted); font-weight: 700; background: var(--surface-soft); }
.table-scroll code { display: inline-block; margin-left: 4px; color: var(--muted); font-size: 11px; }
.quarter-footnote { margin: 0; color: var(--muted); font-size: 12px; line-height: 1.7; }
@media (max-width: 900px) { .quarter-form-grid,.quarter-period-banner { grid-template-columns: repeat(2,minmax(0,1fr)); } .quarter-total-grid,.quarter-count-grid { grid-template-columns: repeat(2,minmax(0,1fr)); } }
@media (max-width: 560px) { .quarter-entry-switch { width: 100%; } .quarter-entry-switch button { flex: 1; } .quarter-form-grid,.quarter-period-banner { grid-template-columns: 1fr; } .quarter-integrity-title,.quarter-fund-panel { align-items: flex-start; flex-direction: column; } .quarter-total-grid,.quarter-count-grid { grid-template-columns: repeat(2,minmax(0,1fr)); } }
</style>
