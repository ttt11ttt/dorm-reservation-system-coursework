const api = require('../../utils/api')
const { formatShanghaiDateTime } = require('../../utils/date')
const { generateCandidates } = require('../../utils/availability')
const guestOperations = require('../../utils/guest-operations')
const { slotDate } = require('../../utils/registration')
const catalog = require('../../utils/catalog')

Page({
  data: {
    clinics: [], dentists: [], dentistOptions: [{ dentist_id: '', name: '不限医生' }],
    clinicIndex: -1, dentistIndex: 0, selectedClinicId: '', selectedClinicName: '',
    selectedDentistId: '', selectedDentistName: '', dateOptions: [], dateIndex: 0, selectedDate: '',
    candidates: [], selectedSlotKey: '', selectedSlotLabel: '', selectedSlotDentist: '', selectionLocked: false,
    contactName: '', contactPhone: '', requestedTreatments: '', specialNeeds: '',
    prices: [], rescheduling: false,
    bookingStatus: null, busy: false, loading: true, confirming: false,
    pendingOperation: false, error: ''
  },

  onLoad(options) {
    this._catalogChoice = catalog.selection(options)
    this._originalNumber = options && /^G-[A-Za-z0-9_-]{22,30}$/.test(options.reschedule || '') ? options.reschedule : ''
    this.setData({ rescheduling: !!this._originalNumber })
    this._alive = true
    this._started = false
    this._requestGeneration = 0
    this._resourceGeneration = 0
    this._sessionToken = ''
    this._subjectKey = ''
    if (this._catalogChoice) this.setData({ selectedClinicId: this._catalogChoice.clinicId, selectedDentistId: this._catalogChoice.dentistId, selectedDate: this._catalogChoice.date })
  },

  onShow() {
    if (!this._alive || this.data.busy) return
    if (api.sessionKind() === 'GUEST' && api.sessionToken()) {
      const subject = api.sessionSubjectKey()
      if (this._started && subject === this._subjectKey && api.sessionToken() === this._sessionToken) return
      this.startForGuest(subject)
      return
    }
    this.setData({ loading: true, error: '' })
    api.ensureBookingSession().then((session) => {
      if (session.subjectKind !== 'GUEST') {
        wx.redirectTo({ url: this._catalogChoice ? catalog.bookingUrl('PATIENT', this._catalogChoice) : '/pages/booking/index?mode=new' })
        return
      }
      this.startForGuest(session.subjectKey)
    }).catch((error) => this.showError(error))
  },

  onUnload() {
    this._alive = false
    this._requestGeneration += 1
    this._resourceGeneration += 1
    this._availability = null
  },

  onPullDownRefresh() {
    this.loadWorkflow().finally(() => wx.stopPullDownRefresh())
  },

  startForGuest(subjectKey) {
    if (typeof subjectKey !== 'string' || !/^G-[1-9]\d*$/.test(subjectKey)) {
      this.setData({ loading: false, error: '访客预约会话主体格式异常，请重新进入。' })
      return
    }
    const changed = this._subjectKey && this._subjectKey !== subjectKey
    this._subjectKey = subjectKey
    this._sessionToken = api.sessionToken()
    this._started = true
    this.setData({
      loading: true, error: '',
      ...(changed ? { contactName: '', contactPhone: '', requestedTreatments: '', specialNeeds: '', selectedSlotKey: '' } : {})
    })
    this.loadWorkflow()
  },

  isCurrent(generation, token = this._sessionToken) {
    return this._alive && generation === this._requestGeneration && !!token && token === api.sessionToken() &&
      api.sessionKind() === 'GUEST' && api.sessionSubjectKey() === this._subjectKey
  },

  loadWorkflow() {
    const token = api.sessionToken()
    if (!token || api.sessionKind() !== 'GUEST' || !this._subjectKey) {
      this.setData({ loading: false, error: '访客预约会话已失效，请重新登录后继续。' })
      return Promise.resolve()
    }
    this._sessionToken = token
    const generation = ++this._requestGeneration
    this.setData({ loading: true, error: '', pendingOperation: !!guestOperations.readPending() })
    const original = this._originalNumber
      ? api.request(`/guest/reservations/by-number/${encodeURIComponent(this._originalNumber)}`) : Promise.resolve(null)
    const prices = Promise.resolve().then(() => api.request('/guest/treatment-types')).catch(() => [])
    return Promise.all([api.request('/guest/clinics'), api.request('/guest/booking-status'), original, prices]).then(([clinics, status, reservation, priceRows]) => {
      if (!this.isCurrent(generation, token)) return
      if (!Array.isArray(clinics) || !status || typeof status.restricted !== 'boolean') {
        throw new Error('访客预约资料响应格式异常，请重试。')
      }
      if (reservation) {
        if (reservation.reservation_status !== '待到诊') throw new Error('原预约已变更，请返回预约列表核对。')
        this._original = reservation
        if (!this.data.contactName) this.setData({
          contactName: reservation.contact_name, contactPhone: reservation.contact_phone,
          requestedTreatments: reservation.requested_treatments, specialNeeds: reservation.special_needs || ''
        })
      }
      const clinicIndex = clinics.findIndex((clinic) => String(clinic.clinic_id) === (this.data.selectedClinicId || String(reservation?.clinic_id || '')))
      const selectedIndex = clinicIndex >= 0 ? clinicIndex : 0
      const clinic = clinics[selectedIndex]
      this.setData({
        clinics, prices: Array.isArray(priceRows) ? priceRows : [], bookingStatus: {
          ...status,
          displayRestrictedUntil: typeof status.restrictedUntil === 'string' && status.restrictedUntil
            ? formatShanghaiDateTime(status.restrictedUntil)
            : ''
        },
        clinicIndex: clinic ? selectedIndex : -1,
        selectedClinicId: clinic ? String(clinic.clinic_id) : '',
        selectedClinicName: clinic ? String(clinic.name || '') : '',
        loading: false
      })
      if (clinic) return this.loadClinicResources(generation, token)
      this.setData({ dentists: [], candidates: [], dateOptions: [], loading: false })
    }).catch((error) => this.handleLoadError(error, generation, token))
  },

  loadClinicResources(generation = this._requestGeneration, token = api.sessionToken()) {
    if (!this.isCurrent(generation, token) || !this.data.selectedClinicId) return Promise.resolve()
    const resourceGeneration = ++this._resourceGeneration
    const clinicId = this.data.selectedClinicId
    this.setData({ loading: true, dentists: [], candidates: [], dateOptions: [], selectedSlotKey: '', selectedSlotLabel: '', selectedSlotDentist: '' })
    return Promise.all([
      api.request(`/guest/dentists?clinicId=${encodeURIComponent(clinicId)}`),
      api.request(`/guest/availability?clinicId=${encodeURIComponent(clinicId)}`)
    ]).then(([dentists, availability]) => {
      if (!this.isCurrent(generation, token) || resourceGeneration !== this._resourceGeneration || clinicId !== this.data.selectedClinicId) return
      if (!Array.isArray(dentists) || !availability || !Array.isArray(availability.windows) || !Array.isArray(availability.occupied)) {
        throw new Error('医生或可约时段响应格式异常，请刷新重试。')
      }
      this._availability = availability
      const options = [{ dentist_id: '', name: '不限医生' }, ...dentists]
      const dentistIndex = options.findIndex((dentist) => String(dentist.dentist_id || '') === this.data.selectedDentistId)
      const selectedIndex = dentistIndex >= 0 ? dentistIndex : 0
      const selected = options[selectedIndex]
      this.setData({
        dentists, dentistOptions: options, dentistIndex: selectedIndex,
        selectedDentistId: String(selected.dentist_id || ''), selectedDentistName: String(selected.name || ''), loading: false
      })
      this.recomputeCandidates()
    }).catch((error) => this.handleLoadError(error, generation, token, resourceGeneration))
  },

  recomputeCandidates() {
    if (!this._availability) {
      this.setData({ candidates: [], dateOptions: [], selectedDate: '', selectedSlotKey: '' })
      return
    }
    const all = generateCandidates({ availability: this._availability, durationMinutes: 30, stepMinutes: 30 })
      .filter((slot) => !this.data.selectedDentistId || slot.dentistId === this.data.selectedDentistId)
    const dates = Array.from(new Set(all.map((slot) => slotDate(slot.scheduledStart))))
    const selectedDate = dates.includes(this.data.selectedDate) ? this.data.selectedDate : (dates[0] || '')
    const candidates = all.filter((slot) => !selectedDate || slotDate(slot.scheduledStart) === selectedDate)
      .map((slot) => ({
        ...slot,
        dentistName: (this.data.dentists.find((dentist) => String(dentist.dentist_id) === slot.dentistId) || {}).name || '出诊医生',
        timeLabel: slot.displayStart.slice(-5) + ' – ' + slot.displayEnd.slice(-5)
      }))
    const selectedStillAvailable = candidates.some((slot) => slot.key === this.data.selectedSlotKey)
    const incoming = this._catalogChoice && catalog.matchingSlot(this._catalogChoice, candidates)
    this.setData({
      dateOptions: dates.map((value) => ({ value, label: value })),
      dateIndex: Math.max(0, dates.indexOf(selectedDate)), selectedDate, candidates,
      selectedSlotKey: selectedStillAvailable ? this.data.selectedSlotKey : '',
      selectedSlotLabel: selectedStillAvailable ? this.data.selectedSlotLabel : '',
      selectedSlotDentist: selectedStillAvailable ? this.data.selectedSlotDentist : ''
    })
    if (this._catalogChoice) {
      this._catalogChoice = null
      this.setData(incoming ? { selectedSlotKey: incoming.key, selectedSlotLabel: `${incoming.displayStart} – ${incoming.displayEnd}`, selectedSlotDentist: incoming.dentistName, selectionLocked: true } : { selectionLocked: false, error: '所选时段已变化，请在当前可约时段中重新选择。' })
    }
  },

  onClinicChange(event) {
    const index = Number(event.detail.value)
    const clinic = this.data.clinics[index]
    if (!clinic) return
    this._availability = null
    this.setData({
      clinicIndex: index, selectedClinicId: String(clinic.clinic_id), selectedClinicName: String(clinic.name || ''),
      selectedDentistId: '', selectedDentistName: '', dentistIndex: 0, selectedDate: '', selectedSlotKey: ''
    })
    this.loadClinicResources()
  },

  onDentistChange(event) {
    const index = Number(event.detail.value)
    const dentist = this.data.dentistOptions[index]
    if (!dentist) return
    this.setData({ dentistIndex: index, selectedDentistId: String(dentist.dentist_id || ''), selectedDentistName: String(dentist.name || '') })
    this.recomputeCandidates()
  },

  onDateChange(event) {
    const index = Number(event.detail.value)
    const option = this.data.dateOptions[index]
    if (!option) return
    this.setData({ dateIndex: index, selectedDate: option.value, selectedSlotKey: '', selectedSlotLabel: '', selectedSlotDentist: '' })
    this.recomputeCandidates()
  },

  selectSlot(event) {
    const index = Number(event.currentTarget.dataset.index)
    const slot = this.data.candidates[index]
    if (!slot) return
    this.setData({ selectedSlotKey: slot.key, selectedSlotLabel: `${slot.displayStart} – ${slot.displayEnd}（上海时间）`, selectedSlotDentist: slot.dentistName, selectionLocked: true })
  },

  changeSelection() { this.setData({ selectionLocked: false }) },

  onContactNameInput(event) { this.setData({ contactName: event.detail.value }) },
  onContactPhoneInput(event) { this.setData({ contactPhone: event.detail.value }) },
  onRequestedTreatmentsInput(event) { this.setData({ requestedTreatments: event.detail.value }) },
  onSpecialNeedsInput(event) { this.setData({ specialNeeds: event.detail.value }) },

  startSubmit() {
    if (this.data.busy || this.data.confirming || this.data.loading) return
    if (guestOperations.readPending()) {
      this.setData({ pendingOperation: true, error: '请先恢复原预约，不能创建第二笔。' })
      return
    }
    if (this.data.bookingStatus && this.data.bookingStatus.restricted) {
      this.setData({ error: '当前预约主体处于服务端限制期，暂不能新增预约。' })
      return
    }
    const slot = this.data.candidates.find((item) => item.key === this.data.selectedSlotKey)
    const contactName = String(this.data.contactName || '').trim()
    const contactPhone = String(this.data.contactPhone || '').trim()
    const requestedTreatments = String(this.data.requestedTreatments || '').trim()
    const specialNeeds = String(this.data.specialNeeds || '').trim()
    if (!slot || !contactName || contactName.length > 80 || !contactPhone || contactPhone.length > 32 || !requestedTreatments || requestedTreatments.length > 8000 || specialNeeds.length > 8000) {
      this.setData({ error: '请选好医生时段，填写联系人姓名、电话和本次就诊需求后再提交。' })
      return
    }
    const token = api.sessionToken()
    const subjectKey = api.sessionSubjectKey()
    const generation = this._requestGeneration
    this.setData({ confirming: true, error: '' })
    wx.showModal({
      title: this._original ? '确认改约' : '确认预约',
      content: `${this.data.selectedClinicName} · ${slot.dentistName}\n${slot.displayStart} – ${slot.displayEnd}（30分钟）\n联系人：${contactName} ${contactPhone}\n就诊需求：${requestedTreatments}`,
      confirmText: '确认提交',
      success: (result) => {
        this.setData({ confirming: false })
        if (!result.confirm) return
        if (!this.isCurrent(generation, token) || subjectKey !== this._subjectKey) {
          this.setData({ error: '预约会话已变化，请重新核对预约信息。' })
          return
        }
        this.setData({ busy: true, error: '' })
        const payload = {
          clinicId: slot.clinicId,
          dentistId: slot.dentistId,
          scheduleId: slot.scheduleId,
          scheduledStart: slot.scheduledStart,
          scheduledEnd: slot.scheduledEnd,
          contactName,
          contactPhone,
          requestedTreatments,
          ...(specialNeeds ? { specialNeeds } : {})
        }
        guestOperations.start(payload, this._original).then((reservation) => {
          if (!this.isCurrent(generation, token)) return
          wx.redirectTo({ url: `/pages/guest-reservation/index?reservationNo=${encodeURIComponent(reservation.reservation_no)}` })
        }).catch((error) => this.setData({ pendingOperation: !!guestOperations.readPending(), error: this.errorMessage(error) })).finally(() => this.setData({ busy: false }))
      },
      fail: () => this.setData({ confirming: false, error: '确认窗口未打开，请重试。' })
    })
  },

  recoverPending() {
    if (this.data.busy) return
    const operation = guestOperations.readPending()
    if (!operation) {
      this.setData({ pendingOperation: false, error: '' })
      return
    }
    this.setData({ busy: true, error: '' })
    api.bookingLogin().then((session) => {
      this._sessionToken = session.token
      this._subjectKey = session.subjectKey
      if (session.subjectKind !== operation.subjectKind || session.subjectKey !== operation.subjectKey) {
        throw Object.assign(new Error('当前微信预约主体与原预约不同，不能恢复或重发这笔请求。'), { code: 'SUBJECT_MISMATCH' })
      }
      return guestOperations.recover()
    }).then((reservation) => {
      this.setData({ pendingOperation: false })
      wx.redirectTo({ url: `/pages/guest-reservation/index?reservationNo=${encodeURIComponent(reservation.reservation_no)}` })
    }).catch((error) => this.setData({ pendingOperation: !!guestOperations.readPending(), error: this.errorMessage(error) })).finally(() => this.setData({ busy: false }))
  },

  retryWorkflow() {
    if (this.data.loading || this.data.busy) return
    if (api.sessionKind() === 'GUEST' && api.sessionToken()) return this.loadWorkflow()
    this.setData({ loading: true, error: '' })
    return api.bookingLogin().then((session) => {
      if (session.subjectKind !== 'GUEST') throw new Error('此微信账号当前使用患者预约入口，请返回首页重新进入。')
      if (this._subjectKey && session.subjectKey !== this._subjectKey) throw Object.assign(new Error('微信预约主体已变化，请返回首页重新进入。'), { code: 'SUBJECT_MISMATCH' })
      this.startForGuest(session.subjectKey)
    }).catch((error) => this.showError(error))
  },

  handleLoadError(error, generation, token, resourceGeneration) {
    if (!this._alive || (generation !== undefined && generation !== this._requestGeneration) ||
      (resourceGeneration !== undefined && resourceGeneration !== this._resourceGeneration)) return
    this.showError(error)
  },

  errorMessage(error) {
    if (error.writeOutcomeUncertain) return '预约结果暂时无法确认。原预约号和完整请求已保存，请使用“恢复原预约”，不要再次提交。'
    if (error.code === 'BOOKING_RESTRICTED') return '当前预约主体处于服务端限制期，暂不能新增预约。'
    if (error.code === 'TIME_CONFLICT') return '这个时段刚被占用，请刷新后重新选择。'
    if (error.code === 'SUBJECT_MISMATCH') return error.message
    if (error.code === 'PATIENT_BINDING_REQUIRED') return '当前微信账号暂时不能建立预约会话，请稍后重试或联系门店核对账号状态。'
    if (error.statusCode === 401) return '预约会话已过期，请重新读取后继续。'
    return error.message || '预约服务暂时不可用，请稍后重试。'
  },

  showError(error) {
    if (error.code === 'SESSION_CHANGED') return
    this.setData({ loading: false, error: this.errorMessage(error) })
  }
})
