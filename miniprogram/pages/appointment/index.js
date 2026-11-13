const api = require('../../utils/api')
const { formatShanghaiDateTime, isZeroAmount } = require('../../utils/date')
const operations = require('../../utils/write-operations')

Page({
  data: {
    appointmentId: '',
    appointment: null,
    treatments: [],
    invoice: null,
    bookingStatus: null,
    clinics: [],
    canCancel: false,
    canReschedule: false,
    pendingOperation: null,
    loading: true,
    error: ''
  },

  onLoad(options) {
    this._requestGeneration = 0
    const id = options && options.id
    if (typeof id !== 'string' || !/^\d+$/.test(id)) {
      this.setData({ loading: false, error: '预约编号无效，请返回列表重新打开。' })
      return
    }
    this.setData({ appointmentId: id })
    this.loadDetail()
  },

  onShow() {
    const pending = operations.readPending()
    this.setData({ pendingOperation: pending ? { kind: pending.kind } : null })
    if (!this.data.appointmentId) return
    const token = api.sessionToken()
    if (!token) {
      this._requestGeneration += 1
      this._sessionToken = ''
      this.clearDetail('登录已失效，请返回并重新登录。')
      return
    }
    if (token !== this._sessionToken) {
      this.clearDetail('会话已切换，正在重新读取本人预约。')
      this.loadDetail()
    }
  },

  onUnload() {
    this._requestGeneration += 1
  },

  onPullDownRefresh() {
    this.loadDetail().finally(() => wx.stopPullDownRefresh())
  },

  loadDetail() {
    const generation = ++this._requestGeneration
    const token = api.sessionToken()
    this._sessionToken = token
    this.setData({ loading: true, error: '' })
    if (!token) {
      this._sessionToken = ''
      this.clearDetail('登录已失效，请返回并重新登录。')
      return Promise.resolve()
    }
    return Promise.all([
      api.request(`/patient/appointments/${encodeURIComponent(this.data.appointmentId)}`),
      api.request('/patient/booking-status'),
      api.request('/patient/clinics').catch(() => [])
    ]).then(async ([result, bookingStatus, clinicDirectory]) => {
        if (generation !== this._requestGeneration || token !== api.sessionToken()) return
        if (!result.appointment || !Array.isArray(result.treatments) || !Object.prototype.hasOwnProperty.call(result, 'invoice')) throw new Error('预约详情响应格式异常')
        const appointment = {
          ...result.appointment,
          display_start: formatShanghaiDateTime(result.appointment.scheduled_start),
          display_end: formatShanghaiDateTime(result.appointment.scheduled_end),
          display_arrived: result.appointment.arrived_at ? formatShanghaiDateTime(result.appointment.arrived_at) : '',
          display_completed: result.appointment.completed_at ? formatShanghaiDateTime(result.appointment.completed_at) : ''
        }
        const clinic = clinicDirectory.find((item) => String(item.clinic_id) === String(appointment.clinic_id))
        const dentists = clinic
          ? await api.request(`/patient/dentists?clinicId=${encodeURIComponent(String(appointment.clinic_id))}`).catch(() => [])
          : []
        const dentist = Array.isArray(dentists) ? dentists.find((item) => String(item.dentist_id) === String(appointment.dentist_id)) : null
        appointment.display_clinic = clinic ? clinic.name : '门店信息暂不可用'
        appointment.display_dentist = dentist ? dentist.name : '牙医信息暂不可用'
        const treatments = result.treatments.map((treatment) => ({
          ...treatment,
          units_display: `${treatment.units} ${treatment.unit_snapshot}`
        }))
        let invoice = null
        if (result.invoice && result.invoice.status === '发出') {
          invoice = {
            ...result.invoice,
            display_issued_at: formatShanghaiDateTime(result.invoice.issued_at),
            display_zero_total: isZeroAmount(result.invoice.total_amount),
            lines: (result.invoice.lines || []).map((line) => ({
              ...line,
              units_display: `${line.units_snapshot} ${line.unit_snapshot}`,
              display_zero_price: isZeroAmount(line.unit_price_snapshot),
              display_zero_subtotal: isZeroAmount(line.subtotal)
            }))
          }
        }
        const pendingState = new Set(['待到诊', '待核实']).has(appointment.appointment_status)
        const noVisitFacts = !appointment.attended && appointment.treatment_status === '未开始' && treatments.length === 0 && !invoice
        if (generation !== this._requestGeneration || token !== api.sessionToken()) return
        const displayBookingStatus = bookingStatus.restriction ? {
          ...bookingStatus,
          restriction: {
            ...bookingStatus.restriction,
            display_starts_at: formatShanghaiDateTime(bookingStatus.restriction.starts_at),
            display_ends_at: formatShanghaiDateTime(bookingStatus.restriction.ends_at)
          }
        } : bookingStatus
        const pending = operations.readPending()
        this.setData({
          appointment, treatments, invoice, bookingStatus: displayBookingStatus, clinics: bookingStatus.restricted ? clinicDirectory : [],
          canCancel: pendingState && noVisitFacts,
          canReschedule: pendingState && noVisitFacts && !bookingStatus.restricted,
          pendingOperation: pending ? { kind: pending.kind } : null,
          loading: false, error: ''
        })
      })
      .catch((error) => {
        if (generation !== this._requestGeneration) return
        if (error.statusCode === 401 || error.code === 'SESSION_CHANGED' || token !== api.sessionToken()) {
          this._sessionToken = ''
          this.clearDetail(error.statusCode === 401
            ? '登录已失效，请返回并重新登录。'
            : '会话已切换，请返回重新登录后查看。')
          return
        }
        this.setData({ loading: false, error: error.message || '读取预约详情失败，请刷新重试。' })
      })
  },

  clearDetail(message) {
    this.setData({
      appointment: null,
      treatments: [],
      invoice: null,
      bookingStatus: null,
      clinics: [],
      canCancel: false,
      canReschedule: false,
      loading: false,
      error: message
    })
  },

  cancelAppointment() {
    if (!this.data.canCancel || this.data.pendingOperation) return
    wx.navigateTo({ url: `/pages/booking/index?mode=cancel&id=${encodeURIComponent(this.data.appointmentId)}` })
  },

  goHome() {
    wx.navigateBack({ delta: 1 })
  },

  rescheduleAppointment() {
    if (!this.data.canReschedule || this.data.pendingOperation) return
    wx.navigateTo({ url: `/pages/booking/index?mode=reschedule&id=${encodeURIComponent(this.data.appointmentId)}` })
  },

  callClinic(event) {
    const phone = event.currentTarget.dataset.phone
    if (typeof phone === 'string' && phone.trim()) wx.makePhoneCall({ phoneNumber: phone })
  }
})
