<script setup lang="ts">
import { ref } from 'vue'
import { api, operationId } from '../api'

const props = defineProps<{
  expense: { expenseId: string; status: string; adjustsExpenseId: string | null; occurredOn: string }
  allocations: { allocationId: string; dentistId: string; amount: string }[]
}>()
const emit = defineEmits<{ changed: [expenseId: string] }>()
const mode = ref('')
const reason = ref('')
const date = ref(props.expense.occurredOn)
const amounts = ref<Record<string,string>>({})
const error = ref('')
const busy = ref(false)
const adjustmentId = operationId('ADJ').slice(4)

async function submit() {
  if (busy.value || !mode.value || !reason.value.trim()) return
  const lines = props.allocations.filter(row => amounts.value[row.allocationId]?.trim()).map(row => {
    const [whole, fraction = ''] = amounts.value[row.allocationId]!.trim().split('.')
    return { allocationId: row.allocationId, amount: whole + '.' + fraction.padEnd(2,'0') }
  })
  if (mode.value==='reversal' && !lines.length) { error.value = '至少填写一项冲回金额。'; return }
  busy.value = true
  error.value = ''
  try {
    const id = props.expense.expenseId
    await api(`/finance/expenses/${id}/${mode.value}`, { method: 'POST', body: mode.value==='void'
      ? { reason: reason.value.trim() } : { adjustmentId, occurredOn: date.value, reason: reason.value.trim(), lines } })
    emit('changed', mode.value==='void' ? id : adjustmentId)
    mode.value = ''
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section>
    <div v-if="!mode" class="form-actions">
      <button v-if="expense.status==='确认' && !expense.adjustsExpenseId && allocations.length" data-readonly-write class="secondary" type="button" @click="mode='reversal'">登记负数冲回</button>
      <button v-if="expense.status!=='作废'" data-readonly-write class="secondary" type="button" @click="mode='void'">作废费用</button>
    </div>
    <form v-else class="form-grid" @submit.prevent="submit">
      <template v-if="mode==='reversal'">
        <label>调整发生日 <input v-model="date" type="date" :min="expense.occurredOn" required :disabled="busy"></label>
        <label v-for="row in allocations" :key="row.allocationId">
          牙医 #{{ row.dentistId }} · 原归属 ¥{{ row.amount }}
          <input v-model="amounts[row.allocationId]" placeholder="填写需冲回的金额" inputmode="decimal" pattern="[0-9]{1,12}(\.[0-9]{1,2})?" :disabled="busy">
        </label>
      </template>
      <label>办理原因 <input v-model="reason" maxlength="500" required :disabled="busy"></label>
      <div class="form-actions">
        <button class="secondary" type="button" :disabled="busy" @click="mode=''; error=''">返回</button>
        <button data-readonly-write type="submit" :disabled="busy">{{ mode==='void' ? '确认作废' : '确认冲回' }}</button>
      </div>
    </form>
    <p v-if="error" class="care-warning" role="alert">{{ error }}</p>
  </section>
</template>
