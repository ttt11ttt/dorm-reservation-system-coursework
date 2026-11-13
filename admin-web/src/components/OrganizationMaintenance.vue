<script setup lang="ts">
import { ref } from 'vue'
import { api, formatShanghai } from '../api'
import type { OrganizationClinicDetail, OrganizationDentistDetail } from '../organization-readonly-core.js'

type Row = Record<string, unknown>
const props = defineProps<{ clinic?: OrganizationClinicDetail; dentist?: OrganizationDentistDetail; readOnly?: boolean }>()
const emit = defineEmits<{ changed: [] }>()
const mode = ref('')
const error = ref('')
const busy = ref(false)
const entry = props.clinic || props.dentist!
const route = props.clinic ? `/org/clinics/${props.clinic.clinicId}` : `/org/dentists/${props.dentist!.dentistId}`
const fields: Record<string,string> = props.clinic
  ? { name: entry.name, postalAddress: props.clinic.postalAddress, managerName: props.clinic.managerName, phone: entry.phone, email: entry.email, areaM2: props.clinic.areaM2 }
  : { name: entry.name, birthDate: props.dentist!.birthDate, homeAddress: props.dentist!.homeAddress, phone: entry.phone, email: entry.email }
const edit = ref(fields)
const labels: Record<string,string> = { name:'名称', postalAddress:'门店地址', managerName:'负责人', phone:'联系电话', email:'邮箱', areaM2:'面积（平方米）', birthDate:'出生日期', homeAddress:'住址' }
const reason = ref('')
const certificate = ref({ name:'', certificateNo:'', validFrom:'', validUntil:'' })
const qualifications = ref<{ certificates: Row[]; upcomingSchedules: Row[] }>()
const verification = ref<{ id:string; status:string; reason:string }>()

async function loadQualifications() {
  error.value = ''
  busy.value = true
  try { qualifications.value = await api(route+'/qualifications') }
  catch (e) { error.value = (e as Error).message }
  finally { busy.value = false }
}
async function save() {
  if (busy.value || props.readOnly) return
  busy.value = true
  error.value = ''
  try {
    if (mode.value==='edit') await api(route, { method:'PUT', body:edit.value })
    if (mode.value==='state') await api(route+'/state', { method:'POST', body:{ active:!entry.active, reason:reason.value.trim() } })
    emit('changed')
  } catch (e) { error.value = (e as Error).message }
  finally { busy.value = false }
}
async function saveCertificate() {
  if (busy.value || props.readOnly) return
  busy.value = true
  error.value = ''
  try {
    const check = verification.value
    if (check) {
      await api(route+'/qualifications/'+check.id+'/verification', { method:'POST', body:{ status:check.status, reason:check.reason.trim() } })
      verification.value = undefined
    } else {
      await api(route+'/qualifications', { method:'POST', body:certificate.value })
      certificate.value = { name:'', certificateNo:'', validFrom:'', validUntil:'' }
    }
    await loadQualifications()
  } catch (e) { error.value = (e as Error).message }
  finally { busy.value = false }
}
</script>

<template>
  <section class="org-maintenance">
    <div class="form-actions">
      <button v-if="!readOnly" data-readonly-write class="secondary" type="button" :disabled="busy" @click="mode='edit'">编辑资料</button>
      <button v-if="!readOnly" data-readonly-write class="secondary" type="button" :disabled="busy" @click="mode='state'">{{ entry.active ? '停用' : '启用' }}</button>
      <button v-if="dentist" class="secondary" type="button" :disabled="busy" @click="mode='certificates'; loadQualifications()">资质证书</button>
    </div>
    <p v-if="error" class="care-warning" role="alert">{{ error }}</p>
    <form v-if="mode==='edit' || mode==='state'" class="form-grid" @submit.prevent="save">
      <template v-if="mode==='edit'">
        <label v-for="(_,key) in edit" :key="key">{{ labels[key] }}<input v-model="edit[key]" :type="key==='birthDate' ? 'date' : 'text'" required :disabled="busy"></label>
      </template>
      <label v-else>{{ entry.active ? '停用原因' : '启用原因' }}<input v-model="reason" required maxlength="500" :disabled="busy"></label>
      <button data-readonly-write type="submit" :disabled="busy">确认保存</button>
    </form>
    <section v-if="mode==='certificates' && qualifications">
      <p v-if="!qualifications.certificates.length" class="muted">暂无证书记录。</p>
      <ul>
        <li v-for="row in qualifications.certificates" :key="String(row.holding_id)">
          #{{ row.holding_id }} · {{ row.name }} · {{ row.certificate_no }} · {{ row.valid_from }} 至 {{ row.valid_until || '长期' }} · {{ row.status }}
          <button v-if="!readOnly && row.status==='待核实'" data-readonly-write class="secondary" :disabled="busy" @click="verification={id:String(row.holding_id),status:'有效',reason:''}">核实有效</button>
          <button v-if="!readOnly && row.status!=='失效'" data-readonly-write class="secondary" :disabled="busy" @click="verification={id:String(row.holding_id),status:'失效',reason:''}">标记失效</button>
        </li>
      </ul>
      <form v-if="verification" class="form-grid" @submit.prevent="saveCertificate">
        <p>将证书 #{{ verification.id }} 标记为{{ verification.status }}。</p>
        <div v-if="verification.status==='失效' && qualifications.upcomingSchedules.length" class="care-warning">
          请核对下列未来出诊，若需停诊，可在「调班与停诊」处理。
          <ul><li v-for="row in qualifications.upcomingSchedules" :key="String(row.schedule_id)">{{ row.clinic_name }} · {{ formatShanghai(row.start_at) }}</li></ul>
        </div>
        <label>核实依据<input v-model="verification.reason" maxlength="500" required :disabled="busy"></label>
        <div class="form-actions"><button class="secondary" type="button" :disabled="busy" @click="verification=undefined">返回</button><button data-readonly-write :disabled="busy">确认</button></div>
      </form>
      <form v-else-if="!readOnly" class="form-grid" @submit.prevent="saveCertificate">
        <label>资质名称<input v-model="certificate.name" required maxlength="100" :disabled="busy"></label>
        <label>证书编号<input v-model="certificate.certificateNo" required maxlength="80" :disabled="busy"></label>
        <label>生效日期<input v-model="certificate.validFrom" type="date" required :disabled="busy"></label>
        <label>到期日期<input v-model="certificate.validUntil" type="date" :min="certificate.validFrom" :disabled="busy"></label>
        <button data-readonly-write type="submit" :disabled="busy">登记待核实证书</button>
      </form>
    </section>
  </section>
</template>

<style scoped>
.org-maintenance { display: grid; gap: 12px; }
.org-maintenance li { margin-bottom: 8px; }
</style>
