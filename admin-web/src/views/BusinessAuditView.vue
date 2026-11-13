<script setup lang="ts">
import { ref } from 'vue'
import { api, formatShanghai } from '../api'

type Row = {
  audit_id: string; actor_account_id: string; action: string; reason: string | null;
  before_value: string | null; after_value: string | null; happened_at: string;
}
const types = [
  ['appointment','正式预约'], ['guest_reservation','游客预约'], ['treatment','治疗明细'],
  ['invoice','结算单'], ['expense','费用'], ['org_schedule','出诊'], ['org_opening','门店开放'], ['org_clinic','门店'],
]
const type = ref('appointment')
const id = ref('')
const rows = ref<Row[]>()
const error = ref('')
const busy = ref(false)

async function search() {
  rows.value = undefined
  error.value = ''
  busy.value = true
  try {
    rows.value = await api<Row[]>('/staff/business-audit?' + new URLSearchParams({ type: type.value, objectId: id.value.trim() }))
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}

function pretty(value: string | null) {
  if (!value) return '—'
  try { return JSON.stringify(JSON.parse(value), null, 2) } catch { return value }
}
</script>

<template>
  <section class="panel business-audit">
    <h2>业务操作记录</h2>
    <p class="muted">输入业务对象的内部 ID，查看最近 30 条变更。</p>
    <form class="form-actions" @submit.prevent="search">
      <label>类型 <select v-model="type" :disabled="busy"><option v-for="[value,label] in types" :key="value" :value="value">{{ label }}</option></select></label>
      <label>记录 ID <input v-model="id" inputmode="numeric" pattern="[1-9][0-9]{0,18}" required :disabled="busy"></label>
      <button class="secondary" :disabled="busy" type="submit">查询</button>
    </form>
    <p v-if="error" class="care-warning" role="alert">{{ error }}</p>
    <p v-if="rows && !rows.length" class="muted">没有找到变更记录。</p>
    <details v-for="row in rows" :key="row.audit_id">
      <summary>{{ formatShanghai(row.happened_at) }} · {{ row.action }} · 操作人 #{{ row.actor_account_id }}</summary>
      <p v-if="row.reason">原因 {{ row.reason }}</p>
      <div class="audit-values"><div><strong>变更前</strong><pre>{{ pretty(row.before_value) }}</pre></div><div><strong>变更后</strong><pre>{{ pretty(row.after_value) }}</pre></div></div>
    </details>
  </section>
</template>

<style scoped>
.business-audit details { padding: 12px 0; border-top: 1px solid var(--line); }
.business-audit summary { cursor: pointer; }
.audit-values { display: grid; grid-template-columns: repeat(auto-fit,minmax(210px,1fr)); gap: 12px; }
.audit-values pre { background: #f3f8f6; padding: 10px; white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
