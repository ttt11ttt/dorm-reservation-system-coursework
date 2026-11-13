function pad(value) {
  return String(value).padStart(2, '0')
}

function formatShanghaiDateTime(value) {
  if (typeof value !== 'string' || !value) return '时间待核实'
  const instant = new Date(value)
  if (Number.isNaN(instant.getTime())) return '时间待核实'
  const local = new Date(instant.getTime() + 8 * 60 * 60 * 1000)
  return `${local.getUTCFullYear()}-${pad(local.getUTCMonth() + 1)}-${pad(local.getUTCDate())} ${pad(local.getUTCHours())}:${pad(local.getUTCMinutes())}`
}

function isZeroAmount(value) {
  return typeof value === 'string' && /^0+(?:\.0+)?$/.test(value)
}

module.exports = { formatShanghaiDateTime, isZeroAmount }
