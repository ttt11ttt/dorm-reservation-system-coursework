<script setup lang="ts">
import { ref, watch } from 'vue'
import { api } from '../api'

type Row = Record<string, unknown>
const props = defineProps<{ invoice: Row; readOnly?: boolean }>()
const emit = defineEmits<{ updated: [invoice: Row] }>()
const action = ref('')
const reason = ref('')
const busy = ref(false)
const error = ref('')
watch(() => props.invoice, () => { action.value = ''; reason.value = ''; error.value = '' })

async function submit() {
  if (busy.value || props.readOnly || !action.value) return
  const current = props.invoice
  busy.value = true
  error.value = ''
  try {
    const updated = await api<Row>(`/staff/invoices/${current.invoice_id}/status`, {
      method: 'POST', body: { action: action.value, reason: reason.value.trim(), expectedVersion: current.state_version },
    })
    if (props.invoice === current) emit('updated', updated)
  } catch (e) {
    if (props.invoice === current) error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="invoice-actions">
    <p>结算单 {{ invoice.invoice_no }} · {{ invoice.status }}</p>
    <template v-if="!readOnly">
      <div v-if="!action" class="form-actions">
        <button v-if="invoice.status==='草稿'" class="secondary" data-readonly-write type="button" @click="action='发出'">核对并发出</button>
        <button v-if="invoice.status!=='作废'" class="secondary" data-readonly-write type="button" @click="action='作废'">作废结算</button>
        <button v-else class="secondary" data-readonly-write type="button" @click="action='恢复草稿'">恢复原单为草稿</button>
      </div>
      <form v-else class="form-grid" @submit.prevent="submit">
        <label v-if="action!=='发出'">{{ action }}原因 <input v-model="reason" maxlength="500" required :disabled="busy"></label>
        <p v-else class="muted">请核对明细与金额，确认后患者可以查询此单。</p>
        <div class="form-actions">
          <button class="secondary" type="button" :disabled="busy" @click="action=''; reason=''">返回</button>
          <button data-readonly-write type="submit" :disabled="busy">确认{{ action }}</button>
        </div>
      </form>
    </template>
    <p v-if="error" class="care-warning" role="alert">{{ error }}</p>
  </section>
</template>
