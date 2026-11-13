<script setup lang="ts">
import { ref } from 'vue'
import NoticeBar from '../components/NoticeBar.vue'
const emit = defineEmits<{ login: [credentials: { loginName: string; password: string }] }>()
defineProps<{ error: string }>()
const loginName = ref('')
const password = ref('')
const busy = ref(false)
async function submit() {
  busy.value = true
  try { emit('login', { loginName: loginName.value.trim(), password: password.value }) }
  finally { window.setTimeout(() => { busy.value = false }, 500) }
}
</script>

<template>
  <main class="login-shell">
    <section class="login-card" aria-labelledby="login-title">
      <div class="login-brand"><div class="brand-mark">ADC</div><div><strong>和安口腔</strong><small>员工工作空间</small></div></div>
      <div class="login-intro"><p class="eyebrow">STAFF WORKSPACE</p><h1 id="login-title">欢迎回来</h1><p class="muted">使用员工账号登录，进入诊所管理工作台。</p></div>
      <form class="stack-lg login-form" @submit.prevent="submit">
        <label>员工账号<input v-model="loginName" name="username" autocomplete="username" required autofocus placeholder="输入员工账号" /></label>
        <label>密码<input v-model="password" name="password" type="password" autocomplete="current-password" required placeholder="输入密码" /></label>
        <NoticeBar tone="error" :title="error" />
        <button class="button button-primary button-wide" type="submit" :disabled="busy">{{ busy ? '正在验证…' : '登录工作台' }}</button>
      </form>
      <p class="login-foot"><span class="dot dot-green"></span>登录后可使用全部后台管理功能</p>
    </section>
    <aside class="login-aside" aria-label="登录流程说明"><div class="aside-grid"></div><div class="aside-orbit orbit-one"></div><div class="aside-orbit orbit-two"></div><div class="aside-copy"><span class="eyebrow eyebrow-light">HEAN DENTAL · CLINIC CARE</span><h2>把每一次就诊，<br />安排得从容安心。</h2><p>预约、牙医接诊、费用核对与持续复诊，在一个清晰的工作台完成。</p><ol class="login-steps"><li><span>01</span><div><strong>验证员工身份</strong><small>使用当前员工账号登录</small></div></li><li><span>02</span><div><strong>载入诊所数据</strong><small>查看全部门店和业务信息</small></div></li><li><span>03</span><div><strong>进入管理工作台</strong><small>统一办理诊所日常业务</small></div></li></ol></div><div class="aside-meta">员工专用工作空间 <span>·</span> Asia/Shanghai</div></aside>
  </main>
</template>
