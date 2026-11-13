<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { api, formatShanghai, sessionToken } from '../api'

type Statistics = {
  asOf: string
  appointments: number
  cancellations: number
  lateCancellations: number
  noShows: number
  unverified: number
  clinicCancellations: number
  followups: number
  onTimeFollowups: number
  completedVisits: number
  operatingDays: number
}

const props = defineProps<{ clinicId: string; today: string }>()
const start = new Date(props.today + 'T00:00:00Z')
start.setUTCDate(start.getUTCDate() - 29)
const from = ref(start.toISOString().slice(0, 10))
const to = ref(props.today)
const compare = ref(false)
const data = ref<Statistics>()
const comparison = ref<Statistics>()
const comparisonPeriod = ref('')
const period = ref('')
const error = ref('')
const loading = ref(false)
let generation = 0

async function load() {
  const current = ++generation
  const token = sessionToken()
  const query = new URLSearchParams({ clinicId: props.clinicId, from: from.value, to: to.value })
  data.value = undefined
  comparison.value = undefined
  error.value = ''
  loading.value = true
  try {
    const result = await api<Statistics>(`/staff/workspace/statistics?${query}`)
    if (current !== generation || token !== sessionToken()) return
    data.value = result
    period.value = `${query.get('from')} 至 ${query.get('to')}`
    if (compare.value) {
      const first = new Date(query.get('from') + 'T00:00:00Z').getTime()
      const last = new Date(query.get('to') + 'T00:00:00Z').getTime()
      const previousStart = new Date(first - (last - first) - 86_400_000).toISOString().slice(0,10)
      const previousEnd = new Date(first - 86_400_000).toISOString().slice(0,10)
      query.set('from',previousStart)
      query.set('to',previousEnd)
      const previous = await api<Statistics>(`/staff/workspace/statistics?${query}`)
      if (current !== generation || token !== sessionToken()) return
      comparison.value = previous
      comparisonPeriod.value = `${previousStart} 至 ${previousEnd}`
    }
  } catch (err) {
    if (current === generation) error.value = err instanceof Error ? err.message : '统计读取失败'
  } finally {
    if (current === generation) loading.value = false
  }
}

function ratio(value: number, total: number, percent = true) {
  if (!total) return '无适用数据'
  return new Intl.NumberFormat('zh-CN', {
    style: percent ? 'percent' : 'decimal',
    maximumFractionDigits: percent ? 1 : 2,
  }).format(value / total)
}

watch(() => props.clinicId, load, { immediate: true })
onUnmounted(() => { generation++ })
</script>

<template>
  <section class="panel statistics">
    <div class="paneltitle"><h2>门店经营统计</h2></div>
    <form class="statistics-filter" @submit.prevent="load">
      <label>开始日期 <input v-model="from" type="date" required :max="to || today"></label>
      <label>结束日期 <input v-model="to" type="date" required :min="from" :max="today"></label>
      <label><input v-model="compare" type="checkbox" :disabled="loading">对比上一等长周期</label>
      <button class="secondary" type="submit" :disabled="loading">查询</button>
    </form>
    <p v-if="loading" class="muted" role="status">正在统计…</p>
    <p v-else-if="error" class="care-warning" role="alert">{{ error }}</p>
    <template v-if="data && !loading">
      <p class="muted">{{ period }} · 截至 {{ formatShanghai(data.asOf) }} 取数</p>
      <dl class="statistics-grid">
        <div><dt>患者取消率</dt><dd>{{ ratio(data.cancellations, data.appointments) }}</dd><small>{{ data.cancellations }} / {{ data.appointments }} 条预约</small></div>
        <div><dt>临时取消率</dt><dd>{{ ratio(data.lateCancellations, data.appointments) }}</dd><small>{{ data.lateCancellations }} 条不足 3 小时，{{ data.cancellations - data.lateCancellations }} 条提前取消</small></div>
        <div><dt>未到诊率</dt><dd>{{ ratio(data.noShows, data.appointments) }}</dd><small>{{ data.noShows }} 条已核实未到诊，{{ data.unverified }} 条待核实</small></div>
        <div><dt>按期复诊率</dt><dd>{{ ratio(data.onTimeFollowups, data.followups) }}</dd><small>{{ data.onTimeFollowups }} / {{ data.followups }} 项到期计划</small></div>
        <div><dt>日均完成就诊</dt><dd>{{ ratio(data.completedVisits, data.operatingDays, false) }}</dd><small>{{ data.completedVisits }} 人次 / {{ data.operatingDays }} 个营业日</small></div>
      </dl>
      <p v-if="comparison" class="muted">上期 {{ comparisonPeriod }} · 取消 {{ ratio(comparison.cancellations,comparison.appointments) }} · 临时取消 {{ ratio(comparison.lateCancellations,comparison.appointments) }} · 未到诊 {{ ratio(comparison.noShows,comparison.appointments) }} · 按期复诊 {{ ratio(comparison.onTimeFollowups,comparison.followups) }} · 日均完成 {{ ratio(comparison.completedVisits,comparison.operatingDays,false) }}</p>
      <p class="muted">按北京时间统计。预约以已结束的正式就诊预约为准，诊所撤销 {{ data.clinicCancellations }} 条另列。全部门店按实际开放日期去重计算营业天数。</p>
    </template>
  </section>
</template>

<style scoped>
.statistics { margin-top: 20px; }
.statistics-filter { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; }
.statistics-filter label { display: flex; align-items: center; gap: 8px; }
.statistics-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 12px; }
.statistics-grid > div { padding: 14px; background: #f3f8f6; border-radius: 10px; }
.statistics-grid dd { margin: 6px 0; font-size: 21px; font-weight: 700; }
.statistics-grid small { color: var(--muted); }
</style>
