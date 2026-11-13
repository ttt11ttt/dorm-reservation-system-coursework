<script setup lang="ts">
import { computed,onMounted,onUnmounted,ref } from 'vue'
import type { Directive } from 'vue'
import { api,sessionToken,setSessionToken,setReadOnlySession } from '../api'
import type { Clinic,Identity } from '../api'
import { staffWorkspaceAccess } from '../staff-workspace-core.js'
import { createIdentityRefreshLane } from '../staff-admin-ui-coordination.js'
import LoginView from './LoginView.vue'
import ApprovedWorkspace from './ApprovedWorkspace.vue'
import FrontDeskView from './FrontDeskView.vue'
import DentistView from './DentistView.vue'
import FrontDeskFollowups from '../components/FrontDeskFollowups.vue'
import OrganizationView from './OrganizationView.vue'
import OrganizationScheduleView from './OrganizationScheduleView.vue'
import ScheduleChangeView from './ScheduleChangeView.vue'
import BusinessAuditView from './BusinessAuditView.vue'
import PatientWechatBindingView from './PatientWechatBindingView.vue'
import AccountAdminView from './AccountAdminView.vue'
import ReminderAdminView from './ReminderAdminView.vue'
import ExpenseAdminView from './ExpenseAdminView.vue'
import QuarterReportView from './QuarterReportView.vue'
const vReadonlyUi:Directive<HTMLElement,boolean|undefined>={
 mounted(el,binding){if(!binding.value)return;const disable=()=>el.querySelectorAll<HTMLButtonElement>('button[data-readonly-write]').forEach(button=>{button.disabled=true;button.title='只读访客不能新增、修改或删除'});disable();const observer=new MutationObserver(disable);observer.observe(el,{childList:true,subtree:true});(el as HTMLElement&{readonlyObserver?:MutationObserver}).readonlyObserver=observer},
 unmounted(el){(el as HTMLElement&{readonlyObserver?:MutationObserver}).readonlyObserver?.disconnect()}
}
const identity=ref<Identity|null>(null),clinics=ref<Clinic[]>([]),page=ref('dashboard'),clinicId=ref('0'),loading=ref(true),loginError=ref(''),settingsTab=ref('accounts')
const access=computed(()=>staffWorkspaceAccess(identity.value)),lane=createIdentityRefreshLane();let generation=0
const allMenus=[['dashboard','工作台'],['scheduling','排班管理'],['appointments','预约管理'],['queue','患者队列'],['consultation','接诊与治疗'],['billing','就诊结算'],['followups','复诊跟进'],['dentists','牙医管理'],['patients','患者管理'],['projects','诊疗项目'],['applications','调班与停诊'],['clinics','门店信息'],['settings','账户设置']]
// Linear stroke icons (18px) keep the navigation visually consistent.
const icons:Record<string,string>={
 dashboard:'<svg viewBox="0 0 24 24"><rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/></svg>',
 scheduling:'<svg viewBox="0 0 24 24"><rect x="3" y="5" width="18" height="16" rx="2.5"/><path d="M8 3v4M16 3v4M3 10.5h18"/><path d="M7.5 14.5h3v3h-3z"/></svg>',
 appointments:'<svg viewBox="0 0 24 24"><path d="M8 6h13M8 12h13M8 18h13"/><circle cx="3.6" cy="6" r="1"/><circle cx="3.6" cy="12" r="1"/><circle cx="3.6" cy="18" r="1"/></svg>',
 queue:'<svg viewBox="0 0 24 24"><path d="M16 20v-1.6a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4V20"/><circle cx="9" cy="7" r="3.4"/><path d="M22 20v-1.6a4 4 0 0 0-3-3.87"/><path d="M16.5 4.2a3.4 3.4 0 0 1 0 6.6"/></svg>',
 consultation:'<svg viewBox="0 0 24 24"><rect x="3" y="3" width="18" height="18" rx="3"/><path d="M12 8v8M8 12h8"/></svg>',
 billing:'<svg viewBox="0 0 24 24"><path d="M14 2.5H7A2.5 2.5 0 0 0 4.5 5v14A2.5 2.5 0 0 0 7 21.5h10a2.5 2.5 0 0 0 2.5-2.5V8z"/><path d="M14 2.5V8h5.5"/><path d="M8.5 13h7M8.5 17h4.5"/></svg>',
 followups:'<svg viewBox="0 0 24 24"><path d="M20.5 12a8.5 8.5 0 1 1-2.6-6.1"/><path d="M20.5 3.5V9H15"/><path d="M12 8v4.5l3 1.8"/></svg>',
 dentists:'<svg viewBox="0 0 24 24"><circle cx="12" cy="8.5" r="4.5"/><path d="M8.6 12.4 7 21.5l5-2.8 5 2.8-1.6-9.1"/></svg>',
 patients:'<svg viewBox="0 0 24 24"><path d="M20 21v-1.8a4.2 4.2 0 0 0-4.2-4.2H8.2A4.2 4.2 0 0 0 4 19.2V21"/><circle cx="12" cy="7.5" r="4"/></svg>',
 projects:'<svg viewBox="0 0 24 24"><path d="M21 8.2 12 3 3 8.2v7.6L12 21l9-5.2z"/><path d="M3 8.2 12 13.4l9-5.2M12 13.4V21"/></svg>',
 applications:'<svg viewBox="0 0 24 24"><path d="M7 3.5v14M7 21l-3.5-3.5M17 20.5v-14M17 3l3.5 3.5"/></svg>',
 clinics:'<svg viewBox="0 0 24 24"><path d="M3.5 10.5 12 3.5l8.5 7V20a1.5 1.5 0 0 1-1.5 1.5h-4.5v-6.5h-5v6.5H5A1.5 1.5 0 0 1 3.5 20z"/></svg>',
 settings:'<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="3.2"/><path d="M19.2 14.4a1.6 1.6 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.6 1.6 0 0 0-2.7 1.1 2 2 0 1 1-4 0 1.6 1.6 0 0 0-2.7-1.1l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.6 1.6 0 0 0-1.1-2.7 2 2 0 1 1 0-4 1.6 1.6 0 0 0 1.1-2.7l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.6 1.6 0 0 0 2.7-1.1 2 2 0 1 1 4 0 1.6 1.6 0 0 0 2.7 1.1l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.6 1.6 0 0 0 1.1 2.7 2 2 0 1 1 0 4 1.6 1.6 0 0 0-1.5 1z"/></svg>'
}
function allowed(p:string){if(['appointments','queue','billing','patients'].includes(p))return access.value.front;if(p==='consultation'||p==='followups')return access.value.front||access.value.dentist;if(['scheduling','dentists','applications','clinics'].includes(p))return access.value.organization;return true}
const menus=computed(()=>allMenus.filter(m=>allowed(m[0]!))),title=computed(()=>allMenus.find(m=>m[0]===page.value)?.[1]||'工作台')
const navGroups=computed(()=>[
  {name:'日常接诊',keys:['dashboard','appointments','queue','consultation','billing','followups']},
  {name:'诊所运营',keys:['scheduling','applications','dentists','patients','projects','clinics']},
  {name:'系统',keys:['settings']},
].map(group=>({...group,items:group.keys.flatMap(key=>menus.value.filter(item=>item[0]===key))})).filter(group=>group.items.length))
const selectedClinics=computed(()=>clinicId.value==='0'?clinics.value:clinics.value.filter(c=>c.clinic_id===clinicId.value))
const scopeKey=computed(()=>JSON.stringify([identity.value?.accountId,identity.value?.grants,clinicId.value]))
const dateLabel=new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',year:'numeric',month:'long',day:'numeric',weekday:'long'}).format(new Date())
function navigate(p:string){if(allowed(p)){page.value=p;window.scrollTo(0,0)}}
async function loadIdentity(){const token=sessionToken();if(!token)return;const current=lane.begin(token);const me=await api<Identity>('/me');if(!lane.isCurrent(current,sessionToken()))return;setReadOnlySession(me.readOnly===true);const list=await api<Clinic[]>('/clinics');if(!lane.isCurrent(current,sessionToken()))return;identity.value=me;clinics.value=list;page.value='dashboard';clinicId.value='0'}
async function login(body:{loginName:string;password:string}){const attempt=++generation;loginError.value='';lane.invalidate();identity.value=null;setSessionToken(null);try{const result=await api<{token:string}>('/staff/sessions',{method:'POST',body,auth:false});if(attempt!==generation)return;setSessionToken(result.token);await loadIdentity()}catch(e){if(attempt!==generation)return;setSessionToken(null);identity.value=null;loginError.value=e instanceof Error?e.message:'登录失败'}}
async function logout(){const token=sessionToken();generation++;lane.invalidate();identity.value=null;clinics.value=[];const end=token?api('/sessions/current',{method:'DELETE'}).catch(()=>undefined):Promise.resolve();setSessionToken(null);await end}
function expired(){generation++;lane.invalidate();setSessionToken(null);identity.value=null;clinics.value=[];loginError.value='登录已失效，请重新登录。'}
onMounted(async()=>{window.addEventListener('adc:session-expired',expired);try{await loadIdentity()}catch(e){setSessionToken(null);loginError.value=e instanceof Error?e.message:'请重新登录'}finally{loading.value=false}})
onUnmounted(()=>window.removeEventListener('adc:session-expired',expired))
</script>
<template>
 <div v-if="loading" class="boot-screen">正在验证员工会话…</div><LoginView v-else-if="!identity" :error="loginError" @login="login" />
 <div v-else-if="identity.kind==='访客'" class="visitor-page"><section class="visitor-card"><span class="eyebrow">HEAN DENTAL</span><h1>访客登录成功</h1><p>当前账号没有业务访问权限。</p><p class="sub">患者资料、预约、排班、诊疗、结算和账户管理均不可访问。</p><button @click="logout">退出登录</button></section></div><div v-else v-readonly-ui="identity.readOnly" class="shell approved-shell" :class="{'read-only-workspace':identity.readOnly}">
  <aside class="side"><div class="brand"><span class="mark"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5c-3-3-8-2-8 2 0 3 2 5 3 11 .5 3 2 3 3-1l1-4c.3-1 1.7-1 2 0l1 4c1 4 2.5 4 3 1 1-6 3-8 3-11 0-4-5-5-8-2Z"/></svg></span><div><b>和安口腔</b><small>HEAN DENTAL</small></div></div><nav class="nav" aria-label="主导航"><div v-for="group in navGroups" :key="group.name" class="navgroup"><div class="cap">{{group.name}}</div><button v-for="m in group.items" :key="m[0]" class="navitem" :class="{on:page===m[0]}" :aria-current="page===m[0]?'page':undefined" @click="navigate(m[0]!)"><span class="navicon" aria-hidden="true" v-html="icons[m[0]]"></span>{{m[1]}}</button></div></nav><div class="sidefoot"><div class="sidenote"><b>课程演示环境</b><span>数据来自服务器真实读写，用于教学演示与验收。</span></div><div class="me"><span class="avatar">{{access.dentist?'医':'管'}}</span><div><b>{{identity.readOnly?'只读访客':'课程管理员'}}</b><small>牙科诊所管理</small></div><button class="logout" @click="logout">退出</button></div></div></aside>
  <div class="workspace"><header class="top"><div class="crumb">和安口腔 / <b>{{title}}</b></div><div class="topright"><label class="scope"><i></i><select v-model="clinicId" aria-label="选择门店"><option value="0">全部门店</option><option v-for="c in clinics" :key="c.clinic_id" :value="c.clinic_id">{{c.name}}</option></select></label><span class="today">{{dateLabel}}</span><span class="badge">{{identity.readOnly?'只读访客 · 仅可查看':'牙科诊所管理后台'}}</span><button class="iconbtn" title="退出登录" @click="logout"><svg viewBox="0 0 24 24"><path d="M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3"/><path d="M10 17l-5-5 5-5"/><path d="M5 12h11"/></svg></button></div></header><main class="content"><div v-if="identity.readOnly" class="readonly-banner">只读访客：可以查看各项业务数据，不能新增、修改、删除或办理业务。</div>   <ApprovedWorkspace v-if="['dashboard','queue','billing','patients','projects'].includes(page)" :key="scopeKey+page" :identity="identity" :clinic-id="clinicId" :mode="page" @navigate="navigate" />
   <div v-else class="approved-inner">
    <FrontDeskView v-if="page==='appointments'&&access.front" :key="scopeKey" :clinics="selectedClinics" :identity="identity" />
    <OrganizationScheduleView :selected-clinic-id="clinicId==='0'?clinics[0]?.clinic_id:clinicId" v-else-if="page==='scheduling'&&access.organization" :key="scopeKey" :identity="identity" />
    <template v-else-if="page==='consultation'"><DentistView v-if="access.dentist" :key="scopeKey" :clinics="selectedClinics" :identity="identity" /><template v-else><div class="pagehead"><div><div class="eyebrow">CONSULTATION & TREATMENT</div><h1>接诊与实际治疗</h1><p class="sub">前台登记到诊，由接诊牙医复核过敏并确认实际治疗。</p></div></div><div class="care-warning">当前为前台／管理账号。实际治疗确认请使用对应牙医账号在同一后台办理。</div><ApprovedWorkspace :identity="identity" :clinic-id="clinicId" mode="queue" @navigate="navigate" /></template></template>
    <FrontDeskFollowups v-else-if="page==='followups'&&access.front" :key="scopeKey" :clinics="selectedClinics" :identity="identity" />
    <DentistView v-else-if="page==='followups'&&access.dentist" :key="scopeKey" :clinics="selectedClinics" :identity="identity" />
    <OrganizationView v-else-if="[ 'dentists', 'clinics' ].includes(page)&&access.organization" :key="scopeKey+page" :identity="identity" :initial-section="page==='dentists'?'dentists':'clinics'" />
    <ScheduleChangeView v-else-if="page==='applications'&&access.organization" :clinic-id="clinicId==='0'?clinics[0]?.clinic_id||'':clinicId" :read-only="identity.readOnly" />
    <template v-else-if="page==='settings'"><div class="pagehead"><div><div class="eyebrow">ACCOUNT & CLINIC SETTINGS</div><h1>账户设置</h1><p class="sub">按当前岗位维护账户与诊所配置。</p></div></div><div class="toolbar"><button v-if="access.accountManager" class="secondary" @click="settingsTab='accounts'">账号与权限</button><button v-if="access.reminders" class="secondary" @click="settingsTab='reminders'">提醒配置</button><button v-if="access.expenses" class="secondary" @click="settingsTab='expenses'">费用登记</button><button v-if="access.quarter" class="secondary" @click="settingsTab='quarter'">季度经营</button><button v-if="access.accountManager" class="secondary" @click="settingsTab='wechat'">患者微信核验</button><button class="secondary" @click="settingsTab='audit'">业务操作记录</button><button class="secondary" @click="logout">退出登录</button></div><AccountAdminView v-if="settingsTab==='accounts'&&access.accountManager" :identity="identity" :clinics="selectedClinics" @identity-refresh="loadIdentity" /><ReminderAdminView v-else-if="settingsTab==='reminders'&&access.reminders" :identity="identity" :clinics="selectedClinics" /><ExpenseAdminView v-else-if="settingsTab==='expenses'&&access.expenses" :identity="identity" :clinics="selectedClinics" /><QuarterReportView v-else-if="settingsTab==='quarter'&&access.quarter" :identity="identity" /><PatientWechatBindingView v-else-if="settingsTab==='wechat'&&access.accountManager" :identity="identity" /><BusinessAuditView v-else-if="settingsTab==='audit'" /></template>
   </div><footer class="footer"><span>HEAN DENTAL © 2026　用心守护每一颗牙齿</span><span>课程演示 · 数据由服务器保存</span></footer>
  </main></div>
 </div>
</template>
