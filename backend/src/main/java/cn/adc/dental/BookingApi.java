package cn.adc.dental;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class BookingApi {
    private final Store db;
    private final Identity identity;
    private final BookingService service;
    private final AvailabilityService availability;
    BookingApi(Store db,Identity identity,BookingService service,AvailabilityService availability) { this.db=db; this.identity=identity; this.service=service; this.availability=availability; }
    private Identity.Actor front(String header,String clinic) { var a=identity.require(header); a.front(Store.parseId(clinic)); return a; }
    @GetMapping("/clinics") Object clinics(@RequestHeader(value="Authorization",required=false) String h) {
        var a=identity.require(h);
        if(!a.kind().equals("员工"))throw ApiError.denied();
        return Store.json(db.jdbc.queryForList("SELECT c.clinic_id,c.clinic_code,c.name FROM clinics c WHERE c.active=1"));
    }
    @GetMapping("/dentists") Object dentists(@RequestHeader(value="Authorization",required=false) String h,@RequestParam String clinicId) {
        front(h,clinicId);
        return Store.json(db.jdbc.queryForList("SELECT DISTINCT d.dentist_id,d.name FROM dentists d JOIN dentist_schedules s ON s.dentist_id=d.dentist_id WHERE d.active=1 AND s.clinic_id=? AND s.status='有效'",Store.parseId(clinicId)));
    }
    @GetMapping("/availability") Object availability(@RequestHeader(value="Authorization",required=false) String h,@RequestParam String clinicId) {
        front(h,clinicId);
        return availability.query(Store.parseId(clinicId),null);
    }
    @GetMapping("/staff/patients") Object patients(@RequestHeader(value="Authorization",required=false) String h,@RequestParam String clinicId,@RequestParam String q) {
        var a=front(h,clinicId); Store.required(q,80);
        var result=Store.json(db.jdbc.queryForList("SELECT * FROM patients WHERE patient_no=? OR name=? OR phone=? ORDER BY patient_id LIMIT 50",q,q,q));
        db.audit(a.id(),"读取","patient_search",Store.parseId(clinicId)); return result;
    }
    @PostMapping("/staff/patients") Object createPatient(@RequestHeader(value="Authorization",required=false) String h,@RequestBody BookingService.Patient p) { return service.patient(identity.require(h),p); }
    @PostMapping("/staff/appointments") Object create(@RequestHeader(value="Authorization",required=false) String h,@RequestBody BookingService.Booking b) { return service.create(identity.require(h),b); }
    @GetMapping("/staff/appointments") Object staffAppointments(@RequestHeader(value="Authorization",required=false) String h,@RequestParam String clinicId) {
        front(h,clinicId); return Store.json(db.jdbc.queryForList("SELECT "+BookingService.COLUMNS+" FROM appointments WHERE clinic_id=? ORDER BY scheduled_start DESC LIMIT 100",Store.parseId(clinicId)));
    }
    @GetMapping("/patient/appointments") Object own(@RequestHeader(value="Authorization",required=false) String h) {
        var a=identity.require(h); a.patient();
        db.audit(a.id(),"读取","own_appointments",a.patientId());
        return Store.json(db.jdbc.queryForList("SELECT "+BookingService.COLUMNS+" FROM appointments WHERE patient_id=? ORDER BY scheduled_start DESC LIMIT 100",a.patientId()));
    }
}
