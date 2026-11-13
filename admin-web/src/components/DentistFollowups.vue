<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { api, sessionToken } from '../api'
import type { Clinic, Identity } from '../api'
import NoticeBar from './NoticeBar.vue'
import StatusBadge from './StatusBadge.vue'
import FollowupPlanDetail from './FollowupPlanDetail.vue'
import { cursorPageChangedBusinessDate, cursorQuery, hasRoleAtClinic, mergeCursorPage } from '../followup-core.js'

type Row = Record<string, unknown>
const props = defineProps<{ clinics: Clinic[]; identity: Identity }>()
const allowedClinics = computed(() => props.clinics.filter((clinic) => hasRoleAtClinic(props.identity, '牙医', clinic.clinic_id)))
const clinicId = ref(allowedClinics.value[0]?.clinic_id ?? '')
const status = ref('待复诊')
const items = ref<Row[]>([])
const asOfDate = ref('')
const hasMore = ref(false)
const nextAfterId = ref<string | null>(null)
const loading = ref(false)
const error = ref('')
const notice = ref('')
const selectedPlanId = ref('')
let generation = 0
let mounted = false
let disposed = false

watch(allowedClinics, (clinics) => { if (!clinics.some((clinic) => clinic.clinic_id === clinicId.value)) clinicId.value = clinics[0]?.clinic_id ?? '' })
watch([clinicId, status, () => props.identity.accountId], () => {
  generation += 1; items.value = []; asOfDate.value = ''; hasMore.value = false; nextAfterId.value = null; selectedPlanId.value = ''; notice.value = ''; error.value = ''
  if (mounted) void loadFirst()
})
onMounted(() => { mounted = true; void loadFirst() })
onUnmounted(() => { disposed = true; generation += 1 })

function current(token: string | null, accountId: string, clinic: string, requestGeneration: number) {
  return !disposed && token === sessionToken() && accountId === props.identity.accountId && clinic === clinicId.value && requestGeneration === generation
}
function endpoint(afterId: string | null) {
  return `/dentist/followup-plans?${cursorQuery({ clinicId: clinicId.value, status: status.value, limit: 30 }, afterId)}`
}
async function loadFirst() { await loadPage(false) }
async function loadMore() { if (hasMore.value && nextAfterId.value && !loading.value) await loadPage(true) }
async function loadPage(append: boolean) {
  if (!allowedClinics.value.some((clinic) => clinic.clinic_id === clinicId.value)) { items.value = []; error.value = '当前账号没有所选门店的牙医权限。'; return }
  const requestGeneration = generation; const token = sessionToken(); const accountId = props.identity.accountId; const clinic = clinicId.value
  const cursor = append ? nextAfterId.value : null
  loading.value = true; error.value = ''
  try {
    const response = await api<{ asOfDate: string; items: Row[]; hasMore: boolean; nextAfterId: string | null }>(endpoint(cursor))
    if (!current(token, accountId, clinic, requestGeneration)) return
    if (append && cursorPageChangedBusinessDate(asOfDate.value, response.asOfDate)) {
      notice.value = `服务端业务日从 ${asOfDate.value} 变为 ${response.asOfDate}；已从第一页重新读取。`
      items.value = []; hasMore.value = false; nextAfterId.value = null
      const first = await api<{ asOfDate: string; items: Row[]; hasMore: boolean; nextAfterId: string | null }>(endpoint(null))
      if (!current(token, accountId, clinic, requestGeneration)) return
      const page = mergeCursorPage([], first, false)
      items.value = page.items; asOfDate.value = page.asOfDate; hasMore.value = page.hasMore; nextAfterId.value = page.nextAfterId
      return
    }
    const page = mergeCursorPage(append ? items.value : [], response, append)
    items.value = page.items; asOfDate.value = page.asOfDate; hasMore.value = page.hasMore; nextAfterId.value = page.nextAfterId
  } catch (cause) { if (current(token, accountId, clinic, requestGeneration)) error.value = cause instanceof Error ? cause.message : '牙医复诊计划读取失败。' }
  finally { if (current(token, accountId, clinic, requestGeneration)) loading.value = false }
}
function showDetail(item: Row) { selectedPlanId.value = String((item.plan as Row).followup_plan_id) }
</script>

<template>
  <section class="page-stack followup-workspace">
    <div class="page-heading"><div><p class="eyebrow">牙医 · 本人复诊计划</p><h1>复诊计划</h1><p class="muted">服务端按当前会话绑定牙医与授权门店过滤；列表不返回电话或联系备注。</p></div><label class="compact-label">获权门店<select v-model="clinicId"><option v-for="clinic in allowedClinics" :key="clinic.clinic_id" :value="clinic.clinic_id">{{ clinic.name }}</option></select></label></div>
    <NoticeBar tone="error" :title="error" @close="error = ''" /><NoticeBar tone="success" :title="notice" @close="notice = ''" />
    <section class="panel"><div class="followup-filters"><label>计划状态<select v-model="status"><option>待复诊</option><option>已完成</option><option>已取消</option><option value="all">全部状态</option></select></label><button class="button button-quiet" type="button" :disabled="loading" @click="loadFirst">↻ 从第一页刷新</button></div><p class="muted followup-asof">服务端上海业务日期：<strong>{{ asOfDate || '读取中…' }}</strong> · 当前已加载 {{ items.length }} 条<span v-if="hasMore"> · 服务端仍有后页</span></p>
      <div v-if="loading && !items.length" class="loading-state"><span class="spinner"></span>正在读取本人复诊计划…</div><div v-else-if="!items.length && !error" class="empty-state"><div class="empty-icon">◷</div><strong>当前没有符合筛选的本人计划</strong><span>计划创建/维护仍由真实完成来源就诊的服务端规则校验。</span></div>
      <div v-else-if="items.length" class="followup-plan-list"><article v-for="item in items" :key="String((item.plan as Row).followup_plan_id)" class="followup-plan-card"><div class="followup-plan-main"><div class="followup-plan-title"><strong>{{ (item.patient as Row).name }}</strong><small>{{ (item.patient as Row).patient_no }}</small><span class="followup-plan-tags"><StatusBadge :value="String((item.plan as Row).status)" /><span v-if="(item.plan as Row).overdue === true" class="badge badge-warning">已逾期（服务端）</span><span v-else-if="(item.plan as Row).status === '待复诊'" class="badge badge-neutral">未逾期（服务端）</span></span></div><div class="followup-plan-date"><span>建议复诊</span><strong>{{ String((item.plan as Row).suggested_date) }}</strong><small>业务日 {{ asOfDate }}</small></div></div><p class="followup-recommendation">{{ (item.plan as Row).recommendation }}</p><p class="muted">来源就诊 #{{ (item.plan as Row).source_appointment_id }} · 牙医/患者由服务端绑定</p><button class="button button-secondary" type="button" @click="showDetail(item)">读取最新完整快照并维护</button></article></div>
      <div v-if="hasMore" class="followup-load-more"><button class="button button-secondary" type="button" :disabled="loading" @click="loadMore">{{ loading ? '正在读取…' : `加载下一页（从计划 #${nextAfterId} 之后继续）` }}</button></div><div v-if="loading && items.length" class="inline-loading"><span class="spinner"></span>正在追加本人范围下一页…</div>
    </section>
    <FollowupPlanDetail v-if="selectedPlanId" :key="`${identity.accountId}:${clinicId}:${selectedPlanId}:dentist`" mode="dentist" :plan-id="selectedPlanId" :clinic-id="clinicId" :identity="identity" :clinics="clinics" @close="selectedPlanId = ''" @changed="loadFirst" />
  </section>
</template>
