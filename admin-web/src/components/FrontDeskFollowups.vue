<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { api, sessionToken } from '../api'
import type { Clinic, Identity } from '../api'
import NoticeBar from './NoticeBar.vue'
import StatusBadge from './StatusBadge.vue'
import FollowupPlanDetail from './FollowupPlanDetail.vue'
import { hasFrontDeskGrant } from '../fulfillment-core.js'
import { cursorPageChangedBusinessDate, cursorQuery, mergeCursorPage } from '../followup-core.js'

type Row = Record<string, unknown>
const props = defineProps<{ clinics: Clinic[]; identity: Identity }>()
const allowedClinics = computed(() => props.clinics.filter((clinic) => hasFrontDeskGrant(props.identity, clinic.clinic_id)))
const clinicId = ref(allowedClinics.value[0]?.clinic_id ?? '')
const status = ref('待复诊')
const overdueOnly = ref(false)
const items = ref<Row[]>([])
const asOfDate = ref('')
const hasMore = ref(false)
const nextAfterId = ref<string | null>(null)
const loading = ref(false)
const error = ref('')
const notice = ref('')
const selectedPlanId = ref('')
let generation = 0
let disposed = false
let mounted = false

watch(allowedClinics, (clinics) => {
  if (!clinics.some((clinic) => clinic.clinic_id === clinicId.value)) clinicId.value = clinics[0]?.clinic_id ?? ''
})
watch(status, (next) => { if (!['待复诊', 'all'].includes(next)) overdueOnly.value = false })
watch([clinicId, status, overdueOnly, () => props.identity.accountId], () => {
  generation += 1; items.value = []; asOfDate.value = ''; hasMore.value = false; nextAfterId.value = null
  selectedPlanId.value = ''; error.value = ''; notice.value = ''
  if (mounted) void loadFirst()
})
onMounted(() => { mounted = true; void loadFirst() })
onUnmounted(() => { disposed = true; generation += 1 })

function current(token: string | null, accountId: string, clinic: string, requestGeneration: number) {
  return !disposed && token === sessionToken() && accountId === props.identity.accountId && clinic === clinicId.value && requestGeneration === generation
}
function endpoint(afterId: string | null) {
  const query = cursorQuery({ clinicId: clinicId.value, status: status.value, overdueOnly: ['待复诊', 'all'].includes(status.value) && overdueOnly.value ? 'true' : 'false', limit: 30 }, afterId)
  return `/staff/followup-plans?${query}`
}
async function loadFirst() { await loadPage(false) }
async function loadMore() {
  if (!hasMore.value || !nextAfterId.value || loading.value) return
  await loadPage(true)
}
async function loadPage(append: boolean) {
  if (!clinicId.value || !allowedClinics.value.some((clinic) => clinic.clinic_id === clinicId.value)) {
    items.value = []; error.value = '当前账号没有所选门店的前台权限。'; return
  }
  const requestGeneration = generation
  const token = sessionToken(); const accountId = props.identity.accountId; const clinic = clinicId.value
  const cursor = append ? nextAfterId.value : null
  loading.value = true; error.value = ''
  try {
    const response = await api<{ asOfDate: string; items: Row[]; hasMore: boolean; nextAfterId: string | null }>(endpoint(cursor))
    if (!current(token, accountId, clinic, requestGeneration)) return
    if (append && cursorPageChangedBusinessDate(asOfDate.value, response.asOfDate)) {
      notice.value = `服务端上海业务日从 ${asOfDate.value} 变为 ${response.asOfDate}；为避免跨日漏项，名单已从第一页重新读取。`
      items.value = []; hasMore.value = false; nextAfterId.value = null
      const first = await api<{ asOfDate: string; items: Row[]; hasMore: boolean; nextAfterId: string | null }>(endpoint(null))
      if (!current(token, accountId, clinic, requestGeneration)) return
      const merged = mergeCursorPage([], first, false)
      items.value = merged.items; asOfDate.value = merged.asOfDate; hasMore.value = merged.hasMore; nextAfterId.value = merged.nextAfterId
      return
    }
    const merged = mergeCursorPage(append ? items.value : [], response, append)
    items.value = merged.items; asOfDate.value = merged.asOfDate; hasMore.value = merged.hasMore; nextAfterId.value = merged.nextAfterId
  } catch (cause) {
    if (current(token, accountId, clinic, requestGeneration)) error.value = cause instanceof Error ? cause.message : '复诊名单读取失败。'
  } finally {
    if (current(token, accountId, clinic, requestGeneration)) loading.value = false
  }
}
function showDetail(item: Row) { selectedPlanId.value = String((item.plan as Row).followup_plan_id) }
</script>

<template>
  <section class="page-stack followup-workspace">
    <div class="page-heading"><div><p class="eyebrow">前台 · 复诊联系与逾期名单</p><h1>复诊跟进</h1><p class="muted">电话联系只追加实际记录；预约或联系不代表计划已完成。</p></div><label class="compact-label">授权负责门店<select v-model="clinicId"><option v-for="clinic in allowedClinics" :key="clinic.clinic_id" :value="clinic.clinic_id">{{ clinic.name }}</option></select></label></div>
    <NoticeBar tone="error" :title="error" @close="error = ''" /><NoticeBar tone="success" :title="notice" @close="notice = ''" />
    <section class="panel">
      <div class="followup-filters"><label>计划状态<select v-model="status"><option>待复诊</option><option>已完成</option><option>已取消</option><option value="all">全部状态</option></select></label><label class="followup-overdue-filter"><input v-model="overdueOnly" type="checkbox" :disabled="!['待复诊','all'].includes(status)" />仅显示服务端逾期</label><button class="button button-quiet" type="button" :disabled="loading" @click="loadFirst">↻ 从第一页刷新</button></div>
      <p class="muted followup-asof">服务端上海业务日期：<strong>{{ asOfDate || '读取中…' }}</strong> · 当前已加载 {{ items.length }} 条<span v-if="hasMore"> · 服务端仍有后页</span></p>
      <div v-if="loading && !items.length" class="loading-state"><span class="spinner"></span>正在读取授权门店的复诊计划…</div>
      <div v-else-if="!items.length && !error" class="empty-state"><div class="empty-icon">◷</div><strong>当前筛选没有复诊计划</strong><span>逾期按服务端asOfDate判断；建议日期加3天后，次日才逾期。</span></div>
      <div v-else-if="items.length" class="followup-plan-list">
        <article v-for="item in items" :key="String((item.plan as Row).followup_plan_id)" class="followup-plan-card">
          <div class="followup-plan-main"><div class="followup-plan-title"><strong>{{ (item.patient as Row).name }}</strong><small>{{ (item.patient as Row).patient_no }} · {{ (item.patient as Row).phone }}</small><span class="followup-plan-tags"><StatusBadge :value="String((item.plan as Row).status)" /><span v-if="(item.plan as Row).overdue === true" class="badge badge-warning">已逾期（服务端）</span><span v-else-if="(item.plan as Row).status === '待复诊'" class="badge badge-neutral">未逾期（服务端）</span></span></div><div class="followup-plan-date"><span>建议复诊</span><strong>{{ String((item.plan as Row).suggested_date) }}</strong><small>业务日 {{ asOfDate }}</small></div></div>
          <p class="followup-recommendation">{{ (item.plan as Row).recommendation }}</p>
          <div v-if="((item.appointments as { items?: Row[] } | undefined)?.items ?? []).length" class="followup-linked-appointments"><strong>有效后续预约 {{ (item.appointments as { items: Row[] }).items.length }} 条</strong><span v-for="appointment in (item.appointments as { items: Row[] }).items" :key="String(appointment.appointment_id)">#{{ appointment.appointment_id }} · {{ appointment.scheduled_start }} · {{ appointment.appointment_status }} / {{ appointment.treatment_status }}</span><small v-if="(item.appointments as { truncated?: boolean }).truncated">还有预约未展开；请打开计划详情。</small></div><p v-else class="muted">无当前有效后续预约关联；不据此判定计划完成。</p>
          <button class="button button-secondary" type="button" @click="showDetail(item)">计划详情与联系历史</button>
        </article>
      </div>
      <div v-if="hasMore" class="followup-load-more"><button class="button button-secondary" type="button" :disabled="loading" @click="loadMore">{{ loading ? '正在读取…' : `加载下一页（从计划 #${nextAfterId} 之后继续）` }}</button></div>
      <div v-if="loading && items.length" class="inline-loading"><span class="spinner"></span>正在追加服务端下一页…</div>
    </section>
    <FollowupPlanDetail v-if="selectedPlanId" :key="`${identity.accountId}:${clinicId}:${selectedPlanId}:staff`" mode="staff" :plan-id="selectedPlanId" :clinic-id="clinicId" :identity="identity" :clinics="clinics" @close="selectedPlanId = ''" @changed="loadFirst" />
  </section>
</template>
