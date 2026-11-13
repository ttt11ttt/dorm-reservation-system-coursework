const api = require('../../utils/api')
const { formatShanghaiDateTime } = require('../../utils/date')
const followup = require('../../utils/followup')
const operations = require('../../utils/write-operations')
const guestOperations = require('../../utils/guest-operations')

function nextVisit(rows, statusKey) {
  const now = Date.now()
  let nearest = null
  let nearestStart = Infinity
  for (const row of rows) {
    if (row[statusKey] !== '待到诊' || !(Date.parse(row.scheduled_end) > now)) continue
    const start = Date.parse(row.scheduled_start)
    if (start < nearestStart) {
      nearest = row
      nearestStart = start
    }
  }
  if (!nearest) return null
  return {
    id: nearest.appointment_id || nearest.reservation_no,
    month: Number(nearest.display_start.slice(5, 7)),
    day: nearest.display_start.slice(8, 10),
    time: nearest.display_start.slice(11),
    clinic: nearest.display_clinic,
    doctor: nearest.display_dentist
  }
}


Page({
  data: {
    loginName: '',
    password: '',
    profile: null,
    appointments: [],
    guestReservations: [],
    nextVisit: null,
    guestBookingStatus: null,
    guestContactName: '',
    guestContactPhone: '',
    bindingTicket: null,
    bindingBusy: false,
    verifyWithoutSession: false,
    subjectKind: '',
    clinics: [],
    recommendedDentists: [],
    projects: [],
    greeting: '你好',
    activeTab: 'home',
    needsIdentity: false,
    pendingOperation: null,
    appointmentsLoading: false,
    busy: false,
    error: ''
  },

  onLoad() {
    this._requestGeneration = 0
  },

  onHide() {
    this._requestGeneration += 1
    this.setData({ busy: false, bindingBusy: false, bindingTicket: null })
  },

  onUnload() {
    this._requestGeneration += 1
  },

  onShow() {
    this.setGreeting()
    this.syncPendingOperation()
    if (!api.sessionToken()) {
      const generation = ++this._requestGeneration
      this._sessionToken = ''
      this.setData({ profile: null, appointments: [], guestReservations: [], nextVisit: null, clinics: [], recommendedDentists: [], appointmentsLoading: true, error: '' })
      api.ensureBookingSession().then((session) => {
        const token = api.sessionToken()
        if (generation !== this._requestGeneration || !token) return
        this._sessionToken = token
        this._sessionKind = session.subjectKind
        this._sessionSubjectKey = session.subjectKey
        this.setData({ subjectKind: session.subjectKind })
        if (session.subjectKind === 'PATIENT') return this.refreshData()
        return this.refreshGuestData()
      }).catch((error) => this.showError(error, generation)).finally(() => {
        if (generation === this._requestGeneration) this.setData({ appointmentsLoading: false })
      })
      return
    }
    this._sessionToken = api.sessionToken()
    this._sessionKind = api.sessionKind()
    this._sessionSubjectKey = api.sessionSubjectKey()
    this.setData({ subjectKind: this._sessionKind, nextVisit: null })
    if (this._sessionKind === 'GUEST') this.refreshGuestData()
    else this.refreshData()
  },

  onPullDownRefresh() {
    if (!api.sessionToken()) {
      wx.stopPullDownRefresh()
      return
    }
    this.refreshData().finally(() => wx.stopPullDownRefresh())
  },

  requestBinding() {
    if (this.data.bindingBusy || (api.sessionKind() !== 'GUEST' && !this.data.verifyWithoutSession)) return
    const generation = this._requestGeneration
    const token = api.sessionToken()
    const current = () => generation === this._requestGeneration && token === api.sessionToken()
    this.setData({ bindingBusy: true, bindingTicket: null, error: '' })
    new Promise((resolve, reject) => wx.login({
      success: result => result.code ? resolve(result.code) : reject(new Error('微信未返回登录凭证。')),
      fail: () => reject(new Error('微信登录未完成，请重试。'))
    })).then(code => {
      if (!current()) return null
      return api.request('/patient/wechat-binding-requests', {
        method: 'POST', data: { code }, sessionToken: '', guardSession: false
      })
    }).then(ticket => {
      if (!ticket || !current()) return
      this.setData({ bindingTicket: { code: ticket.bindingCode, expires: formatShanghaiDateTime(ticket.expiresAt) } })
    }).catch(error => {
      if (current()) this.setData({ error: error.message || '暂时无法生成核验码。' })
    }).finally(() => {
      if (current()) this.setData({ bindingBusy: false })
    })
  },

  refreshVerifiedLogin() {
    this.setData({ bindingTicket: null, bindingBusy: false })
    this.login()
  },

  openReminders() {
    if (api.sessionKind() === 'PATIENT') wx.navigateTo({ url: '/pages/reminders/index' })
  },

  openProfile() {
    if (api.sessionKind() === 'PATIENT') wx.navigateTo({ url: '/pages/profile/index' })
  },

  onLoginNameInput(event) {
    this.setData({ loginName: event.detail.value })
  },

  onPasswordInput(event) {
    this.setData({ password: event.detail.value })
  },

  retryHome() {
    if (this.data.busy || this.data.appointmentsLoading) return
    if (api.sessionKind() === 'GUEST') return this.refreshGuestData()
    if (this.data.profile) return this.refreshData()
    return this.login()
  },

  login() {
    if (this.data.busy) return
    const generation = ++this._requestGeneration
    this.setData({ busy: true, error: '' })
    api.bookingLogin()
      .then((session) => {
        const token = api.sessionToken()
        this._sessionToken = token
        this._sessionKind = session.subjectKind
        this._sessionSubjectKey = session.subjectKey
        if (!this.isCurrentSession(generation, token)) return
        this.setData({ profile: null, subjectKind: session.subjectKind, nextVisit: null, password: '', needsIdentity: false, verifyWithoutSession: false })
        if (session.subjectKind === 'PATIENT') {
          const displayProfile = this.displayProfile(session.profile)
          this.setData({ profile: displayProfile })
          getApp().globalData.profile = displayProfile
        }
        this.syncPendingOperation()
        const intent = this._loginIntent
        this._loginIntent = ''
        if (session.subjectKind === 'GUEST') {
          return this.refreshGuestData().then(() => {
            if (intent === 'booking') return this.navigateToBooking(session.subjectKind)
            if (intent === 'appointments') return this.scrollToGuestRecords()
            if (intent === 'experts') return this.scrollToExperts()
          }).finally(() => this.setData({ busy: false }))
        }
        if (intent === 'booking') return this.navigateToBooking(session.subjectKind)
        if (intent === 'followups') return this.openFollowups()
        return this.loadAppointments(generation).then(() => {
          if (intent === 'mine') this.scrollToMine()
          if (intent === 'appointments') this.scrollToAppointments()
          if (intent === 'experts') this.scrollToExperts()
        })
      })
      .catch((error) => this.showError(error, generation))
      .finally(() => {
        if (generation === this._requestGeneration) this.setData({ busy: false })
      })
  },

  isCurrentSession(generation, token = this._sessionToken) {
    return generation === this._requestGeneration && !!token && api.sessionToken() === token &&
      (!this._sessionKind || api.sessionKind() === this._sessionKind) &&
      (!this._sessionSubjectKey || !api.sessionSubjectKey() || api.sessionSubjectKey() === this._sessionSubjectKey)
  },

  displayProfile(profile) {
    if (!profile || !profile.bookingStatus || !profile.bookingStatus.restriction) return profile
    return {
      ...profile,
      bookingStatus: {
        ...profile.bookingStatus,
        restriction: {
          ...profile.bookingStatus.restriction,
          display_starts_at: formatShanghaiDateTime(profile.bookingStatus.restriction.starts_at),
          display_ends_at: formatShanghaiDateTime(profile.bookingStatus.restriction.ends_at)
        }
      }
    }
  },

  refreshData() {
    if (api.sessionKind() === 'GUEST') return this.refreshGuestData()
    const generation = ++this._requestGeneration
    const token = api.sessionToken()
    this._sessionToken = token
    this.loadProjects()
    this.setData({ appointmentsLoading: true, error: '' })
    return Promise.all([
      api.request('/patient/profile'),
      api.request('/patient/appointments'),
      api.request('/patient/clinics').catch(() => [])
    ]).then(async ([rawProfile, appointments, clinics]) => {
      if (!this.isCurrentSession(generation, token)) return
      if (!Array.isArray(appointments)) throw new Error('预约列表响应格式异常')
      const profile = this.displayProfile(rawProfile)
      const [displayAppointments, recommendedDentists] = await Promise.all([
        this.decorateAppointments(appointments, clinics),
        this.loadRecommendedDentists(clinics, generation, token)
      ])
      if (!this.isCurrentSession(generation, token)) return
      this.setData({ profile, appointments: displayAppointments, nextVisit: nextVisit(displayAppointments, 'appointment_status'), clinics, recommendedDentists, error: '' })
      getApp().globalData.profile = profile
      this.syncPendingOperation()
    }).catch((error) => this.showError(error, generation, token)).finally(() => {
      if (generation === this._requestGeneration) this.setData({ appointmentsLoading: false })
    })
  },

  refreshGuestData() {
    const generation = ++this._requestGeneration
    const token = api.sessionToken()
    const subjectKey = api.sessionSubjectKey()
    if (!token || api.sessionKind() !== 'GUEST' || !subjectKey) {
      this.setData({ guestReservations: [], nextVisit: null, guestBookingStatus: null, clinics: [], recommendedDentists: [], profile: null, appointments: [], appointmentsLoading: false })
      return Promise.resolve()
    }
    this.loadProjects()
    this._sessionToken = token
    this._sessionKind = 'GUEST'
    this._sessionSubjectKey = subjectKey
    this.setData({ appointmentsLoading: true, error: '', profile: null, appointments: [], nextVisit: null })
    return Promise.all([
      api.request('/guest/clinics'),
      api.request('/guest/booking-status'),
      api.request('/guest/reservations')
    ]).then(async ([clinics, status, reservations]) => {
      if (!this.isCurrentSession(generation, token)) return
      if (!Array.isArray(clinics) || !Array.isArray(reservations) || !status || typeof status.restricted !== 'boolean') {
        throw new Error('访客预约资料响应格式异常，请重试。')
      }
      const doctors = await this.loadGuestDentists(clinics, generation, token)
      if (!this.isCurrentSession(generation, token)) return
      const decoratedReservations = this.decorateGuestReservations(reservations, clinics, doctors)
      const latest = decoratedReservations[0] || null
      this.setData({
        subjectKind: 'GUEST', clinics, recommendedDentists: doctors,
        guestReservations: decoratedReservations,
        nextVisit: nextVisit(decoratedReservations, 'reservation_status'),
        guestBookingStatus: {
          ...status,
          displayRestrictedUntil: typeof status.restrictedUntil === 'string' && status.restrictedUntil
            ? formatShanghaiDateTime(status.restrictedUntil)
            : ''
        },
        guestContactName: latest ? latest.contact_name : '',
        guestContactPhone: latest ? latest.contact_phone : '',
        error: ''
      })
      this.syncPendingOperation()
    }).catch((error) => this.showError(error, generation, token)).finally(() => {
      if (generation === this._requestGeneration) this.setData({ appointmentsLoading: false })
    })
  },

  loadGuestDentists(clinics, generation, token) {
    return Promise.all(clinics.slice(0, 6).map((clinic) => {
      if (!clinic || clinic.clinic_id === undefined || clinic.clinic_id === null) return Promise.resolve([])
      return api.request(`/guest/dentists?clinicId=${encodeURIComponent(String(clinic.clinic_id))}`)
        .then((rows) => Array.isArray(rows) ? rows.map((row) => ({ ...row, clinicName: clinic.name })) : [])
    })).then((groups) => {
      if (!this.isCurrentSession(generation, token)) return []
      const seen = new Set()
      return groups.flat().filter((row) => {
        if (!row || row.dentist_id === undefined || row.dentist_id === null || !String(row.name || '').trim()) return false
        const id = String(row.dentist_id)
        if (seen.has(id)) return false
        seen.add(id)
        row.initial = Array.from(String(row.name))[0]
        return true
      })
    })
  },

  decorateGuestReservations(reservations, clinics, dentistRows) {
    const clinicById = new Map(clinics.map((clinic) => [String(clinic.clinic_id), clinic]))
    const dentists = new Map()
    dentistRows.forEach((dentist) => dentists.set(String(dentist.dentist_id), dentist.name))
    return reservations.map((row) => {
      if (!row || typeof row.reservation_no !== 'string' || !/^G-[A-Za-z0-9_-]{22,30}$/.test(row.reservation_no)) {
        throw new Error('访客预约记录格式异常，请刷新重试。')
      }
      const clinic = clinicById.get(String(row.clinic_id))
      return {
        ...row,
        display_start: formatShanghaiDateTime(row.scheduled_start),
        display_end: formatShanghaiDateTime(row.scheduled_end),
        display_clinic: clinic ? clinic.name : '门店信息暂不可用',
        display_dentist: dentists.get(String(row.dentist_id)) || '牙医信息暂不可用'
      }
    })
  },

  loadAppointments(generation) {
    const token = api.sessionToken()
    this._sessionToken = token
    this.setData({ appointmentsLoading: true })
    return Promise.all([
      api.request('/patient/appointments'),
      api.request('/patient/clinics').catch(() => [])
    ]).then(async ([appointments, clinics]) => {
      if (!this.isCurrentSession(generation, token)) return
      if (!Array.isArray(appointments)) throw new Error('预约列表响应格式异常')
      const [displayAppointments, recommendedDentists] = await Promise.all([
        this.decorateAppointments(appointments, clinics),
        this.loadRecommendedDentists(clinics, generation, token)
      ])
      if (!this.isCurrentSession(generation, token)) return
      this.setData({ appointments: displayAppointments, nextVisit: nextVisit(displayAppointments, 'appointment_status'), clinics, recommendedDentists })
    }).catch((error) => this.showError(error, generation, token)).finally(() => {
      if (generation === this._requestGeneration) this.setData({ appointmentsLoading: false })
    })
  },

  async decorateAppointments(appointments, clinics) {
    const clinicById = new Map((clinics || []).map((clinic) => [String(clinic.clinic_id), clinic]))
    const relevant = appointments.filter((appointment) =>
      ['待到诊', '已到诊', '待核实'].includes(appointment.appointment_status) && Date.parse(appointment.scheduled_end) > Date.now()
    )
    const clinicIds = Array.from(new Set(relevant.map((appointment) => String(appointment.clinic_id))))
    const dentistLists = await Promise.all(clinicIds.map((clinicId) =>
      api.request(`/patient/dentists?clinicId=${encodeURIComponent(clinicId)}`).catch(() => [])
    ))
    const dentistById = new Map()
    dentistLists.forEach((dentists) => {
      if (Array.isArray(dentists)) dentists.forEach((dentist) => dentistById.set(String(dentist.dentist_id), dentist))
    })
    return appointments.map((appointment) => {
      const upcoming = relevant.includes(appointment)
      const clinic = clinicById.get(String(appointment.clinic_id))
      const dentist = dentistById.get(String(appointment.dentist_id))
      return {
        ...appointment,
        display_start: formatShanghaiDateTime(appointment.scheduled_start),
        display_end: formatShanghaiDateTime(appointment.scheduled_end),
        display_clinic: upcoming ? (clinic ? clinic.name : '门店信息暂不可用') : '',
        display_dentist: upcoming ? (dentist ? dentist.name : '牙医信息暂不可用') : ''
      }
    })
  },

  setGreeting() {
    const hour = new Date().getHours()
    const greeting = hour < 6 ? '凌晨好' : hour < 12 ? '上午好' : hour < 14 ? '中午好' : hour < 18 ? '下午好' : '晚上好'
    if (greeting !== this.data.greeting) this.setData({ greeting })
  },

  loadProjects() {
    const path = api.sessionKind() === 'PATIENT' ? '/patient/treatment-types' : '/guest/treatment-types'
    api.request(path).then((rows) => {
      if (!Array.isArray(rows)) return
      const projects = rows
        .filter((row) => row && row.name)
        .slice(0, 4)
        .map((row) => ({
          id: String(row.treatment_type_id),
          name: String(row.name),
          price: Number(row.standard_unit_price || 0).toFixed(0),
          unit: String(row.unit_name || '次')
        }))
      this.setData({ projects })
    }).catch(() => {})
  },

  loadRecommendedDentists(clinics, generation, token) {
    if (!token || !Array.isArray(clinics) || clinics.length === 0) return Promise.resolve([])
    const eligibleClinics = clinics.slice(0, 6)
    return Promise.all(eligibleClinics.map((clinic) => {
      if (!clinic || clinic.clinic_id === undefined || clinic.clinic_id === null) return Promise.resolve([])
      const clinicId = String(clinic.clinic_id)
      return api.request(`/patient/dentists?clinicId=${encodeURIComponent(clinicId)}`).catch(() => [])
        .then((dentists) => ({ clinic, dentists: Array.isArray(dentists) ? dentists : [] }))
    })).then((groups) => {
      if (!this.isCurrentSession(generation, token)) return []
      const seen = new Set()
      const result = []
      groups.forEach(({ clinic, dentists }) => {
        dentists.forEach((dentist) => {
          if (!dentist || dentist.dentist_id === undefined || dentist.dentist_id === null) return
          const id = String(dentist.dentist_id)
          if (seen.has(id)) return
          seen.add(id)
          const name = String(dentist.name || '').trim()
          if (!name) return
          result.push({
            dentist_id: id,
            name,
            initial: Array.from(name)[0],
            clinicName: String(clinic.name || '可预约门店')
          })
        })
      })
      return result.slice(0, 3)
    })
  },

  logout() {
    if (this.data.busy) return
    const pending = api.sessionKind() === 'GUEST' ? guestOperations.readPending() : operations.readPending()
    if (pending) {
      this.setData({ error: '有一笔预约操作结果待核实。请先重试原请求，再退出登录。' })
      return
    }
    const generation = ++this._requestGeneration
    this.setData({ busy: true, error: '' })
    this._sessionToken = ''
    this.setData({ profile: null, appointments: [], guestReservations: [], nextVisit: null, guestBookingStatus: null, clinics: [], recommendedDentists: [], password: '', appointmentsLoading: false, subjectKind: '' })
    api.logout().catch((error) => this.showError(error, generation)).finally(() => {
      if (generation === this._requestGeneration) this.setData({ busy: false })
    })
  },

  openNextVisit() {
    const visit = this.data.nextVisit
    if (!visit || this._sessionToken !== api.sessionToken()) return
    const dataset = { id: visit.id, number: visit.id }
    const event = { currentTarget: { dataset } }
    if (api.sessionKind() === 'GUEST') this.openGuestReservation(event)
    else if (api.sessionKind() === 'PATIENT') this.openAppointment(event)
  },

  openAppointment(event) {
    const id = event.currentTarget.dataset.id
    if (typeof id !== 'string' || !/^\d+$/.test(id)) {
      this.setData({ error: '预约编号格式异常，请刷新列表。' })
      return
    }
    wx.navigateTo({ url: `/pages/appointment/index?id=${encodeURIComponent(id)}` })
  },

  openGuestReservation(event) {
    const number = event.currentTarget.dataset.number
    if (typeof number !== 'string' || !/^G-[A-Za-z0-9_-]{22,30}$/.test(number)) {
      this.setData({ error: '预约号格式异常，请刷新列表。' })
      return
    }
    wx.navigateTo({ url: `/pages/guest-reservation/index?reservationNo=${encodeURIComponent(number)}` })
  },

  openFollowups() {
    if (!api.sessionToken() || !this.data.profile) {
      this.requireIdentity('followups')
      return
    }
    wx.navigateTo({ url: '/pages/followups/index' })
  },

  openCatalogClinics() {
    wx.navigateTo({ url: '/pages/catalog/index?mode=clinics' })
  },

  openCatalogExperts() {
    wx.navigateTo({ url: '/pages/catalog/index?mode=experts' })
  },

  openRecordsPage() {
    wx.navigateTo({ url: '/pages/records/index' })
  },

  bookNew() {
    this.setData({ activeTab: 'home' })
    if (!api.sessionToken()) {
      this.setData({ busy: true, error: '' })
      return api.ensureBookingSession().then((session) => this.navigateToBooking(session.subjectKind)).catch((error) => this.showError(error)).finally(() => this.setData({ busy: false }))
    }
    if (api.sessionKind() === 'GUEST') {
      return this.navigateToBooking('GUEST')
    }
    if (!this.data.profile) {
      this.setData({ error: '正在读取本人预约资料，请稍候重试。' })
      return
    }
    if (this.data.pendingOperation) {
      this.setData({ error: '请先处理待核实的预约操作。' })
      return
    }
    if (this.data.profile && this.data.profile.bookingStatus.restricted) {
      this.setData({ error: '当前限制期内不能自助新增或改约；仍可查看、取消和联系门店前台。' })
      return
    }
    return this.navigateToBooking('PATIENT')
  },

  navigateToBooking(subjectKind) {
    if (subjectKind === 'GUEST') {
      if (guestOperations.readPending()) {
        this.setData({ error: '有一笔访客预约结果待核实，请先恢复原预约。' })
        return
      }
      wx.navigateTo({ url: '/pages/guest-booking/index' })
      return
    }
    wx.navigateTo({ url: '/pages/booking/index?mode=new' })
  },

  viewMine() {
    this.setData({ activeTab: 'mine' })
    if (api.sessionKind() === 'GUEST') return this.refreshGuestData().then(() => this.scrollToMine())
    if (!api.sessionToken()) {
      this._loginIntent = 'mine'
      return this.login()
    }
    if (!this.data.profile) return this.requireIdentity('mine')
    return this.refreshData().then(() => this.scrollToMine())
  },

  openAppointmentRecords() {
    if (api.sessionKind() === 'GUEST') return this.refreshGuestData().then(() => this.scrollToGuestRecords())
    if (!api.sessionToken()) {
      this._loginIntent = 'appointments'
      return this.login()
    }
    if (!this.data.profile) return this.requireIdentity('appointments')
    return this.refreshData().then(() => this.scrollToAppointments())
  },

  scrollToMine() {
    if (typeof wx.pageScrollTo === 'function') wx.pageScrollTo({ selector: '.profile-section', duration: 220 })
  },

  scrollToAppointments() {
    if (typeof wx.pageScrollTo === 'function') wx.pageScrollTo({ selector: '.appointments-card', duration: 220 })
  },

  scrollToGuestRecords() {
    if (typeof wx.pageScrollTo === 'function') wx.pageScrollTo({ selector: '.guest-reservations-card', duration: 220 })
  },

  scrollToExperts() {
    if (typeof wx.pageScrollTo === 'function') wx.pageScrollTo({ selector: '.doctor-section', duration: 220 })
  },

  goHome() {
    this.setData({ activeTab: 'home' })
    if (typeof wx.pageScrollTo === 'function') wx.pageScrollTo({ scrollTop: 0, duration: 220 })
  },

  openDentistBooking() {
    if (api.sessionKind() === 'GUEST') {
      if (this.data.recommendedDentists.length) return this.scrollToExperts()
      return this.navigateToBooking('GUEST')
    }
    if (!api.sessionToken() || !this.data.profile) {
      if (!api.sessionToken()) this._loginIntent = 'experts'
      return api.sessionToken() ? this.requireIdentity('experts') : this.login()
    }
    if (this.data.recommendedDentists.length) {
      this.scrollToExperts()
      return
    }
    this.bookNew()
  },

  requireIdentity(intent) {
    this._loginIntent = intent
    this.setData({ needsIdentity: true, error: '' })
    if (typeof wx.pageScrollTo === 'function') wx.pageScrollTo({ selector: '.identity-card', duration: 200 })
  },

  closeIdentity() {
    if (this.data.busy) return
    this._loginIntent = ''
    this._requestGeneration += 1
    this.setData({ needsIdentity: false, error: '' })
  },

  syncPendingOperation() {
    if (api.sessionKind() === 'GUEST') {
      const operation = guestOperations.readPending()
      const matchesSubject = !!operation && operation.subjectKind === 'GUEST' && operation.subjectKey === api.sessionSubjectKey()
      this.setData({ pendingOperation: operation ? { matchesSubject, guest: true } : null })
      return
    }
    const operation = operations.readPending()
    const patientId = this.data.profile && this.data.profile.patient && String(this.data.profile.patient.patient_id)
    const matchesPatient = !!patientId && !!operation && patientId === operation.patientId
    this.setData({ pendingOperation: operation ? (matchesPatient ? { kind: operation.kind, matchesPatient: true } : { matchesPatient: false }) : null })
  },

  retryPendingOperation() {
    if (this.data.busy) return
    if (api.sessionKind() === 'GUEST') {
      const operation = guestOperations.readPending()
      if (!operation) return
      this.setData({ busy: true, error: '' })
      const resume = api.ensureBookingSession().then((session) => {
        this._sessionToken = session.token
        this._sessionKind = session.subjectKind
        this._sessionSubjectKey = session.subjectKey
        if (session.subjectKind !== 'GUEST' || session.subjectKey !== operation.subjectKey) {
          throw Object.assign(new Error('这笔预约由另一个微信预约主体创建，当前主体不能恢复或重发。'), { code: 'SUBJECT_MISMATCH' })
        }
        return guestOperations.recover()
      }).then((reservation) => {
        this.syncPendingOperation()
        return this.refreshGuestData().then(() => {
          if (reservation && reservation.reservation_no) this.openGuestReservation({ currentTarget: { dataset: { number: reservation.reservation_no } } })
        })
      }).catch((error) => this.showError(error)).finally(() => this.setData({ busy: false }))
      return resume
    }
    const operation = operations.readPending()
    const patientId = this.data.profile && this.data.profile.patient && String(this.data.profile.patient.patient_id)
    if (!operation || !patientId || patientId !== operation.patientId) {
      this.setData({ error: '请使用创建这笔待核实操作的本人账号登录，再恢复原请求。' })
      return
    }
    this.setData({ busy: true, error: '' })
    operations.retryPending().then((result) => {
      this.syncPendingOperation()
      const appointment = result && result.appointment
      const id = appointment && appointment.appointment_id
      const linked = operation.path.match(/^\/patient\/followup-plans\/([1-9]\d*)\/appointments$/)
      if (linked && (!appointment || appointment.followup_plan_id !== linked[1] || typeof id !== 'string' || !/^\d+$/.test(id))) {
        followup.markLinkReview(patientId, linked[1], operation.operationNo)
        this.setData({ error: '服务端已返回预约结果，但未确认关联到原复诊计划。请先核对计划和预约记录，不要重复提交。' })
        return
      }
      return this.refreshData().then(() => {
        if (typeof id === 'string' && /^\d+$/.test(id)) {
          wx.navigateTo({ url: `/pages/appointment/index?id=${encodeURIComponent(id)}` })
        }
      })
    }).catch((error) => this.showError(error)).finally(() => this.setData({ busy: false }))
  },

  callClinic(event) {
    const phone = event.currentTarget.dataset.phone
    if (typeof phone === 'string' && phone.trim()) wx.makePhoneCall({ phoneNumber: phone })
  },

  showError(error, generation, token = this._sessionToken) {
    if (error.code === 'SESSION_CHANGED' || (generation !== undefined && generation !== this._requestGeneration)) return
    if (error.statusCode === 401) {
      this._sessionToken = ''
      this.setData({ profile: null, password: '', appointments: [], guestReservations: [], nextVisit: null, guestBookingStatus: null, clinics: [], recommendedDentists: [], subjectKind: '' })
    }
    this.syncPendingOperation()
    const message = error.writeOutcomeUncertain
      ? '请求结果暂时无法确认。已保留原操作内容和操作号，请重试原请求。'
      : error.code === 'PATIENT_BINDING_REQUIRED'
      ? '当前微信账号暂时不能建立预约会话，请稍后重试或联系门店核对账号状态。'
      : error.statusCode === 401
      ? '登录已失效，请重新登录。'
      : error.statusCode === 409
        ? `${error.message || '预约状态已变化'}（请刷新后重试）`
        : error.message || '请求失败，请稍后重试。'
    this.setData({ error: message, verifyWithoutSession: error.code === 'PATIENT_BINDING_REQUIRED' })
  }
})
