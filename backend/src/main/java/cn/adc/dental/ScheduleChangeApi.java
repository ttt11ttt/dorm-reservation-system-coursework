package cn.adc.dental;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/org")
class ScheduleChangeApi {
    private final Store db;
    private final Identity identity;

    ScheduleChangeApi(Store db, Identity identity) {
        this.db = db;
        this.identity = identity;
    }

    record Change(String expectedVersion, String endAt, String reason) {}

    @GetMapping("/clinics/{id}/upcoming-schedules")
    Object upcoming(@RequestHeader(value="Authorization",required=false) String header,
                    @PathVariable String id, @RequestParam String date) {
        OrganizationRules.chainOnly(identity.require(header));
        var day = ExpenseRules.date(date);
        var start = Timestamp.from(day.atStartOfDay(CatalogPolicy.ZONE).toInstant());
        var end = Timestamp.from(day.plusDays(1).atStartOfDay(CatalogPolicy.ZONE).toInstant());
        return Store.json(db.jdbc.queryForList("""
            SELECT s.schedule_id,s.start_at,s.end_at,s.row_version,d.name AS dentist_name
            FROM dentist_schedules s JOIN dentists d ON d.dentist_id=s.dentist_id
            WHERE s.clinic_id=? AND s.status='有效' AND s.start_at<? AND s.end_at>? AND s.end_at>?
            ORDER BY s.start_at,s.schedule_id LIMIT 100
            """,Store.parseId(id),end,start,db.now()));
    }

    @GetMapping("/schedules/{id}/affected-appointments")
    Object affected(@RequestHeader(value="Authorization",required=false) String header,
                    @PathVariable String id, @RequestParam(required=false) String endAt) {
        var actor = identity.require(header);
        OrganizationRules.chainOnly(actor);
        long schedule = Store.parseId(id);
        db.one("SELECT schedule_id FROM dentist_schedules WHERE schedule_id=?",schedule);
        return Store.json(affected(schedule, endAt == null ? db.now() : Store.offset(endAt)));
    }

    @PostMapping("/schedules/{id}/change")
    @Transactional
    Object change(@RequestHeader(value="Authorization",required=false) String header,
                  @PathVariable String id, @RequestBody Change request) {
        var actor = identity.require(header);
        OrganizationRules.chainOnly(actor);
        String reason = OrganizationRules.label(request.reason(),500);
        long schedule = Store.parseId(id);
        var parent = db.one("SELECT clinic_id,dentist_id FROM dentist_schedules WHERE schedule_id=?",schedule);
        // Booking and rescheduling take the clinic and dentist locks in the same order.
        db.one("SELECT clinic_id FROM clinics WHERE clinic_id=? FOR UPDATE",parent.get("clinic_id"));
        db.one("SELECT dentist_id FROM dentists WHERE dentist_id=? FOR UPDATE",parent.get("dentist_id"));
        var old = db.one("SELECT * FROM dentist_schedules WHERE schedule_id=? FOR UPDATE",schedule);
        if (!Long.toString(Store.number(old,"row_version")).equals(request.expectedVersion())) {
            throw ApiError.conflict("ROW_VERSION_CONFLICT");
        }
        var start = (Timestamp) old.get("start_at");
        var end = (Timestamp) old.get("end_at");
        var now = db.now();
        var shortenedEnd = request.endAt() == null ? null : Store.offset(request.endAt());
        if (!"有效".equals(old.get("status")) || !end.after(now)
            || (shortenedEnd == null ? !start.after(now)
                : !shortenedEnd.after(start) || !shortenedEnd.after(now) || !shortenedEnd.before(end))) {
            throw new ApiError(409,"INVALID_SCHEDULE_CHANGE","只能撤销尚未开始的排班，或缩短有效排班的剩余时段。");
        }
        var impacted = affected(schedule, shortenedEnd == null ? now : shortenedEnd);
        if (impacted.values().stream().anyMatch(rows -> !rows.isEmpty())) {
            throw new ApiError(409,"SCHEDULE_HAS_APPOINTMENTS","请先在预约管理处理受影响的预约，再提交排班调整。");
        }
        if (shortenedEnd == null) {
            db.jdbc.update("UPDATE dentist_schedules SET status='撤销',row_version=row_version+1 WHERE schedule_id=?",schedule);
        } else {
            db.jdbc.update("UPDATE dentist_schedules SET end_at=?,row_version=row_version+1 WHERE schedule_id=?",shortenedEnd,schedule);
        }
        var updated = db.one("SELECT * FROM dentist_schedules WHERE schedule_id=?",schedule);
        db.audit(actor.id(),shortenedEnd == null ? "撤销出诊" : "缩短出诊","org_schedule",schedule,reason,old,updated);
        return Store.json(updated);
    }

    private Map<String,List<Map<String,Object>>> affected(long id, Timestamp cutoff) {
        var appointments = db.jdbc.queryForList("""
            SELECT appointment_no,scheduled_start,scheduled_end,appointment_status AS status
            FROM appointments WHERE schedule_id=? AND scheduled_end>?
              AND appointment_status IN ('待到诊','待核实','已到诊')
            ORDER BY scheduled_start,appointment_id LIMIT 101
            """,id,cutoff);
        var guests = db.jdbc.queryForList("""
            SELECT reservation_no AS appointment_no,scheduled_start,scheduled_end,reservation_status AS status
            FROM guest_reservations WHERE schedule_id=? AND scheduled_end>?
              AND reservation_status='待到诊'
            ORDER BY scheduled_start,guest_reservation_id LIMIT 101
            """,id,cutoff);
        return Map.of("appointments",appointments,"guests",guests);
    }
}
