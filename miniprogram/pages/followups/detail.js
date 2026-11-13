const api = require('../../utils/api')
const followup = require('../../utils/followup')

Page({
  data: {
    planId: '',
    plan: null,
    loading: true,
    error: ''
  },

  onLoad(options) {
    this._alive = true
    this._requestGeneration = 0
    this._sessionToken = ''
    const id = options && options.id
    if (!followup.isId(id)) {
      this.setData({ loading: false, error: '复诊计划编号无效，请返回计划列表重新打开。' })
      return
    }
    this.setData({ planId: id })
    this.loadDetail()
  },

  onShow() {
    if (!this._alive || !this.data.planId) return
    const token = api.sessionToken()
    if (!token) {
      this.clearPlan('登录已失效，请返回患者服务重新登录。')
      return
    }
    if (token !== this._sessionToken) {
      this.clearPlan('会话已切换，正在重新读取本人复诊计划。')
      this._sessionToken = token
      this.loadDetail()
    }
  },

  onUnload() {
    this._alive = false
    this._requestGeneration += 1
  },

  onPullDownRefresh() {
    this.loadDetail().finally(() => wx.stopPullDownRefresh())
  },

  loadDetail() {
    if (!this._alive || !this.data.planId) return Promise.resolve()
    const token = api.sessionToken()
    if (!token) {
      this.clearPlan('登录已失效，请返回患者服务重新登录。')
      return Promise.resolve()
    }
    const generation = ++this._requestGeneration
    this._sessionToken = token
    this.setData({ loading: true, error: '' })
    return api.request(`/patient/followup-plans/${encodeURIComponent(this.data.planId)}`).then((plan) => {
      if (!this.isCurrent(generation, token)) return
      if (!followup.isPlan(plan) || plan.followup_plan_id !== this.data.planId) throw new Error('复诊计划详情响应格式异常，请返回列表刷新。')
      this.setData({ plan, loading: false, error: '' })
    }).catch((error) => {
      if (!this.isGenerationCurrent(generation)) return
      if (error.statusCode === 401) {
        this.clearPlan('登录已失效，请返回患者服务重新登录。')
        return
      }
      if (error.code === 'SESSION_CHANGED' || token !== api.sessionToken()) {
        this.clearPlan('会话已切换，请返回患者服务后重新打开。')
        return
      }
      this.setData({ plan: null, loading: false, error: error.message || '读取复诊计划详情失败，请下拉刷新重试。' })
    })
  },

  isCurrent(generation, token) {
    return this._alive && generation === this._requestGeneration && !!token && token === api.sessionToken()
  },

  isGenerationCurrent(generation) {
    return this._alive && generation === this._requestGeneration
  },

  clearPlan(message) {
    this._requestGeneration += 1
    this._sessionToken = ''
    this.setData({ plan: null, loading: false, error: message })
  },

  arrangeFollowup() {
    if (!this._alive || !this.data.plan || this.data.plan.status !== '待复诊') return
    const token = api.sessionToken()
    if (!token || token !== this._sessionToken) {
      this.clearPlan(token ? '会话已切换，请返回计划列表后重新打开。' : '登录已失效，请返回患者服务重新登录。')
      return
    }
    const id = this.data.plan.followup_plan_id
    if (!followup.isId(id)) {
      this.setData({ error: '复诊计划编号异常，请返回计划列表刷新。' })
      return
    }
    wx.navigateTo({ url: `/pages/booking/index?mode=new&planId=${encodeURIComponent(id)}` })
  },

  goBack() {
    if (!this._alive) return
    wx.navigateBack({ delta: 1, fail: () => {
      if (this._alive) wx.redirectTo({ url: '/pages/followups/index' })
    } })
  }
})
