<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { api, formatShanghai } from '../api'

type Schedule = { schedule_id: string; dentist_name: string; start_at: string; end_at: string; row_version: number }
type Appointment = { appointment_no: string; scheduled_start: string; status: string }
type Impact = { appointments: Appointment[]; guests: Appointment[] }
const props = defineProps<{ clinicId: string; readOnly?: boolean }>()
const today = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(new Date())
const date = ref(today)
const rows = ref<Schedule[]>([])
const selected = ref<Schedule>()
const action = ref('stop')
const end = ref('')
const reason = ref('')
const impact = ref<Impact>()
const error = ref('')
const message = ref('')
const busy = ref(false)
let generation = 0

async function load() {
  const current = ++generation
  selected.value = undefined
  impact.value = undefined
  rows.value = []
  error.value = ''
  busy.value = false
  if (!props.clinicId || !date.value) return
  busy.value = true
  try {
    const result = await api<Schedule[]>(`/org/clinics/${props.clinicId}/upcoming-schedules?date=${date.value}`)
    if (current === generation) rows.value = result
  } catch (e) {
    if (current === generation) error.value = String((e as Error).message)
  } finally {
    if (current === generation) busy.value = false
  }
}

function choose(row: Schedule) {
  selected.value = row
  end.value = ''
  reason.value = ''
  action.value = 'stop'
  impact.value = undefined
  error.value = ''
  message.value = ''
}

async function submit(save: boolean) {
  if (busy.value || !selected.value || (save && (!reason.value.trim() || props.readOnly || !impact.value))) return
  if (action.value === 'shorten' && !end.value) { error.value = '请选择新的结束时间。'; return }
  const current = generation
  const path = `/org/schedules/${selected.value.schedule_id}`
  const endAt = action.value === 'shorten' ? `${end.value}:00+08:00` : null
  error.value = ''
  busy.value = true
  try {
    if (save) {
      await api(path + '/change', { method: 'POST', body: { expectedVersion: String(selected.value.row_version), endAt, reason: reason.value.trim() } })
      if (current === generation) {
        message.value = '排班已更新。'
        await load()
      }
    } else {
      const result = await api<Impact>(path + '/affected-appointments' + (endAt ? '?endAt=' + encodeURIComponent(endAt) : ''))
      if (current === generation) impact.value = result
    }
  } catch (e) {
    if (current === generation) { impact.value = undefined; error.value = String((e as Error).message) }
  } finally {
    if (current === generation) busy.value = false
  }
}

watch([() => props.clinicId, date], load, { immediate: true })
watch([action, end], () => { impact.value = undefined })
onUnmounted(() => { generation++ })
</script>

<template>
  <section class="panel schedule-change">
    <h2>调班与停诊</h2>
    <p class="muted">选择门店、日期和出诊时段。先核对影响范围，再到预约管理协调原有预约。</p>
    <label>出诊日期 <input v-model="date" type="date" :min="today" :disabled="busy"></label>
    <p v-if="error" class="care-warning" role="alert">{{ error }}</p>
    <p v-if="message" role="status">{{ message }}</p>
    <p v-if="!busy && !rows.length && !error" class="muted">这天暂无可调整的出诊。</p>
    <table v-if="rows.length">
      <thead><tr><th>牙医</th><th>出诊时间</th><th>操作</th></tr></thead>
      <tbody><tr v-for="row in rows" :key="row.schedule_id">
        <td>{{ row.dentist_name }}</td><td>{{ formatShanghai(row.start_at) }} 至 {{ formatShanghai(row.end_at) }}</td>
        <td><button class="secondary" :disabled="busy" @click="choose(row)">调整</button></td>
      </tr></tbody>
    </table>
    <form v-if="selected" class="change-form" @submit.prevent="submit(true)">
      <h3>{{ selected.dentist_name }} · {{ formatShanghai(selected.start_at) }}</h3>
      <label>处理方式 <select v-model="action" :disabled="busy"><option value="stop">撤销该时段</option><option value="shorten">提前结束</option></select></label>
      <label v-if="action==='shorten'">新的结束时间 <input v-model="end" type="datetime-local" required :disabled="busy"></label>
      <button class="secondary" type="button" :disabled="busy" @click="submit(false)">查看受影响预约</button>
      <template v-if="impact">
        <ul v-if="impact.appointments.length || impact.guests.length">
          <li v-for="item in impact.appointments" :key="'a'+item.appointment_no">预约 {{ item.appointment_no }} · {{ formatShanghai(item.scheduled_start) }} · {{ item.status }}</li>
          <li v-for="item in impact.guests" :key="'g'+item.appointment_no">游客预约 {{ item.appointment_no }} · {{ formatShanghai(item.scheduled_start) }} · {{ item.status }}</li>
        </ul>
        <p v-if="impact.appointments.length || impact.guests.length" class="care-warning">请先协调处理上述预约，然后重新核对。</p>
        <template v-else>
          <p>没有受影响的有效预约。</p>
          <label v-if="!readOnly">调整原因 <input v-model="reason" required maxlength="500" :disabled="busy"></label>
          <button v-if="!readOnly" data-readonly-write :disabled="busy" type="submit">确认调整</button>
        </template>
      </template>
    </form>
  </section>
</template>

<style scoped>
.schedule-change, .change-form { display: grid; gap: 12px; justify-items: start; }
.change-form { border-top: 1px solid var(--line); padding-top: 14px; width: 100%; }
</style>
