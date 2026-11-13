const api = require('../../utils/api')
const { formatShanghaiDateTime } = require('../../utils/date')
Page({
  data: { rows: [], loading: false, error: '', truncated: false },
  onLoad() { this._alive = true; this._generation = 0 },
  onShow() { this.load() },
  onHide() { this._generation++; this.setData({ rows: [], error: '', loading: false }) },
  onUnload() { this._alive = false; this._generation++ },
  current(generation, session) {
    return this._alive && generation === this._generation && session.token === api.sessionToken() &&
      session.subjectKind === api.sessionKind() && session.subjectKey === api.sessionSubjectKey()
  },
  async load() {
    const generation = ++this._generation
    this.setData({ loading: true, error: '', rows: [], truncated: false })
    try {
      const session = await api.ensureBookingSession()
      if (!this.current(generation, session)) return
      this._session = session
      const prefix = session.subjectKind === 'GUEST' ? '/guest' : '/patient'
      const [rows, clinics] = await Promise.all([api.request(prefix + (prefix === '/guest' ? '/reservations' : '/appointments')), api.request(prefix + '/clinics')])
      if (!this.current(generation, session)) return
      if (!Array.isArray(rows) || !Array.isArray(clinics)) throw new Error('预约记录响应异常。')
      const shown = rows.slice().sort((a, b) => Date.parse(b.scheduled_start) - Date.parse(a.scheduled_start)).slice(0, 100)
      const clinicIds = Array.from(new Set(shown.map((r) => String(r.clinic_id))))
      const dentists = await Promise.all(clinicIds.map((c) => api.request(`${prefix}/dentists?clinicId=${encodeURIComponent(c)}`)))
      if (!this.current(generation, session)) return
      const names = new Map()
      dentists.forEach((list) => { if (Array.isArray(list)) list.forEach((d) => names.set(String(d.dentist_id), d.name)) })
      const display = shown.map((r) => {
        const guest = session.subjectKind === 'GUEST'
        const reference = guest ? r.reservation_no : r.appointment_id
        if (typeof reference !== 'string' || !(guest ? /^G-[A-Za-z0-9_-]{22,30}$/.test(reference) : /^[1-9]\d*$/.test(reference))) throw new Error('预约编号格式异常。')
        const clinic = clinics.find((c) => String(c.clinic_id) === String(r.clinic_id))
        return { reference, guest, status: guest ? r.reservation_status : r.appointment_status,
          start: formatShanghaiDateTime(r.scheduled_start), end: formatShanghaiDateTime(r.scheduled_end),
          clinic: clinic ? clinic.name : '门店资料暂不可用', dentist: names.get(String(r.dentist_id)) || '医生资料暂不可用' }
      })
      this.setData({ rows: display, truncated: rows.length > 100 })
    } catch (error) {
      if (this._alive && generation === this._generation) this.setData({ error: error.message || '预约记录加载失败。' })
    } finally { if (this._alive && generation === this._generation) this.setData({ loading: false }) }
  },
  open(event) {
    if (!this._session || !this.current(this._generation, this._session)) {
      this.setData({ rows: [], error: '预约主体已变化，请重新读取。' }); return
    }
    const row = this.data.rows[Number(event.currentTarget.dataset.index)]
    if (!row) return
    wx.navigateTo({ url: row.guest ? `/pages/guest-reservation/index?reservationNo=${encodeURIComponent(row.reference)}` : `/pages/appointment/index?id=${row.reference}` })
  }
})
