const api = require('../../utils/api')
const followup = require('../../utils/followup')

const filterOptions = [
  { value: 'all', label: '全部计划' },
  { value: '待复诊', label: '待复诊' },
  { value: '已完成', label: '已完成' },
  { value: '已取消', label: '已取消' }
]

function shanghaiDate(now = Date.now()) {
  const date = new Date(now + 8 * 60 * 60 * 1000)
  const pad = (value) => String(value).padStart(2, '0')
  return `${date.getUTCFullYear()}-${pad(date.getUTCMonth() + 1)}-${pad(date.getUTCDate())}`
}

Page({
  data: {
    filters: filterOptions,
    filterIndex: 0,
    filterLabel: filterOptions[0].label,
    status: 'all',
    items: [],
    asOfDate: '',
    hasMore: false,
    nextAfterId: '',
    loading: true,
    loadingMore: false,
    error: ''
  },

  onLoad() {
    this._alive = true
    this._requestGeneration = 0
    this._sessionToken = ''
    this._pageBusy = ''
  },

  onShow() {
    if (!this._alive) return
    const token = api.sessionToken()
    if (!token) {
      this.clearList('登录已失效，请返回患者服务重新登录。')
      return
    }
    if (token !== this._sessionToken) {
      this.clearList('会话已切换，正在重新读取本人复诊计划。')
      this._sessionToken = token
      this.refreshList()
      return
    }
    if (this.data.asOfDate && this.data.asOfDate !== shanghaiDate()) this.refreshList()
  },

  onUnload() {
    this._alive = false
    this._requestGeneration += 1
    this._pageBusy = ''
  },

  onPullDownRefresh() {
    this.refreshList().finally(() => wx.stopPullDownRefresh())
  },

  onReachBottom() {
    this.loadNextPage()
  },

  retryList() {
    if (this.data.loading || this.data.loadingMore) return
    if (this.data.items.length && this.data.hasMore && this.data.nextAfterId) return this.loadNextPage()
    return this.refreshList()
  },

  onFilterChange(event) {
    const index = Number(event.detail.value)
    const filter = filterOptions[index]
    if (!filter || filter.value === this.data.status) return
    this.setData({ filterIndex: index, filterLabel: filter.label, status: filter.value })
    return this.refreshList()
  },

  refreshList() {
    if (!this._alive) return Promise.resolve()
    const token = api.sessionToken()
    if (!token) {
      this.clearList('登录已失效，请返回患者服务重新登录。')
      return Promise.resolve()
    }
    const generation = ++this._requestGeneration
    this._sessionToken = token
    this._pageBusy = 'first'
    const status = this.data.status
    this.setData({
      items: [], asOfDate: '', hasMore: false, nextAfterId: '',
      loading: true, loadingMore: false, error: ''
    })
    return api.request(followup.listUrl(status, '')).then((page) => {
      if (!this.isCurrent(generation, token)) return
      this.assertPage(page, '')
      this.setData({ items: page.items, asOfDate: page.asOfDate, hasMore: page.hasMore, nextAfterId: page.nextAfterId || '', loading: false })
    }).catch((error) => {
      if (!this.isGenerationCurrent(generation)) return
      if (error.statusCode === 401) {
        this.clearList('登录已失效，请返回患者服务重新登录。')
        return
      }
      if (error.code === 'SESSION_CHANGED' || token !== api.sessionToken()) {
        this.clearList('会话已切换，请返回患者服务后重新打开。')
        return
      }
      this.setData({ loading: false, error: error.message || '读取复诊计划失败，请下拉刷新重试。' })
    }).finally(() => {
      if (this._alive && generation === this._requestGeneration) this._pageBusy = ''
    })
  },

  loadNextPage() {
    if (!this._alive || this._pageBusy || !this.data.hasMore) return Promise.resolve()
    const cursor = this.data.nextAfterId
    const token = api.sessionToken()
    if (!token || token !== this._sessionToken || typeof cursor !== 'string' || !/^\d+$/.test(cursor)) {
      if (!token || token !== this._sessionToken) {
        this.clearList(token ? '会话已切换，正在重新读取本人复诊计划。' : '登录已失效，请返回患者服务重新登录。')
        if (token) {
          this._sessionToken = token
          return this.refreshList()
        }
      }
      return Promise.resolve()
    }
    const generation = this._requestGeneration
    const status = this.data.status
    this._pageBusy = 'more'
    this.setData({ loadingMore: true, error: '' })
    return api.request(followup.listUrl(status, cursor)).then((page) => {
      if (!this.isCurrent(generation, token)) return
      this.assertPage(page, cursor)
      if (page.asOfDate !== this.data.asOfDate) {
        return this.refreshList()
      }
      const existingIds = new Set(this.data.items.map((plan) => plan.followup_plan_id))
      if (page.items.some((plan) => existingIds.has(plan.followup_plan_id))) throw new Error('复诊计划分页游标返回重复记录，请刷新后重试。')
      this.setData({
        items: this.data.items.concat(page.items),
        hasMore: page.hasMore,
        nextAfterId: page.nextAfterId || '',
        loadingMore: false
      })
    }).catch((error) => {
      if (!this.isGenerationCurrent(generation)) return
      if (error.statusCode === 401) {
        this.clearList('登录已失效，请返回患者服务重新登录。')
        return
      }
      if (error.code === 'SESSION_CHANGED' || token !== api.sessionToken()) {
        this.clearList('会话已切换，请返回患者服务后重新打开。')
        return
      }
      this.setData({ loadingMore: false, error: error.message || '读取下一页失败；游标已保留，可重试。' })
    }).finally(() => {
      if (this._alive && generation === this._requestGeneration) this._pageBusy = ''
    })
  },

  assertPage(page, cursor) {
    if (!followup.isPage(page) || !followup.hasValidNextCursor(page, cursor)) throw new Error('复诊计划列表响应格式或分页游标异常，请刷新重试。')
  },

  isCurrent(generation, token) {
    return this._alive && generation === this._requestGeneration && !!token && token === api.sessionToken()
  },

  isGenerationCurrent(generation) {
    return this._alive && generation === this._requestGeneration
  },

  clearList(message) {
    this._requestGeneration += 1
    this._pageBusy = ''
    this._sessionToken = ''
    this.setData({
      items: [], asOfDate: '', hasMore: false, nextAfterId: '',
      loading: false, loadingMore: false, error: message
    })
  },

  openDetail(event) {
    const id = event.currentTarget.dataset.id
    if (typeof id !== 'string' || !/^\d+$/.test(id)) {
      this.setData({ error: '复诊计划编号异常，请刷新列表。' })
      return
    }
    wx.navigateTo({ url: `/pages/followups/detail?id=${encodeURIComponent(id)}` })
  },

  goHome() {
    wx.navigateBack({ delta: 1 })
  }
})
