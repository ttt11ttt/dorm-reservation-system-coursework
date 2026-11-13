export type StaffGuestReservation = {
  reservationId: string
  reservationNo: string
  clinicId: string
  dentistId: string
  scheduleId: string
  startAt: string
  endAt: string
  contactName: string
  contactPhone: string
  requestedTreatments: string
  specialNeeds: string | null
  status: '待到诊' | '患者取消' | '诊所撤销' | '已改约' | '未到诊' | '已转换' | '待核实'
  rowVersion: number
  convertedAppointmentId: string | null
  createdAt: string
}
export type GuestReservationConversionPayload = { expectedRowVersion: number; patientId: string; evidenceRef: string; confirmedInPerson: true }
export function preserveStaffGuestReservations(raw: unknown, clinicId: string): StaffGuestReservation[]
export function makeGuestReservationConversionPayload(row: StaffGuestReservation, patientId: string, evidenceRef: string, confirmedInPerson: boolean): GuestReservationConversionPayload
export function preserveGuestReservationConversion(raw: unknown, expected: { clinicId: string; reservationId: string; reservationNo: string }): { replayed: boolean; appointmentId: string; reservation: StaffGuestReservation }
