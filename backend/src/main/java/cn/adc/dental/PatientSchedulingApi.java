package cn.adc.dental;

import java.util.Map;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import org.springframework.web.bind.annotation.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/api/v1")
class PatientSchedulingApi {
    record SelfBooking(String appointmentNo,String clinicId,String dentistId,String scheduleId,String scheduledStart,String scheduledEnd,String requestedTreatments,String specialNeeds) {
        @JsonAnySetter void unknown(String key,Object value) { throw ApiError.bad(); }
    }
    private final Identity identity;
    private final Store db;
    private final BookingService bookings;
    private final AppointmentChanges changes;
    private final PatientInformation info;
    private final AvailabilityService availability;
    PatientSchedulingApi(Identity identity,Store db,BookingService bookings,AppointmentChanges changes,PatientInformation info,AvailabilityService availability) { this.identity=identity;this.db=db;this.bookings=bookings;this.changes=changes;this.info=info;this.availability=availability; }
    private Identity.Actor patient(String h) { var a=identity.require(h);a.patient();return a; }
    @GetMapping("/patient/profile") Object profile(@RequestHeader(value="Authorization",required=false) String h) { return info.read(patient(h),true); }
    @PutMapping("/patient/profile/contact") Object contact(@RequestHeader(value="Authorization",required=false) String h,@RequestBody PatientInformation.Contact request) { return info.contact(patient(h),request); }
    @PutMapping("/patient/profile/allergies") Object allergies(@RequestHeader(value="Authorization",required=false) String h,@RequestBody PatientInformation.AllergyDeclaration request) { return info.allergies(patient(h),request); }
    @GetMapping("/patient/booking-status") Object status(@RequestHeader(value="Authorization",required=false) String h) { return info.read(patient(h),false); }
    @GetMapping("/patient/clinics") Object clinics(@RequestHeader(value="Authorization",required=false) String h) { patient(h);return Store.json(db.jdbc.queryForList("SELECT clinic_id,clinic_code,name,postal_address,phone FROM clinics WHERE active=1 ORDER BY clinic_id")); }
    @GetMapping("/patient/dentists") Object dentists(@RequestHeader(value="Authorization",required=false) String h,@RequestParam String clinicId) { patient(h);return Store.json(db.jdbc.queryForList("SELECT DISTINCT d.dentist_id,d.name FROM dentists d JOIN dentist_schedules s ON s.dentist_id=d.dentist_id JOIN clinics c ON c.clinic_id=s.clinic_id JOIN clinic_openings o ON o.opening_id=s.opening_id AND o.clinic_id=s.clinic_id WHERE s.clinic_id=? AND d.active=1 AND c.active=1 AND s.status='有效' AND o.status='有效' AND o.start_at<=s.start_at AND o.end_at>=s.end_at AND s.end_at>? ORDER BY d.dentist_id",Store.parseId(clinicId),db.now())); }
    @GetMapping("/patient/availability")
    Object availability(@RequestHeader(value="Authorization",required=false) String h,@RequestParam String clinicId,
                        @RequestParam(required=false) String rescheduleId) {
        long owner=patient(h).patientId();
        long excluded=rescheduleId==null?0L:Store.parseId(rescheduleId);
        if(excluded!=0){
            var row=db.one("SELECT patient_id,appointment_status FROM appointments WHERE appointment_id=?",excluded);
            if(Store.number(row,"patient_id")!=owner)throw ApiError.denied();
            if(!java.util.Set.of("待到诊","待核实").contains(row.get("appointment_status")))throw ApiError.conflict("INVALID_STATE");
        }
        return availability.query(Store.parseId(clinicId),owner,excluded);
    }
    @GetMapping("/patient/treatment-types") Object prices(@RequestHeader(value="Authorization",required=false) String h) { patient(h);return Store.json(db.jdbc.queryForList("SELECT treatment_type_id,treatment_code,name,unit_name,standard_unit_price,allows_fraction FROM treatment_types WHERE active=1 ORDER BY treatment_type_id")); }
    @PostMapping("/patient/appointments") Object book(@RequestHeader(value="Authorization",required=false) String h,@RequestBody SelfBooking b) { var a=patient(h);return bookings.createOwn(a,new BookingService.Booking(b.appointmentNo(),Long.toString(a.patientId()),b.clinicId(),b.dentistId(),b.scheduleId(),b.scheduledStart(),b.scheduledEnd(),"自助",b.requestedTreatments(),b.specialNeeds())); }
    @PostMapping("/patient/appointments/{id}/cancel") Object cancel(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id,@RequestBody AppointmentChanges.Decision b) { return changes.cancel(patient(h),Store.parseId(id),b,true,false); }
    @PostMapping("/patient/appointments/{id}/reschedule") Object reschedule(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id,@RequestBody AppointmentChanges.Adjustment b) { return changes.reschedule(patient(h),Store.parseId(id),b,true); }
    @PostMapping("/staff/appointments/{id}/cancel") Object staffCancel(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id,@RequestBody AppointmentChanges.Decision b) { return changes.cancel(identity.require(h),Store.parseId(id),b,false,false); }
    @PostMapping("/staff/appointments/{id}/reschedule") Object staffReschedule(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id,@RequestBody AppointmentChanges.Adjustment b) { return changes.reschedule(identity.require(h),Store.parseId(id),b,false); }
    @PostMapping("/staff/appointments/{id}/no-show") Object noShow(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id,@RequestBody AppointmentChanges.Decision b) { return changes.cancel(identity.require(h),Store.parseId(id),b,false,true); }
    @PostMapping("/staff/appointments/{id}/result-correction") Object correct(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id,@RequestBody AppointmentChanges.ResultCorrection b) { return changes.correctResult(identity.require(h),Store.parseId(id),b); }
    @GetMapping("/staff/appointments/{id}/fulfillment") Object fulfillment(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id) { return changes.read(identity.require(h),Store.parseId(id)); }
    @PostMapping("/staff/breaches/{id}/review") Object review(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id,@RequestBody AppointmentChanges.Review b) { return changes.review(identity.require(h),Store.parseId(id),b); }
}

@Service
class PatientInformation {
    private final Store db;
    private final FulfillmentService fulfillment;
    PatientInformation(Store db,FulfillmentService fulfillment) { this.db=db;this.fulfillment=fulfillment; }
    record Contact(Integer expectedVersion,String phone,String email,String billingAddress,String emergencyName,String emergencyPhone) {}
    record AllergyDeclaration(Integer expectedVersion,String declaration,List<EncounterService.AllergyItem> items) {}

    private Map<String,Object> patientRow(long id,boolean lock){
        return db.one("SELECT * FROM patients WHERE patient_id=?"+(lock?" FOR UPDATE":""),id);
    }
    private List<Map<String,Object>> activeAllergies(long id){
        return db.jdbc.queryForList("""
            SELECT substance,reaction_note,verification_status FROM patient_allergies
            WHERE patient_id=? AND active=1 ORDER BY substance,allergy_id
            """,id);
    }
    private void version(Map<String,Object> patient,Integer expected){
        if(expected==null||expected<0)throw ApiError.bad();
        if(!Store.enabled(patient,"active"))throw ApiError.denied();
        if(Store.number(patient,"row_version")!=expected)throw ApiError.conflict("ROW_VERSION_CONFLICT");
    }
    @Transactional
    public Object contact(Identity.Actor actor,Contact request){
        actor.patient();
        long id=actor.patientId();
        var before=patientRow(id,true);
        version(before,request.expectedVersion());
        String phone=OrganizationRules.label(request.phone(),32);
        String email=request.email()==null||request.email().isBlank()?null:OrganizationRules.label(request.email(),254);
        String address=OrganizationRules.label(request.billingAddress(),255);
        String emergency=OrganizationRules.label(request.emergencyName(),80);
        String emergencyPhone=OrganizationRules.label(request.emergencyPhone(),32);
        db.jdbc.update("""
            UPDATE patients SET phone=?,email=?,billing_address=?,emergency_name=?,emergency_phone=?,row_version=row_version+1
            WHERE patient_id=?
            """,phone,email,address,emergency,emergencyPhone,id);
        var after=patientRow(id,false);
        db.audit(actor.id(),"本人修改联系资料","patient",id,null,before,after);
        return Store.json(after);
    }
    @Transactional
    public Object allergies(Identity.Actor actor,AllergyDeclaration request){
        actor.patient();
        var items=EncounterService.normalized(request.items(),request.declaration());
        long id=actor.patientId();
        var before=patientRow(id,true);
        version(before,request.expectedVersion());
        var previous=activeAllergies(id);
        var current=previous.stream().map(row->java.util.Arrays.asList(row.get("substance"),row.get("reaction_note"))).toList();
        var submitted=items.stream().map(row->java.util.Arrays.asList(row.get("substance"),row.get("reaction_note"))).toList();
        if(!request.declaration().equals(before.get("allergy_declaration"))||!new java.util.HashSet<>(current).equals(new java.util.HashSet<>(submitted))){
            db.jdbc.update("UPDATE patient_allergies SET active=0,updated_at=? WHERE patient_id=? AND active=1",db.now(),id);
            for(var item:items){
                db.jdbc.update("""
                    INSERT INTO patient_allergies(allergy_id,patient_id,substance,reaction_note,active,verification_status,updated_at)
                    VALUES(?,?,?,?,1,'患者申报',?)
                    """,db.id(),id,item.get("substance"),item.get("reaction_note"),db.now());
            }
            db.jdbc.update("UPDATE patients SET allergy_declaration=?,allergy_version=allergy_version+1,row_version=row_version+1 WHERE patient_id=?",request.declaration(),id);
            db.audit(actor.id(),"本人过敏申报","patient",id,null,
                Map.of("declaration",before.get("allergy_declaration"),"items",previous),
                Map.of("declaration",request.declaration(),"items",items));
        }
        return Store.json(Map.of("patient",patientRow(id,false),"allergies",activeAllergies(id)));
    }
    @Transactional
    public Object read(Identity.Actor actor,boolean profile) {
        actor.patient();long patient=actor.patientId();var status=fulfillment.status(patient);db.audit(actor.id(),"读取",profile?"own_profile":"own_booking_status",patient);
        return Store.json(profile?Map.of("patient",patientRow(patient,false),"bookingStatus",status,"allergies",activeAllergies(patient)):status);
    }
}
