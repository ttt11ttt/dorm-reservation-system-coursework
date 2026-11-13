// Slot dates follow the service's Asia/Shanghai business timezone on every device.
function slotDate(value) {
  const instant = Date.parse(value)
  return Number.isFinite(instant) ? new Date(instant + 8 * 60 * 60 * 1000).toISOString().slice(0, 10) : ''
}

module.exports = { slotDate }
