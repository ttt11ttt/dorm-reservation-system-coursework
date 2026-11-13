const api = require('../../utils/api')

Page({
  data: { patient: null, items: [], declaration: '', busy: false, error: '', notice: '' },
  onShow() { this.load() },
  load() {
    this.setData({ busy: true, error: '' })
    api.request('/patient/profile').then(({ patient, allergies }) => {
      this.setData({ patient, declaration: patient.allergy_declaration, items: allergies || [] })
    }).catch(error => this.setData({ error: error.message })).finally(() => this.setData({ busy: false }))
  },
  contactInput(event) {
    this.setData({ ['patient.' + event.currentTarget.dataset.field]: event.detail.value })
  },
  declare(event) {
    this.setData({ declaration: event.detail.value })
  },
  addItem() {
    if (this.data.items.length < 50) this.setData({ items: [...this.data.items, { substance: '', reaction_note: '' }] })
  },
  removeItem(event) {
    this.setData({ items: this.data.items.filter((_, index) => index !== Number(event.currentTarget.dataset.index)) })
  },
  allergyInput(event) {
    const { index, field } = event.currentTarget.dataset
    this.setData({ ['items[' + index + '].' + field]: event.detail.value })
  },
  save(path, payload, update) {
    if (this.data.busy || !this.data.patient) return
    this.setData({ busy: true, notice: '', error: '' })
    api.request('/patient/profile/' + path, { method: 'PUT', data: payload })
      .then(result => this.setData({ ...update(result), notice: '已保存。' }))
      .catch(error => this.setData({ error: error.message }))
      .finally(() => this.setData({ busy: false }))
  },
  saveContact() {
    const p = this.data.patient
    this.save('contact', {
      expectedVersion: p.row_version, phone: p.phone, email: p.email || null, billingAddress: p.billing_address,
      emergencyName: p.emergency_name, emergencyPhone: p.emergency_phone
    }, patient => ({ patient }))
  },
  saveAllergies() {
    const declaration = this.data.declaration
    if (declaration === '未询问') { this.setData({ error: '请选择过敏情况。' }); return }
    const items = declaration === '已报告过敏'
      ? this.data.items.map(row => ({ substance: row.substance.trim(), reactionNote: (row.reaction_note || '').trim() || null })) : []
    this.save('allergies', { expectedVersion: this.data.patient.row_version, declaration, items }, result => result)
  }
})
