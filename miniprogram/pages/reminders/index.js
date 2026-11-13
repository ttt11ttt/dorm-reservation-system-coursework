const api = require('../../utils/api')
const { formatShanghaiDateTime } = require('../../utils/date')

Page({
  data: { rows: [], busy: false, error: '' },
  onShow() { this.load() },
  onHide() { this.generation = (this.generation || 0) + 1 },
  onUnload() { this.onHide() },
  load() {
    const current = this.generation = (this.generation || 0) + 1
    this.setData({ rows: [], busy: true, error: '' })
    api.request('/patient/reminders').then(rows => {
      if (current !== this.generation) return
      this.setData({ rows: rows.map(row => ({ ...row, display_time: formatShanghaiDateTime(row.sent_at) })) })
    }).catch(error => {
      if (current === this.generation) this.setData({ error: error.message || '读取提醒失败。' })
    }).finally(() => {
      if (current === this.generation) this.setData({ busy: false })
    })
  }
})
