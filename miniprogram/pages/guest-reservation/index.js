const api = require('../../utils/api')
const { formatShanghaiDateTime } = require('../../utils/date')

Page({
  data: { reservationNo: '', reservation: null, loading: true, changing: false, error: '' },

  onLoad(options) {
    this._alive = true
    this._requestGeneration = 0
    const number = options && options.reservationNo
    if (typeof number !== 'string' || !/^G-[A-Za-z0-9_-]{22,30}$/.test(number)) {
      this.setData({ loading: false, error: '预约号格式异常，请返回本人预约列表重新打开。' })
      return
    }
    this._reservationNo = number
    this.setData({ reservationNo: number })
  },

  onShow() {
    if (!this._alive || !this._reservationNo) return
    if (api.sessionKind() === 'GUEST' && api.sessionToken()) return this.loadReservation()
    this.setData({ loading: true, error: '' })
    api.ensureBookingSession().then((session) => {
      if (session.subjectKind !== 'GUEST') throw new Error('此预约号不属于当前患者预约入口。')
      return this.loadReservation()
    }).catch((error) => this.showError(error))
  },

  onUnload() {
    this._alive = false
    this._requestGeneration += 1
  },

  loadReservation() {
    if (api.sessionKind() !== 'GUEST' || !api.sessionSubjectKey()) {
      this.setData({ loading: false, error: '访客预约会话无效，请从首页重新进入。' })
      return Promise.resolve()
    }
    const generation = ++this._requestGeneration
    const token = api.sessionToken()
    const subjectKey = api.sessionSubjectKey()
    this.setData({ loading: true, error: '' })
    return api.request(`/guest/reservations/by-number/${encodeURIComponent(this._reservationNo)}`).then((row) => {
      if (!this.isCurrent(generation, token, subjectKey)) return
      const required = ['guest_reservation_id', 'reservation_no', 'clinic_id', 'dentist_id', 'schedule_id', 'scheduled_start', 'scheduled_end', 'contact_name', 'contact_phone', 'requested_treatments', 'reservation_status', 'row_version']
      if (!row || required.some((key) => !Object.prototype.hasOwnProperty.call(row, key)) || row.reservation_no !== this._reservationNo) {
        throw new Error('访客预约详情字段不完整，请重新读取。')
      }
      this.setData({
        reservation: {
          ...row,
          displayStart: formatShanghaiDateTime(row.scheduled_start),
          displayEnd: formatShanghaiDateTime(row.scheduled_end),
          verificationLabel: row.reservation_status === '待到诊'
            ? '访客预约待到店，医疗身份需在门店核验'
            : row.reservation_status === '已转换'
              ? '已由门店完成到店核验'
              : row.reservation_status
        },
        loading: false
      })
    }).catch((error) => {
      if (!this._alive || generation !== this._requestGeneration) return
      this.showError(error)
    })
  },

  isCurrent(generation, token, subjectKey) {
    return this._alive && generation === this._requestGeneration && api.sessionKind() === 'GUEST' &&
      token === api.sessionToken() && subjectKey === api.sessionSubjectKey()
  },

  retry() {
    if (this.data.loading) return
    if (api.sessionKind() === 'GUEST' && api.sessionToken()) return this.loadReservation()
    this.setData({ loading: true, error: '' })
    return api.bookingLogin().then((session) => {
      if (session.subjectKind !== 'GUEST') throw new Error('当前微信账号使用患者预约入口，请从首页查看患者预约记录。')
      return this.loadReservation()
    }).catch((error) => this.showError(error))
  },

  reschedule() {
    if (this.data.reservation?.reservation_status !== '待到诊' || this.data.changing) return
    wx.navigateTo({ url: `/pages/guest-booking/index?reschedule=${encodeURIComponent(this._reservationNo)}` })
  },

  cancel() {
    const row = this.data.reservation
    if (row?.reservation_status !== '待到诊' || this.data.changing) return
    const token = api.sessionToken()
    wx.showModal({
      title: '取消预约',
      content: '距离预约开始不足 3 小时的取消将计入履约记录。',
      success: result => {
        if (!result.confirm || !this._alive || token !== api.sessionToken() || row !== this.data.reservation) return
        this.setData({ changing: true, error: '' })
        api.request(`/guest/reservations/${row.guest_reservation_id}/cancel`, {
          method: 'POST', data: { expectedRowVersion: row.row_version, reason: '患者在小程序取消' }
        }).then(() => this._alive && this.loadReservation()).catch(error => {
          if (this._alive) this.showError(error)
        }).finally(() => {
          if (this._alive) this.setData({ changing: false })
        })
      }
    })
  },

  showError(error) {
    if (error.code === 'SESSION_CHANGED') return
    this.setData({ loading: false, error: error.statusCode === 404
      ? '当前微信预约主体下未找到这笔预约。'
      : error.code === 'PATIENT_BINDING_REQUIRED'
        ? '当前微信账号暂时不能读取访客预约，请稍后重试或联系门店核对账号状态。'
      : error.message || '读取访客预约详情失败，请重试。' })
  }
})
