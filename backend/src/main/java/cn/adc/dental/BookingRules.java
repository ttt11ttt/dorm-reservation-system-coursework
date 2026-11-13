package cn.adc.dental;

import java.sql.Timestamp;
import java.util.*;
import org.springframework.stereotype.Component;

/** Shared validation/coverage for front desk and self-service. Call only after the resource parent locks. */
@Component
class BookingRules {
    final Store db;
    BookingRules(Store db) { this.db=db; }
    void validate(BookingService.Booking b,boolean self) {
        if(b.appointmentNo()==null||!b.appointmentNo().matches("[A-Za-z0-9-]{1,32}")||b.appointmentNo().startsWith("G-"))throw ApiError.bad();
        if(!(self?Set.of("自助"):Set.of("电话","柜台")).contains(Objects.toString(b.bookingChannel(),"")))throw ApiError.bad();
        Store.required(b.requestedTreatments(),8000);if(b.specialNeeds()!=null&&b.specialNeeds().length()>8000)throw ApiError.bad();
        if(!Store.offset(b.scheduledEnd()).after(Store.offset(b.scheduledStart())))throw ApiError.bad();
    }
    record Coverage(Map<String,Object> schedule,Map<String,Object> opening) {}
    Coverage lockCoverage(long schedule,long clinic,long dentist) {
        var candidate=db.one("SELECT opening_id,clinic_id,dentist_id FROM dentist_schedules WHERE schedule_id=?",schedule);
        if(Store.number(candidate,"clinic_id")!=clinic||Store.number(candidate,"dentist_id")!=dentist)throw ApiError.conflict("OUTSIDE_COVERAGE");
        var opening=db.one("SELECT * FROM clinic_openings WHERE opening_id=? FOR UPDATE",candidate.get("opening_id"));
        var s=db.one("SELECT * FROM dentist_schedules WHERE schedule_id=? FOR UPDATE",schedule);
        if(!Objects.equals(candidate.get("opening_id"),s.get("opening_id")))throw ApiError.conflict("RETRY_TRANSACTION");
        return new Coverage(s,opening);
    }
    void coverage(Coverage c,long clinic,long dentist,Timestamp start,Timestamp end) { if(Store.number(c.schedule(),"clinic_id")!=clinic||Store.number(c.schedule(),"dentist_id")!=dentist||Store.number(c.opening(),"clinic_id")!=clinic||!covered(c.schedule(),start,end)||!covered(c.opening(),start,end))throw ApiError.conflict("OUTSIDE_COVERAGE"); }
    private boolean covered(Map<String,Object> row,Timestamp start,Timestamp end) { return "有效".equals(row.get("status"))&&!((Timestamp)row.get("start_at")).after(start)&&!((Timestamp)row.get("end_at")).before(end); }
    void available(List<Map<String,Object>> rows,long dentist,long patient,Timestamp start,Timestamp end,long excluded) {
        for(var a:rows)if(Store.number(a,"appointment_id")!=excluded&&(Store.number(a,"dentist_id")==dentist||Store.number(a,"patient_id")==patient)&&Set.of("待到诊","已到诊","待核实").contains(a.get("appointment_status"))&&((Timestamp)a.get("scheduled_start")).before(end)&&((Timestamp)a.get("scheduled_end")).after(start))throw ApiError.conflict("TIME_CONFLICT");
    }
}
