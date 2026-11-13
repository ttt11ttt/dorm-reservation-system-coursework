<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { api, sessionToken, setSessionToken } from '../api'
import type { Clinic, Identity } from '../api'
import LoginView from './LoginView.vue'
import FrontDeskView from './FrontDeskView.vue'
import DentistView from './DentistView.vue'
import ReminderAdminView from './ReminderAdminView.vue'
import ExpenseAdminView from './ExpenseAdminView.vue'
import QuarterReportView from './QuarterReportView.vue'
import AccountAdminView from './AccountAdminView.vue'
import OrganizationView from './OrganizationView.vue'
import { chooseStaffLanding, staffWorkspaceAccess } from '../staff-workspace-core.js'
import type { StaffWorkspace } from '../staff-workspace-core.js'
import { createIdentityRefreshLane } from '../staff-admin-ui-coordination.js'

const identity = ref<Identity | null>(null)
const clinics = ref<Clinic[]>([])
const page = ref<StaffWorkspace>('front')
const loading = ref(true)
const loginError = ref('')
const appError = ref('')
const identityLane = createIdentityRefreshLane()
let loginGeneration = 0
const now = new Date()
const dateLabel = new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' }).format(now)
const grants = computed(() => identity.value?.grants ?? [])
const roleNames = computed(() => [...new Set(grants.value.map((grant) => grant.role_code).filter(Boolean))])
const workspaceAccess = computed(() => staffWorkspaceAccess(identity.value))
const canAccountAdmin = computed(() => workspaceAccess.value.accountManager)
const canOrganization = computed(() => workspaceAccess.value.organization)
const canFront = computed(() => workspaceAccess.value.front)
const canDentist = computed(() => workspaceAccess.value.dentist)
const canReminders = computed(() => workspaceAccess.value.reminders)
const canExpenses = computed(() => workspaceAccess.value.expenses)
const canQuarter = computed(() => workspaceAccess.value.quarter)
const clinicScopedPage = computed(() => ['front', 'dentist', 'reminders', 'expenses'].includes(page.value))
const workspaceScopeKey = computed(() => JSON.stringify([identity.value?.accountId, identity.value?.kind, identity.value?.dentistId, identity.value?.grants, clinics.value.map((clinic) => clinic.clinic_id)]))
const isDentist = computed(() => page.value === 'dentist' && canDentist.value)
const isReminderPage = computed(() => page.value === 'reminders' && canReminders.value)
const isExpensePage = computed(() => page.value === 'expenses' && canExpenses.value)
const isQuarterPage = computed(() => page.value === 'quarter' && canQuarter.value)
const isAccountAdminPage = computed(() => page.value === 'account-admin' && canAccountAdmin.value)
const isOrganizationPage = computed(() => page.value === 'organization' && canOrganization.value)
const pageTitle = computed(() => isDentist.value ? '牙医接诊' : isReminderPage.value ? '提醒管理' : isExpensePage.value ? '原始费用' : isQuarterPage.value ? '季度经营报表' : isAccountAdminPage.value ? '账号管理' : isOrganizationPage.value ? '门店与牙医出诊' : '预约接诊')

onMounted(async () => {
  window.addEventListener('adc:session-expired', handleSessionExpired)
  const token = sessionStorage.getItem('adc.staff.session')
  if (!token) { clearWorkspace(); loading.value = false; return }
  const captured = identityLane.begin(token)
  try { await loadIdentity(captured) }
  catch (error) {
    if (identityLane.isCurrent(captured, sessionToken())) {
      setSessionToken(null); identityLane.invalidate(); clearWorkspace(); loading.value = false
      appError.value = error instanceof Error ? error.message : '会话无效，请重新登录。'
    }
  } finally {
    if (identityLane.isCurrent(captured, sessionToken())) loading.value = false
  }
})
onUnmounted(() => window.removeEventListener('adc:session-expired', handleSessionExpired))
function clearWorkspace() {
  identity.value = null
  clinics.value = []
  page.value = 'front'
}
function handleSessionExpired() {
  identityLane.invalidate()
  loginGeneration += 1
  setSessionToken(null)
  clearWorkspace()
  loading.value = false
  appError.value = '员工会话已失效，请重新登录。当前页面资料已清除。'
}
async function loadIdentity(captured: ReturnType<typeof identityLane.begin>) {
  const nextIdentity = await api<Identity>('/me')
  if (!identityLane.isCurrent(captured, sessionToken())) return false
  const nextClinics = await api<Clinic[]>('/clinics')
  if (!identityLane.isCurrent(captured, sessionToken())) return false
  const access = staffWorkspaceAccess(nextIdentity)
  if (!access.front && !access.dentist && !access.reminders && !access.expenses && !access.quarter && !access.accountManager && !access.organization) throw new Error('当前员工账号没有本工作台支持的岗位授权。')
  identity.value = nextIdentity
  clinics.value = nextClinics
  page.value = chooseStaffLanding(identity.value, clinics.value.length)
  return true
}
async function refreshIdentity() {
  const token = sessionToken()
  if (!token) return
  const captured = identityLane.begin(token)
  try {
    const nextIdentity = await api<Identity>('/me')
    if (!identityLane.isCurrent(captured, sessionToken())) return
    const nextClinics = await api<Clinic[]>('/clinics')
    if (!identityLane.isCurrent(captured, sessionToken())) return
    const nextAccess = staffWorkspaceAccess(nextIdentity)
    if (!nextAccess.front && !nextAccess.dentist && !nextAccess.reminders && !nextAccess.expenses && !nextAccess.quarter && !nextAccess.accountManager && !nextAccess.organization) throw new Error('当前员工账号已没有本工作台支持的岗位授权。')
    identity.value = nextIdentity
    clinics.value = nextClinics
    const stillAllowed = page.value === 'front' ? nextAccess.front
      : page.value === 'dentist' ? nextAccess.dentist
        : page.value === 'reminders' ? nextAccess.reminders
          : page.value === 'expenses' ? nextAccess.expenses
            : page.value === 'quarter' ? nextAccess.quarter
              : page.value === 'account-admin' ? nextAccess.accountManager
                : page.value === 'organization' ? nextAccess.organization : false
    if (!stillAllowed) page.value = chooseStaffLanding(nextIdentity, nextClinics.length)
  } catch (error) {
    if (!identityLane.isCurrent(captured, sessionToken())) return
    setSessionToken(null)
    identityLane.invalidate()
    clearWorkspace()
    appError.value = error instanceof Error ? error.message : '身份刷新失败，请重新登录。'
  }
}
async function login(credentials: { loginName: string; password: string }) {
  const attempt = ++loginGeneration
  identityLane.invalidate()
  loginError.value = ''
  appError.value = ''
  clearWorkspace()
  setSessionToken(null)
  let captured: ReturnType<typeof identityLane.begin> | null = null
  try {
    const session = await api<{ token: string }>('/staff/sessions', { method: 'POST', body: credentials, auth: false })
    if (attempt !== loginGeneration || sessionToken() !== null) return
    setSessionToken(session.token)
    captured = identityLane.begin(session.token)
    await loadIdentity(captured)
  } catch (error) {
    if (attempt !== loginGeneration) return
    if (captured && !identityLane.isCurrent(captured, sessionToken())) return
    setSessionToken(null)
    identityLane.invalidate()
    clearWorkspace()
    loginError.value = error instanceof Error ? error.message : '登录失败，请检查账号信息。'
  }
}
async function logout() {
  const token = sessionToken()
  const endSession = token ? api('/sessions/current', { method: 'DELETE' }).catch(() => undefined) : Promise.resolve()
  loginGeneration += 1
  identityLane.invalidate()
  setSessionToken(null); clearWorkspace(); loginError.value = ''; appError.value = ''
  await endSession
}
</script>

<template>
  <div v-if="loading" class="boot-screen"><span class="spinner"></span><span>正在验证员工会话…</span></div>
  <LoginView v-else-if="!identity" :error="loginError || appError" @login="login" />
  <div v-else class="app-shell">
    <aside class="sidebar" aria-label="主导航">
      <div class="sidebar-brand"><span class="brand-mark brand-mark-small">ADC</span><span><strong>和安口腔</strong><small>员工工作台</small></span></div>
      <div class="clinic-switcher"><span>门店访问范围</span><strong>{{ clinics.length === 0 ? '当前无可访问门店' : clinics.length === 1 ? clinics[0]?.name : '多门店访问' }}</strong><small>{{ clinics.length }} 家门店由服务端返回</small></div>
      <nav class="main-nav">
        <p class="nav-label">工作空间</p>
        <button v-if="canFront" class="nav-item" :class="{ active: page === 'front' }" @click="page = 'front'"><span class="nav-icon">▤</span><span>预约接诊</span><span v-if="page === 'front'" class="nav-active-dot"></span></button>
        <button v-if="canDentist" class="nav-item" :class="{ active: page === 'dentist' }" @click="page = 'dentist'"><span class="nav-icon">✚</span><span>牙医接诊</span><span v-if="page === 'dentist'" class="nav-active-dot"></span></button>
        <button v-if="canReminders" class="nav-item" :class="{ active: page === 'reminders' }" @click="page = 'reminders'"><span class="nav-icon">◷</span><span>提醒管理</span><span v-if="page === 'reminders'" class="nav-active-dot"></span></button>
        <button v-if="canExpenses" class="nav-item" :class="{ active: page === 'expenses' }" @click="page = 'expenses'"><span class="nav-icon">¤</span><span>原始费用</span><span v-if="page === 'expenses'" class="nav-active-dot"></span></button>
        <button v-if="canQuarter" class="nav-item" :class="{ active: page === 'quarter' }" @click="page = 'quarter'"><span class="nav-icon">▥</span><span>季度经营报表</span><span v-if="page === 'quarter'" class="nav-active-dot"></span></button>
        <button v-if="canAccountAdmin" class="nav-item" :class="{ active: page === 'account-admin' }" @click="page = 'account-admin'"><span class="nav-icon">⌘</span><span>账号管理</span><span v-if="page === 'account-admin'" class="nav-active-dot"></span></button>
        <button v-if="canOrganization" class="nav-item" :class="{ active: page === 'organization' }" @click="page = 'organization'"><span class="nav-icon">⌂</span><span>门店与牙医出诊</span><span v-if="page === 'organization'" class="nav-active-dot"></span></button>
        <p class="nav-label nav-label-lower">使用范围</p>
        <div class="scope-card"><span class="scope-shield">✓</span><span><strong>当前授权</strong><span class="role-chip-list"><small v-for="role in roleNames" :key="role">{{ role }}</small></span><small>岗位与数据范围由服务端确认。</small></span></div>
      </nav>
      <div class="sidebar-bottom"><div class="user-card"><span class="avatar avatar-inverse">{{ identity.kind === '员工' ? '职' : '患' }}</span><span class="user-meta"><strong>员工账号 #{{ identity.accountId }}</strong><small>{{ grants.map((item) => item.role_code).join(' · ') }}</small></span><button class="icon-button logout-icon" title="退出登录" aria-label="退出登录" @click="logout">↪</button></div><small class="sidebar-version">HEAN DENTAL · 诊所工作台</small></div>
    </aside>
    <main class="workspace">
      <header class="topbar"><div class="mobile-brand"><span class="brand-mark brand-mark-small">ADC</span><strong>和安口腔</strong></div><div class="breadcrumbs"><span>员工工作空间</span><span class="crumb-separator">/</span><strong>{{ pageTitle }}</strong></div><div class="topbar-right"><span class="topbar-date">{{ dateLabel }}</span><span class="topbar-separator"></span><span class="connection-state"><span class="dot dot-green"></span>员工会话有效</span><button class="button button-quiet topbar-logout" @click="logout">退出</button></div></header>
      <div v-if="appError" class="workspace-alert"><span>{{ appError }}</span><button class="icon-button" @click="appError = ''">×</button></div>
      <div class="workspace-content"><section v-if="clinicScopedPage && clinics.length === 0" class="panel empty-state"><div class="empty-icon">⌂</div><strong>当前没有可访问门店</strong><span>这个岗位入口仍保留。请由账号管理员配置门店范围，或由连锁运营人员在组织资料中检查门店；服务端确认范围后重新加载身份。空范围下不会读取或提交门店业务。</span></section><FrontDeskView v-else-if="page === 'front' && canFront" :key="workspaceScopeKey" :clinics="clinics" :identity="identity" /><DentistView v-else-if="page === 'dentist' && canDentist" :key="workspaceScopeKey" :clinics="clinics" :identity="identity" /><ReminderAdminView v-else-if="page === 'reminders' && canReminders" :key="workspaceScopeKey" :clinics="clinics" :identity="identity" /><ExpenseAdminView v-else-if="page === 'expenses' && canExpenses" :key="workspaceScopeKey" :clinics="clinics" :identity="identity" /><QuarterReportView v-else-if="page === 'quarter' && canQuarter" :identity="identity" /><AccountAdminView v-else-if="page === 'account-admin' && canAccountAdmin" :identity="identity" :clinics="clinics" @identity-refresh="refreshIdentity" /><OrganizationView v-else-if="page === 'organization' && canOrganization" :identity="identity" /><section v-else class="panel empty-state"><strong>此账号没有可用工作台</strong><span>请联系账号管理员确认真实岗位授权。</span></section></div>
    </main>
    <nav class="mobile-nav" aria-label="移动导航"><button v-if="canFront" :class="{ active: page === 'front' }" @click="page = 'front'"><span>▤</span>预约接诊</button><button v-if="canDentist" :class="{ active: page === 'dentist' }" @click="page = 'dentist'"><span>✚</span>牙医接诊</button><button v-if="canReminders" :class="{ active: page === 'reminders' }" @click="page = 'reminders'"><span>◷</span>提醒管理</button><button v-if="canExpenses" :class="{ active: page === 'expenses' }" @click="page = 'expenses'"><span>¤</span>原始费用</button><button v-if="canQuarter" :class="{ active: page === 'quarter' }" @click="page = 'quarter'"><span>▥</span>季度报表</button><button v-if="canAccountAdmin" :class="{ active: page === 'account-admin' }" @click="page = 'account-admin'"><span>⌘</span>账号管理</button><button v-if="canOrganization" :class="{ active: page === 'organization' }" @click="page = 'organization'"><span>⌂</span>门店与牙医出诊</button><button @click="logout"><span>↪</span>退出</button></nav>
  </div>
</template>
