const api = require('../../utils/api')
const { formatShanghaiDateTime } = require('../../utils/date')
const { generateCandidates } = require('../../utils/availability')
const followup = require('../../utils/followup')
const operations = require('../../utils/write-operations')
const { slotDate } = require('../../utils/registration')
const catalog = require('../../utils/catalog')

const defaultDurations = [30, 60, 90, 120]

Page({
  data: {
    mode: 'new',
    appointmentId: '',
    followupPlanId: '',
    followupPlan: null,
    linkedEntryRequired: false,
    entryInvalid: false,
    canCreateLinked: false,
    linkedResultNeedsReview: false,
    sourceAppointment: null,
    sourceTreatments: [],
    sourceInvoice: null,
    canCancelSource: false,
    canRescheduleSource: false,
    clinics: [],
    dentists: [],
    dentistOptions: [{ dentist_id: '', name: '不限医生' }],
    dateOptions: [],
    dateIndex: 0,
    selectedDate: '',
    selectedSlotLabel: '',
    selectedSlotDentist: '',
    selectionLocked: false,
    confirming: false,
    treatments: [],
    bookingStatus: null,
    durationOptions: defaultDurations.map((minutes) => ({ value: String(minutes), label: `${minutes} 分钟` })),
    clinicIndex: -1,
    dentistIndex: -1,
    selectedClinicId: '',
    selectedClinicName: '',
    selectedDentistId: '',
    selectedDentistName: '',
    selectedDuration: '',
    selectedDurationIndex: -1,
    candidates: [],
    selectedSlotKey: '',
    requestedTreatments: '',
    specialNeeds: '',
    reason: '',
    busy: false,
    loading: true,
    error: '',
    pendingOperation: null
  },

  onLoad(options) {
    this._catalogChoice = options && options.mode === 'new' && !options.planId ? catalog.selection(options) : null
    this._alive = true
    this._requestGeneration = 0
    this._resourceGeneration = 0
    this._entryInvalid = false
    const mode = options && options.mode
    const appointmentId = options && options.id
    const hasPlanId = !!options && Object.prototype.hasOwnProperty.call(options, 'planId')
    const hasPatientId = !!options && Object.prototype.hasOwnProperty.call(options, 'patientId')
    const followupPlanId = hasPlanId ? options.planId : ''
    this._linkedEntryRequired = hasPlanId
    this._followupPlanId = ''
    if (!['new', 'reschedule', 'cancel'].includes(mode) || (mode !== 'new' && (typeof appointmentId !== 'string' || !/^\d+$/.test(appointmentId))) || hasPatientId ||
      (hasPlanId && (mode !== 'new' || !followup.isId(followupPlanId)))) {
      this._entryInvalid = true
      this._sessionToken = api.sessionToken()
      this.setData({ loading: false, entryInvalid: true, linkedEntryRequired: hasPlanId, error: '办理入口参数无效，请返回预约列表重新打开。' })
      return
    }
    this._followupPlanId = followupPlanId
    this._sessionToken = api.sessionToken()
    this.setData({ mode, appointmentId: appointmentId || '', followupPlanId, followupPlan: null, canCreateLinked: false, linkedResultNeedsReview: false, linkedEntryRequired: hasPlanId, entryInvalid: false })
    this.loadWorkflow(false)
  },

  onShow() {
    if (!this._alive || !this.data.mode) return
    const token = api.sessionToken()
    if (!token) {
      this._sessionToken = ''
      this.clearForm('登录已失效，请返回重新登录。')
      return
    }
    if (token !== this._sessionToken) {
      this.clearForm('会话已切换，正在重新读取本人资料。')
      this._sessionToken = token
      this.loadWorkflow(false)
      return
    }
    this.syncPendingOperation()
  },

  onUnload() {
    this._alive = false
    this._requestGeneration += 1
    this._resourceGeneration += 1
    this._availability = null
  },

  onPullDownRefresh() {
    this.loadWorkflow(true).finally(() => wx.stopPullDownRefresh())
  },

  retryWorkflow() {
    if (this.data.busy || this.data.loading) return
    return this.loadWorkflow(true)
  },

  loadWorkflow(preserveInput) {
    if (!this._alive || this._entryInvalid) return Promise.resolve()
    const token = api.sessionToken()
    const sameSession = !!token && token === this._sessionToken
    const savedInput = preserveInput && sameSession ? {
      requestedTreatments: this.data.requestedTreatments,
      specialNeeds: this.data.specialNeeds,
      reason: this.data.reason
    } : {}
    if (!sameSession) this.clearForm(token ? '会话已切换，正在重新读取本人资料。' : '登录已失效，请返回重新登录。')
    this._sessionToken = token
    const generation = ++this._requestGeneration
    this.setData({ loading: true, error: '' })
    if (!token) {
      this.clearForm('登录已失效，请返回重新登录。')
      return Promise.resolve()
    }
    if (this._linkedEntryRequired && !this._followupPlanId) {
      this.clearForm('复诊关联已清理，请返回复诊计划并从本人账号重新打开。')
      return Promise.resolve()
    }
    const requests = [api.request('/patient/clinics'), api.request('/patient/booking-status'), api.request('/patient/profile')]
    if (this.data.mode !== 'cancel') requests.push(api.request('/patient/treatment-types'))
    if (this.data.mode !== 'new') requests.push(api.request(`/patient/appointments/${encodeURIComponent(this.data.appointmentId)}`))
    if (this._followupPlanId) requests.push(api.request(`/patient/followup-plans/${encodeURIComponent(this._followupPlanId)}`))
    return Promise.all(requests).then((responses) => {
      if (!this.isCurrent(generation, token)) return
      const clinics = responses[0]
      const bookingStatus = responses[1]
      const profile = responses[2]
      if (!profile || !profile.patient || typeof profile.patient.patient_id !== 'string') throw new Error('本人资料响应缺少字符串患者ID')
      let treatments = []
      let source = null
      let sourceTreatments = []
      let sourceInvoice = null
      let linkedPlan = null
      let responseIndex = 3
      if (this.data.mode !== 'cancel') treatments = responses[responseIndex++]
      if (this.data.mode !== 'new') {
        const detail = responses[responseIndex]
        if (!detail || !detail.appointment || !Array.isArray(detail.treatments) || !Object.prototype.hasOwnProperty.call(detail, 'invoice')) {
          throw new Error('本人预约详情响应格式异常')
        }
        source = detail.appointment
        sourceTreatments = detail.treatments
        sourceInvoice = detail.invoice || null
      }
      if (this._followupPlanId) {
        linkedPlan = responses[responseIndex]
        if (!followup.isPlan(linkedPlan) || linkedPlan.followup_plan_id !== this._followupPlanId) {
          throw new Error('无法核验这项复诊计划，请返回计划列表刷新后重试。')
        }
        if (linkedPlan.patient_id !== String(profile.patient.patient_id)) {
          throw new Error('此复诊计划不属于当前登录患者，请返回本人计划列表。')
        }
      }
      if (!Array.isArray(clinics) || !Array.isArray(treatments) || !bookingStatus || typeof bookingStatus.restricted !== 'boolean') {
        throw new Error('门店、目录或预约状态响应格式异常')
      }
      if (this.data.mode !== 'new' && !source) throw new Error('未读取到本人预约详情')
      if (source && String(source.patient_id) !== String(profile.patient.patient_id)) {
        throw new Error('预约详情与当前登录患者不匹配，请联系门店前台核实。')
      }
      const clinicIndex = source
        ? clinics.findIndex((clinic) => String(clinic.clinic_id) === String(source.clinic_id))
        : this._catalogChoice ? clinics.findIndex((clinic) => String(clinic.clinic_id) === this._catalogChoice.clinicId) : -1
      let durationOptions = defaultDurations.map((minutes) => ({ value: String(minutes), label: `${minutes} 分钟` }))
      let selectedDuration = this.data.mode === 'new' ? '30' : ''
      if (source && this.data.mode === 'reschedule') {
        const originalDuration = Math.round((Date.parse(source.scheduled_end) - Date.parse(source.scheduled_start)) / 60000)
        if (Number.isInteger(originalDuration) && originalDuration > 0 && !durationOptions.some((option) => Number(option.value) === originalDuration)) {
          durationOptions = [...durationOptions, { value: String(originalDuration), label: `保留原时长（${originalDuration} 分钟）` }]
        }
        if (Number.isInteger(originalDuration) && originalDuration > 0) selectedDuration = String(originalDuration)
      }
      const displayStatus = bookingStatus.restriction ? {
        ...bookingStatus,
        restriction: {
          ...bookingStatus.restriction,
          display_starts_at: formatShanghaiDateTime(bookingStatus.restriction.starts_at),
          display_ends_at: formatShanghaiDateTime(bookingStatus.restriction.ends_at)
        }
      } : bookingStatus
      source = source ? {
        ...source,
        display_start: formatShanghaiDateTime(source.scheduled_start),
        display_end: formatShanghaiDateTime(source.scheduled_end)
      } : null
      const selectedDurationIndex = durationOptions.findIndex((option) => option.value === selectedDuration)
      const sourcePending = !!source && ['待到诊', '待核实'].includes(source.appointment_status)
      const sourceNoVisitFacts = sourcePending && !source.attended && source.treatment_status === '未开始' && sourceTreatments.length === 0 && !sourceInvoice
      const linkedResultNeedsReview = !!linkedPlan && followup.hasLinkReview(String(profile.patient.patient_id), linkedPlan.followup_plan_id)
      this.setData({
        clinics, treatments, sourceTreatments, sourceInvoice, bookingStatus: displayStatus, sourceAppointment: source,
        followupPlan: linkedPlan,
        followupPlanId: linkedPlan ? linkedPlan.followup_plan_id : '',
        linkedEntryRequired: this._linkedEntryRequired,
        canCreateLinked: !!linkedPlan && linkedPlan.status === '待复诊' && !bookingStatus.restricted && !linkedResultNeedsReview,
        linkedResultNeedsReview,
        canCancelSource: sourceNoVisitFacts,
        canRescheduleSource: sourceNoVisitFacts && !bookingStatus.restricted,
        clinicIndex, selectedClinicId: clinicIndex >= 0 ? String(clinics[clinicIndex].clinic_id) : '',
        selectedClinicName: clinicIndex >= 0 ? clinics[clinicIndex].name : '',
        selectedDentistId: source && this.data.mode === 'reschedule' ? String(source.dentist_id) : this._catalogChoice ? this._catalogChoice.dentistId : '',
        ...(this._catalogChoice ? { selectedDate: this._catalogChoice.date } : {}),
        selectedDentistName: '',
        selectedDuration, selectedDurationIndex, durationOptions,
        requestedTreatments: savedInput.requestedTreatments !== undefined ? savedInput.requestedTreatments : (source && this.data.mode === 'reschedule' ? source.requested_treatments : ''),
        specialNeeds: savedInput.specialNeeds !== undefined ? savedInput.specialNeeds : (source && this.data.mode === 'reschedule' ? source.special_needs || '' : ''),
        reason: savedInput.reason || '',
        loading: false
      })
      this._patientId = String(profile.patient.patient_id)
      this.syncPendingOperation()
      if (clinicIndex >= 0 && this.data.mode !== 'cancel' && (!source || sourceNoVisitFacts)) return this.loadClinicResources(generation, token)
      return undefined
    }).catch((error) => {
      if (!this._alive || generation !== this._requestGeneration) return
      if (error.statusCode === 401 || error.code === 'SESSION_CHANGED' || token !== api.sessionToken()) {
        this.clearForm(error.statusCode === 401
          ? '登录已失效，请返回重新登录。'
          : '会话已切换，请重新登录后读取本人资料。')
      }
      else this.setData({ loading: false, error: error.message || '读取预约办理资料失败，请刷新重试。' })
    })
  },

  loadClinicResources(workflowGeneration = this._requestGeneration, sessionToken = api.sessionToken()) {
    if (!this._alive || !sessionToken || sessionToken !== api.sessionToken() || workflowGeneration !== this._requestGeneration) return Promise.resolve()
    const clinicId = this.data.selectedClinicId
    if (!clinicId) return Promise.resolve()
    const generation = ++this._resourceGeneration
    const rescheduleId = this.data.mode === 'reschedule' ? '&rescheduleId=' + encodeURIComponent(this.data.appointmentId) : ''
    this.setData({ candidates: [], selectedSlotKey: '', error: '' })
    return Promise.all([
      api.request(`/patient/dentists?clinicId=${encodeURIComponent(clinicId)}`),
      api.request(`/patient/availability?clinicId=${encodeURIComponent(clinicId)}${rescheduleId}`)
    ]).then(([dentists, availability]) => {
      if (!this.isCurrent(workflowGeneration, sessionToken) || generation !== this._resourceGeneration || clinicId !== this.data.selectedClinicId) return
      if (!Array.isArray(dentists) || !availability || !Array.isArray(availability.windows)) throw new Error('出诊或时段响应格式异常')
      this._availability = availability
      const dentistIndex = dentists.findIndex((dentist) => String(dentist.dentist_id) === this.data.selectedDentistId)
      const selectedDentist = dentistIndex >= 0 ? dentists[dentistIndex] : null
      this.setData({
        dentists, dentistIndex, dentistOptions: [{ dentist_id: '', name: '不限医生' }, ...dentists],
        selectedDentistId: selectedDentist ? String(selectedDentist.dentist_id) : '',
        selectedDentistName: selectedDentist ? selectedDentist.name : ''
      })
      this.recomputeCandidates()
    }).catch((error) => {
      if (!this._alive || workflowGeneration !== this._requestGeneration || generation !== this._resourceGeneration) return
      if (error.statusCode === 401 || error.code === 'SESSION_CHANGED' || sessionToken !== api.sessionToken()) {
        this.clearForm(error.statusCode === 401
          ? '登录已失效，请返回重新登录。'
          : '会话已切换，请重新登录后读取本人资料。')
        return
      }
      this.setData({ dentists: [], candidates: [], loading: false, error: error.message || '读取出诊和可约时段失败，请重试。' })
    })
  },

  recomputeCandidates() {
    const { selectedDuration } = this.data
    if (!this._availability || !selectedDuration) {
      this.setData({ candidates: [], selectedSlotKey: '', selectedSlotLabel: '', selectedSlotDentist: '', dateOptions: [], selectedDate: '' })
      return
    }
    const all = this.currentCandidates(true)
    const dates = Array.from(new Set(all.map((slot) => slotDate(slot.scheduledStart))))
    const selectedDate = dates.includes(this.data.selectedDate) ? this.data.selectedDate : (dates[0] || '')
    this.setData({ selectedDate, dateOptions: dates.map((value) => ({ value, label: value })), dateIndex: Math.max(0, dates.indexOf(selectedDate)) })
    const candidates = this.currentCandidates()
    const selectedStillAvailable = candidates.some((slot) => slot.key === this.data.selectedSlotKey)
    this.setData({ candidates, selectedSlotKey: selectedStillAvailable ? this.data.selectedSlotKey : '', selectedSlotLabel: selectedStillAvailable ? this.data.selectedSlotLabel : '', selectedSlotDentist: selectedStillAvailable ? this.data.selectedSlotDentist : '' })
    if (this._catalogChoice) {
      const incoming = catalog.matchingSlot(this._catalogChoice, candidates)
      this._catalogChoice = null
      this.setData(incoming ? { selectedSlotKey: incoming.key, selectedSlotLabel: `${incoming.displayStart} – ${incoming.displayEnd}`, selectedSlotDentist: incoming.dentistName, selectionLocked: true } : { selectionLocked: false, error: '所选时段已变化，请重新选择当前可约时段。' })
    }
  },

  changeSelection() { this.setData({ selectionLocked: false }) },

  currentCandidates(allDates = false) {
    const { selectedDuration, selectedDentistId } = this.data
    if (!this._availability || !selectedDuration) return []
    return generateCandidates({ availability: this._availability, durationMinutes: Number(selectedDuration) })
      .filter((slot) => (!selectedDentistId || slot.dentistId === selectedDentistId) && (allDates || !this.data.selectedDate || slotDate(slot.scheduledStart) === this.data.selectedDate))
      .map((slot) => ({ ...slot, durationLabel: `${slot.durationMinutes} 分钟`, dentistName: (this.data.dentists.find((dentist) => String(dentist.dentist_id) === slot.dentistId) || {}).name || '出诊医生', timeLabel: slot.displayStart.slice(-5) + ' – ' + slot.displayEnd.slice(-5) }))
  },

  onClinicChange(event) {
    const clinicIndex = Number(event.detail.value)
    if (!Number.isInteger(clinicIndex) || !this.data.clinics[clinicIndex]) return
    this._availability = null
    this._resourceGeneration += 1
    this.setData({ clinicIndex, selectedClinicId: String(this.data.clinics[clinicIndex].clinic_id), selectedClinicName: this.data.clinics[clinicIndex].name, selectedDentistId: '', selectedDentistName: '', dentistIndex: -1, dentists: [], candidates: [], selectedSlotKey: '' })
    this.loadClinicResources()
  },

  onDentistChange(event) {
    const dentistIndex = Number(event.detail.value)
    const dentist = (this.data.dentistOptions || this.data.dentists)[dentistIndex]
    if (!dentist) return
    this.setData({ dentistIndex: this.data.dentistOptions ? dentistIndex - 1 : dentistIndex, selectedDentistId: String(dentist.dentist_id), selectedDentistName: dentist.name, candidates: [], selectedSlotKey: '' })
    this.recomputeCandidates()
  },

  onDateChange(event) {
    const dateIndex = Number(event.detail.value)
    const option = this.data.dateOptions[dateIndex]
    if (!option) return
    this.setData({ dateIndex, selectedDate: option.value, selectedSlotKey: '', selectedSlotLabel: '', selectedSlotDentist: '' })
    this.recomputeCandidates()
  },

  onDurationChange(event) {
    const index = Number(event.detail.value)
    const option = this.data.durationOptions[index]
    if (!option) return
    this.setData({ selectedDuration: option.value, selectedDurationIndex: index, selectedSlotKey: '' })
    this.recomputeCandidates()
  },

  selectSlot(event) {
    const index = Number(event.currentTarget.dataset.index)
    const slot = this.data.candidates[index]
    if (slot) this.setData({ selectedSlotKey: slot.key, selectedSlotLabel: `${slot.displayStart} – ${slot.displayEnd}`, selectedSlotDentist: slot.dentistName })
  },

  onRequestedTreatmentsInput(event) { this.setData({ requestedTreatments: event.detail.value }) },
  onSpecialNeedsInput(event) { this.setData({ specialNeeds: event.detail.value }) },
  onReasonInput(event) { this.setData({ reason: event.detail.value }) },

  startSubmit() {
    if (this.data.entryInvalid || this.data.busy || this.data.confirming || this.data.pendingOperation) return
    const token = api.sessionToken()
    if (!token || token !== this._sessionToken) {
      this.clearForm(token ? '会话已切换，正在重新读取本人资料。' : '登录已失效，请返回重新登录。')
      if (token) this.loadWorkflow(false)
      return
    }
    if (this.data.linkedResultNeedsReview) {
      this.setData({ error: '服务端已返回预约结果但关联未核实，该计划的自助关联预约已暂停。请联系门店前台核对，不要重复提交。' })
      return
    }
    if (this._linkedEntryRequired && (!this._followupPlanId || !this.data.canCreateLinked || !this.data.followupPlan || this.data.followupPlan.followup_plan_id !== this._followupPlanId)) {
      this.setData({ error: '当前复诊计划不可关联预约，请返回计划列表核对计划状态。' })
      return
    }
    if (operations.readPending()) {
      this.syncPendingOperation()
      this.setData({ error: '已有一笔操作结果待核实，请先重试原请求。' })
      return
    }
    if (this.data.mode === 'cancel') {
      if (!this.data.sourceAppointment || !Number.isInteger(this.data.sourceAppointment.row_version) || !this.data.reason.trim()) {
        this.setData({ error: '请刷新预约并填写取消原因。' })
        return
      }
      wx.showModal({
        title: '确认告知取消',
        content: '距预约开始至少3小时的取消按正常调整处理；少于3小时会记录临近取消事件，但仍可取消并释放时段。服务端时间和预约状态为准。',
        confirmText: '确认取消',
        success: (result) => { if (result.confirm) this.submitCancel() }
      })
      return
    }
    if (this.data.bookingStatus && this.data.bookingStatus.restricted) {
      this.setData({ error: '当前限制期不能自助新增或改约。仍可查询、取消或联系门店前台。' })
      return
    }
    if (!this.data.selectedClinicId || !this.data.selectedDuration || !this.data.selectedSlotKey || !this.data.requestedTreatments.trim()) {
      this.setData({ error: '请选择门店、牙医、预约时长和候选时段，并填写就诊需求。' })
      return
    }
    const currentCandidates = this.currentCandidates()
    const slot = currentCandidates.find((candidate) => candidate.key === this.data.selectedSlotKey)
    if (!slot) {
      this.setData({ candidates: currentCandidates, selectedSlotKey: '' })
      this.setData({ error: '候选时段已变化，请刷新后重新选择。' })
      return
    }
    const generation = this._requestGeneration
    const requestedTreatments = this.data.requestedTreatments
    const specialNeeds = this.data.specialNeeds
    const reason = this.data.reason
    this.setData({ confirming: true })
    wx.showModal({
      title: this.data.mode === 'reschedule' ? '确认改约安排' : '确认预约挂号',
      content: `${this.data.selectedClinicName}\n${slot.dentistName}\n${slot.displayStart} – ${slot.displayEnd}\n就诊需求：${requestedTreatments.trim().slice(0, 140)}${requestedTreatments.trim().length > 140 ? '…' : ''}`,
      confirmText: '确认提交',
      success: (result) => {
        if (!result.confirm || !this.isCurrent(generation, token) || this.data.pendingOperation || this.data.busy) return
        if (this.data.selectedSlotKey !== slot.key || requestedTreatments !== this.data.requestedTreatments || specialNeeds !== this.data.specialNeeds || reason !== this.data.reason) return
        const refreshedSlot = this.currentCandidates().find((candidate) => candidate.key === slot.key)
        if (!refreshedSlot) { this.recomputeCandidates(); this.setData({ error: '时段已变化，请重新选择。' }); return }
        if (this.data.mode === 'reschedule') this.submitReschedule(refreshedSlot)
        else this.submitBooking(refreshedSlot)
      },
      complete: () => { if (this._alive) this.setData({ confirming: false }) }
    })
  },

  submitBooking(slot) {
    if (this._linkedEntryRequired && (!this._followupPlanId || !this.data.canCreateLinked || !this.data.followupPlan || this.data.followupPlan.followup_plan_id !== this._followupPlanId)) {
      this.setData({ error: '当前复诊计划未通过本人和状态核验，不能提交关联预约。' })
      return
    }
    const payload = {
      clinicId: slot.clinicId,
      dentistId: slot.dentistId,
      scheduleId: slot.scheduleId,
      scheduledStart: slot.scheduledStart,
      scheduledEnd: slot.scheduledEnd,
      requestedTreatments: this.data.requestedTreatments.trim()
    }
    if (this.data.specialNeeds.trim()) payload.specialNeeds = this.data.specialNeeds.trim()
    const path = this._followupPlanId
      ? `/patient/followup-plans/${encodeURIComponent(this._followupPlanId)}/appointments`
      : '/patient/appointments'
    const operation = operations.makeOperation(this._followupPlanId ? '复诊预约新增' : '预约新增', 'POST', path, payload, true, this._patientId)
    this.sendOperation(operation)
  },

  submitReschedule(slot) {
    const source = this.data.sourceAppointment
    if (!this.data.canRescheduleSource || !source || !Number.isInteger(source.row_version)) {
      this.setData({ error: '此预约的到诊或诊疗状态需要人工核实，不能直接改约。' })
      return
    }
    if (!this.data.reason.trim()) {
      this.setData({ error: '请说明改约原因。' })
      return
    }
    const payload = {
      expectedRowVersion: source.row_version,
      clinicId: slot.clinicId,
      dentistId: slot.dentistId,
      scheduleId: slot.scheduleId,
      scheduledStart: slot.scheduledStart,
      scheduledEnd: slot.scheduledEnd,
      requestedTreatments: this.data.requestedTreatments.trim(),
      reason: this.data.reason.trim()
    }
    if (this.data.specialNeeds.trim()) payload.specialNeeds = this.data.specialNeeds.trim()
    const path = `/patient/appointments/${encodeURIComponent(this.data.appointmentId)}/reschedule`
    const operation = operations.makeOperation('预约改约', 'POST', path, payload, true, this._patientId)
    this.sendOperation(operation)
  },

  submitCancel() {
    const source = this.data.sourceAppointment
    if (!this.data.canCancelSource || !source || !Number.isInteger(source.row_version) || !this.data.reason.trim()) {
      this.setData({ error: '当前预约已不符合自助取消条件，请刷新并联系门店前台核实。' })
      return
    }
    const payload = { expectedRowVersion: source.row_version, reason: this.data.reason.trim() }
    const path = `/patient/appointments/${encodeURIComponent(this.data.appointmentId)}/cancel`
    this.sendOperation(operations.makeOperation('预约取消', 'POST', path, payload, false, this._patientId))
  },

  sendOperation(operation) {
    if (!this._alive) return
    const token = api.sessionToken()
    const generation = this._requestGeneration
    this.setData({ busy: true, error: '' })
    operations.start(operation).then((result) => this.completeOperation(result, operation)).catch((error) => {
      if (!this._alive || generation !== this._requestGeneration) return
      if (error.statusCode === 401 || error.code === 'SESSION_CHANGED' || token !== api.sessionToken()) {
        this.clearForm(error.statusCode === 401
          ? '登录已失效，请返回重新登录。'
          : '会话已切换，请重新登录后核实原操作。')
        this.syncPendingOperation()
        return
      }
      this.syncPendingOperation()
      const message = this.errorMessage(error)
      this.setData({ error: message, busy: false })
      if (error.statusCode === 409 && !operations.readPending()) {
        this.loadWorkflow(true).finally(() => {
          if (this._alive && token === api.sessionToken()) this.setData({ error: message })
        })
      }
    })
  },

  retryPendingOperation() {
    if (!this._alive || this.data.busy) return
    const token = api.sessionToken()
    if (!token || token !== this._sessionToken) {
      this.clearForm(token ? '会话已切换，正在重新读取本人资料。' : '登录已失效，请返回重新登录。')
      if (token) this.loadWorkflow(false)
      return
    }
    const operation = operations.readPending()
    if (!operation || operation.patientId !== this._patientId) {
      this.setData({ error: '请返回我的预约并使用创建原操作的本人账号恢复。' })
      return
    }
    const generation = this._requestGeneration
    this.setData({ busy: true, error: '' })
    operations.retryPending().then((result) => this.completeOperation(result, operation)).catch((error) => {
      if (!this._alive || generation !== this._requestGeneration) return
      if (error.statusCode === 401 || error.code === 'SESSION_CHANGED' || token !== api.sessionToken()) {
        this.clearForm(error.statusCode === 401
          ? '登录已失效，请返回重新登录。原操作仍待本人账号核实。'
          : '会话已切换，请重新登录后核实原操作。')
        this.syncPendingOperation()
        return
      }
      this.syncPendingOperation()
      this.setData({ busy: false, error: this.errorMessage(error) })
    })
  },

  completeOperation(result, operation) {
    if (!this._alive) return
    const token = api.sessionToken()
    if (!token || token !== this._sessionToken) {
      this.clearForm('会话已切换，请重新登录后核实原操作。')
      this.syncPendingOperation()
      return
    }
    const appointment = result && result.appointment
    const id = appointment && appointment.appointment_id
    this.syncPendingOperation()
    this.setData({ busy: false })
    const linkedMatch = operation && operation.path.match(/^\/patient\/followup-plans\/([1-9]\d*)\/appointments$/)
    if (linkedMatch) {
      const expectedPlanId = linkedMatch[1]
      if (!appointment || appointment.followup_plan_id !== expectedPlanId || this._patientId !== operation.patientId) {
        followup.markLinkReview(operation.patientId, expectedPlanId, operation.operationNo)
        this.setData({
          linkedResultNeedsReview: true,
          error: '服务端已返回预约结果，但未确认该预约关联到当前复诊计划。请先核对计划和预约记录，不要重复提交。'
        })
        return
      }
    }
    if (typeof id !== 'string' || !/^\d+$/.test(id)) {
      if (linkedMatch) {
        followup.markLinkReview(operation.patientId, linkedMatch[1], operation.operationNo)
        this.setData({ linkedResultNeedsReview: true })
      }
      this.setData({ error: '服务端已返回成功，但缺少预约编号。请返回我的预约刷新确认。' })
      return
    }
    wx.redirectTo({ url: `/pages/appointment/index?id=${encodeURIComponent(id)}` })
  },

  errorMessage(error) {
    if (error.writeOutcomeUncertain) return '结果暂时无法确认。原始请求内容和操作号已保留，请重试原请求。'
    if (error.code === 'BOOKING_RESTRICTED') return error.message || '当前自助预约受限，请联系门店前台。'
    if (error.code === 'MANUAL_REVIEW_REQUIRED') return '此预约已有到诊或诊疗处理，需要联系门店前台核实。'
    if (error.code === 'ROW_VERSION_CONFLICT') return '预约状态已更新，页面资料已刷新。请核对后重新操作。'
    if (['TIME_CONFLICT', 'OUTSIDE_COVERAGE', 'RESOURCE_INACTIVE', 'RETRY_TRANSACTION'].includes(error.code)) return '所选时段或资源已变化，候选已刷新，请重新选择。'
    if (error.code === 'IDEMPOTENCY_CONFLICT') return '服务端报告操作号内容冲突。原请求仍已保留，请联系门店前台核实。'
    if (error.statusCode === 401) return '登录已失效，请返回并重新登录。'
    if (error.statusCode === 409) return error.message || '预约状态冲突，请刷新后重试。'
    return error.message || '请求失败，请检查网络后重试。'
  },

  syncPendingOperation() {
    if (!this._alive) return
    const operation = operations.readPending()
    const matchesPatient = !!operation && !!this._patientId && this._patientId === operation.patientId
    this.setData({ pendingOperation: operation ? (matchesPatient ? { kind: operation.kind, matchesPatient: true } : { matchesPatient: false }) : null })
  },

  clearForm(message) {
    this._requestGeneration += 1
    this._resourceGeneration += 1
    this._availability = null
    this._patientId = ''
    this._sessionToken = ''
    this._followupPlanId = ''
    this.setData({
      loading: false, busy: false, confirming: false, clinics: [], dentists: [], dentistOptions: [{ dentist_id: '', name: '不限医生' }], treatments: [], dateOptions: [], selectedDate: '', dateIndex: 0, selectedSlotLabel: '', selectedSlotDentist: '',
      sourceAppointment: null, followupPlan: null, followupPlanId: '', canCreateLinked: false,
      linkedResultNeedsReview: false, entryInvalid: this._entryInvalid, bookingStatus: null, candidates: [], selectedSlotKey: '',
      selectedClinicId: '', selectedClinicName: '', selectedDentistId: '', selectedDentistName: '',
      sourceTreatments: [], sourceInvoice: null, canCancelSource: false, canRescheduleSource: false,
      requestedTreatments: '', specialNeeds: '', reason: '', pendingOperation: null, error: message
    })
  },

  isCurrent(generation, token) {
    return this._alive && generation === this._requestGeneration && !!token && token === api.sessionToken()
  },

  callClinic(event) {
    const phone = event.currentTarget.dataset.phone
    if (typeof phone === 'string' && phone.trim()) wx.makePhoneCall({ phoneNumber: phone })
  }
})
