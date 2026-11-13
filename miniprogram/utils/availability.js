const { formatShanghaiDateTime } = require('./date')

function overlaps(start, end, blockedStart, blockedEnd) {
  return start < blockedEnd && end > blockedStart
}

function generateCandidates({ availability, durationMinutes, nowMs = Date.now(), stepMinutes = 30 }) {
  if (!availability || !Array.isArray(availability.windows)) return []
  if (!Number.isInteger(durationMinutes) || durationMinutes <= 0 || !Number.isInteger(stepMinutes) || stepMinutes <= 0) return []
  const asOf = Date.parse(availability.asOf)
  if (!Number.isFinite(asOf)) return []
  const earliest = Math.max(asOf, nowMs)
  const durationMs = durationMinutes * 60 * 1000
  const stepMs = stepMinutes * 60 * 1000
  const occupied = Array.isArray(availability.occupied) ? availability.occupied : []
  // patientOccupied has no appointment ID. Keep every interval blocked, including the
  // source appointment during reschedule; matching timestamps cannot prove identity.
  const patientOccupied = Array.isArray(availability.patientOccupied) ? availability.patientOccupied : []
  const slots = []
  const seen = new Set()

  for (const window of availability.windows) {
    const windowStart = Date.parse(window.start_at)
    const windowEnd = Date.parse(window.end_at)
    if (!Number.isFinite(windowStart) || !Number.isFinite(windowEnd) || windowEnd <= windowStart) continue
    const firstStep = Math.max(0, Math.ceil((earliest - windowStart) / stepMs))
    for (let start = windowStart + firstStep * stepMs; start + durationMs <= windowEnd; start += stepMs) {
      const end = start + durationMs
      const dentistId = String(window.dentist_id)
      const dentistConflict = occupied.some((item) => String(item.dentist_id) === dentistId && overlaps(
        start, end, Date.parse(item.scheduled_start), Date.parse(item.scheduled_end)
      ))
      const patientConflict = patientOccupied.some((item) => overlaps(
        start, end, Date.parse(item.scheduled_start), Date.parse(item.scheduled_end)
      ))
      if (dentistConflict || patientConflict) continue
      const scheduledStart = new Date(start).toISOString()
      const scheduledEnd = new Date(end).toISOString()
      const scheduleId = String(window.schedule_id)
      const clinicId = String(window.clinic_id)
      const key = `${scheduleId}|${dentistId}|${scheduledStart}|${scheduledEnd}`
      if (seen.has(key)) continue
      seen.add(key)
      slots.push({
        key,
        scheduleId,
        clinicId,
        dentistId,
        scheduledStart,
        scheduledEnd,
        durationMinutes,
        displayStart: formatShanghaiDateTime(scheduledStart),
        displayEnd: formatShanghaiDateTime(scheduledEnd)
      })
    }
  }
  return slots.sort((a, b) => a.scheduledStart.localeCompare(b.scheduledStart) || a.key.localeCompare(b.key))
}

module.exports = { generateCandidates, overlaps }
