const catalog = require('../../utils/catalog')
const api = require('../../utils/api')
const guestOperations = require('../../utils/guest-operations')
const operations = require('../../utils/write-operations')

Page({
  data: { mode: 'clinics', q: '', rows: [], dates: [], date: '', loading: false, error: '', hasMore: false,
    clinicId: '', clinicName: '', dentistId: '', detail: null, locations: [], locationIndex: 0, periods: [],
    status: '', expandedIntro: false, expandedExpertise: false, booking: false },
  onLoad(options = {}) {
    this._alive = true; this._generation = 0; this._slotGeneration = 0; this._cursor = ''
    const mode = ['clinics', 'doctors', 'experts', 'detail'].includes(options.mode) ? options.mode : 'clinics'
    const clinicId = catalog.id(options.clinicId) ? options.clinicId : ''
    const dentistId = catalog.id(options.dentistId) ? options.dentistId : ''
    if ((mode === 'doctors' && !clinicId) || (mode === 'detail' && !dentistId)) {
      this.setData({ error: '页面参数无效，请返回重新选择。' }); return
    }
    const dates = catalog.dates()
    const requestedDate = dates.some((d) => d.date === options.date) ? options.date : dates[0].date
    let clinicName = String(options.clinicName || '')
    try { clinicName = decodeURIComponent(clinicName) } catch { /* Preserve plain labels containing a percent sign. */ }
    this.setData({ mode, clinicId, dentistId, clinicName: clinicName.slice(0, 100), dates, date: requestedDate })
    this.setTitle(mode === 'clinics' ? '选择门诊部' : mode === 'experts' ? '专家信息' : mode === 'doctors' ? this.data.clinicName || '选择医生' : '医生详情')
    this.load(false)
  },
  onUnload() { this._alive = false; this._generation++; this._slotGeneration++ },
  setTitle(title) { if (wx.setNavigationBarTitle) wx.setNavigationBarTitle({ title }) },
  current(generation) { return this._alive && this._generation === generation },
  onSearchInput(event) { this.setData({ q: String(event.detail.value || '').slice(0, 40) }) },
  search() { this.load(false) },
  retry() { return this.load(false) },
  more() { if (this.data.hasMore && !this.data.loading) return this.load(true) },
  async load(append) {
    if (!this._alive) return
    const generation = ++this._generation
    this._slotGeneration++
    this.setData({ loading: true, error: '', ...(append ? {} : { rows: [], periods: [], hasMore: false, detail: null }) })
    if (!append) this._cursor = ''
    try {
      const mode = this.data.mode
      if (mode === 'detail') { await this.loadDetail(generation); return }
      const path = mode === 'clinics'
        ? `/clinics?q=${encodeURIComponent(this.data.q)}${this._cursor ? '&afterId=' + this._cursor : ''}`
        : mode === 'experts' ? `/experts${this._cursor ? '?afterId=' + this._cursor : ''}`
          : `/clinics/${this.data.clinicId}/dentists?date=${this.data.date}&q=${encodeURIComponent(this.data.q)}`
      const response = await catalog.get(path)
      if (!this.current(generation)) return
      const incoming = catalog.items(response).map((row) => {
        if (mode !== 'clinics') return catalog.decorate(row)
        if (!row || !catalog.id(row.clinicId) || typeof row.name !== 'string' || typeof row.address !== 'string') throw new Error('门诊资料格式异常。')
        return row
      })
      const rows = append ? this.data.rows.concat(incoming) : incoming
      const hasMore = response.hasMore === true
      if (hasMore && (!catalog.id(response.nextAfterId) || response.nextAfterId === this._cursor)) throw new Error('目录分页响应异常。')
      this._cursor = hasMore ? response.nextAfterId : ''
      this.setData({ rows, hasMore })
    } catch (error) { if (this.current(generation)) this.setData({ error: error.message || '目录加载失败，请重试。' }) }
    finally { if (this.current(generation)) this.setData({ loading: false }) }
  },
  async loadDetail(generation) {
    const clinicId = this.data.clinicId
    const response = await catalog.get(`/dentists/${this.data.dentistId}${clinicId ? '?clinicId=' + clinicId : ''}`)
    if (!this.current(generation)) return
    const detail = catalog.decorate(response)
    if (!Array.isArray(response.clinics) || !Array.isArray(response.dates)) throw new Error('出诊资料格式异常。')
    const locations = this.data.locations.length ? this.data.locations : response.clinics
    if (locations.some((c) => !catalog.id(c.clinicId))) throw new Error('门诊资料格式异常。')
    const chosen = locations.find((c) => c.clinicId === clinicId) || locations[0]
    const days = response.dates.map((d) => {
      if (!/^\d{4}-\d{2}-\d{2}$/.test(d.date) || !['FULL', 'AVAILABLE'].includes(d.status)) throw new Error('出诊日期格式异常。')
      const date = new Date(d.date + 'T00:00:00Z')
      return { ...d, short: d.date.slice(5), week: ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][date.getUTCDay()] }
    })
    const selected = days.find((d) => d.date === this.data.date) || days.find((d) => d.status === 'AVAILABLE') || days[0]
    this.setData({ detail, locations, locationIndex: chosen ? locations.indexOf(chosen) : 0,
      clinicId: chosen ? chosen.clinicId : '', clinicName: chosen ? chosen.name : '', dates: days, date: selected ? selected.date : '', periods: [], status: 'NOT_SCHEDULED' })
    this.setTitle(detail.name)
    // Expert entry initially spans stores; reload the dates for the actual selected store.
    if (!clinicId && chosen) { await this.loadDetail(generation); return }
    if (chosen && selected) await this.loadSlots(generation)
  },
  async loadSlots(generation = this._generation) {
    const request = ++this._slotGeneration
    const { clinicId, dentistId, date } = this.data
    this.setData({ periods: [], loading: true, error: '' })
    try {
      const result = await catalog.get(`/availability?clinicId=${clinicId}&dentistId=${dentistId}&date=${date}`)
      if (!this.current(generation) || request !== this._slotGeneration) return
      if (result.clinicId !== clinicId || result.dentistId !== dentistId || result.date !== date || !Array.isArray(result.periods) || !['AVAILABLE', 'FULL', 'NOT_SCHEDULED'].includes(result.status)) throw new Error('可约时段响应异常。')
      const periods = result.periods.map((p) => {
        if (!['AM', 'PM'].includes(p.period) || !Array.isArray(p.slots) || !Number.isInteger(p.remainingCandidateCount) || p.remainingCandidateCount < 0) throw new Error('排班格式异常。')
        const slots = p.slots.map((s) => {
          const choice = catalog.selection({ ...s, clinicId, dentistId })
          if (!choice || typeof s.available !== 'boolean') throw new Error('时段格式异常。')
          return { ...s, choice, label: s.start.slice(11, 16) + '–' + s.end.slice(11, 16) }
        })
        return { ...p, slots, label: p.period === 'AM' ? '上午' : '下午', time: p.start.slice(11, 16) + '–' + p.end.slice(11, 16) }
      })
      this.setData({ periods, status: result.status })
    } catch (error) { if (this.current(generation) && request === this._slotGeneration) this.setData({ error: error.message || '时段加载失败，请重试。' }) }
    finally { if (this.current(generation) && request === this._slotGeneration) this.setData({ loading: false }) }
  },
  selectDate(event) {
    const date = event.currentTarget.dataset.date
    if (!this.data.dates.some((d) => d.date === date)) return
    this.setData({ date, periods: [] })
    if (this.data.mode === 'detail') this.loadSlots()
    else this.load(false)
  },
  selectLocation(event) {
    const index = Number(event.detail.value), clinic = this.data.locations[index]
    if (!clinic) return
    this.setData({ clinicId: clinic.clinicId, clinicName: clinic.name, locationIndex: index, date: '', periods: [] })
    this.load(false)
  },
  openRow(event) {
    const row = this.data.rows[Number(event.currentTarget.dataset.index)]
    if (!row) return
    const values = this.data.mode === 'clinics'
      ? { clinicId: row.clinicId, clinicName: row.name }
      : { dentistId: row.dentistId, ...(this.data.mode === 'doctors' ? { clinicId: this.data.clinicId, clinicName: this.data.clinicName, date: this.data.date } : {}) }
    wx.navigateTo({ url: catalog.url(this.data.mode === 'clinics' ? 'doctors' : 'detail', values) })
  },
  toggleIntro() { this.setData({ expandedIntro: !this.data.expandedIntro }) },
  toggleExpertise() { this.setData({ expandedExpertise: !this.data.expandedExpertise }) },
  async book(event) {
    if (this.data.booking || this.data.loading) return
    const p = this.data.periods[Number(event.currentTarget.dataset.period)]
    const slot = p && p.slots[Number(event.currentTarget.dataset.slot)]
    if (!slot || !slot.available) return
    const generation = this._generation, slotGeneration = this._slotGeneration
    this.setData({ booking: true, error: '' })
    try {
      const session = await api.ensureBookingSession()
      if (!this.current(generation) || slotGeneration !== this._slotGeneration) return
      if ((session.subjectKind === 'GUEST' ? guestOperations : operations).readPending()) throw new Error('有预约结果待核实，请先返回首页恢复原预约。')
      wx.navigateTo({ url: catalog.bookingUrl(session.subjectKind, slot.choice) })
    } catch (error) { if (this.current(generation)) this.setData({ error: error.message || '预约会话加载失败。' }) }
    finally { if (this._alive) this.setData({ booking: false }) }
  }
})
