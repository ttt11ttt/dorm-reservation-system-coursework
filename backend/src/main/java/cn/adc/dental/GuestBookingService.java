package cn.adc.dental;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.*;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class GuestBookingService {
    record Request(String reservationNo,String clinicId,String dentistId,String scheduleId,String scheduledStart,String scheduledEnd,String contactName,String contactPhone,String requestedTreatments,String specialNeeds) {
        @JsonAnySetter void unknown(String key,Object value) { throw ApiError.bad(); }
    }
    record Cancel(Integer expectedRowVersion,String reason) { @JsonAnySetter void unknown(String key,Object value) { throw ApiError.bad(); } }
    record Reschedule(Integer expectedRowVersion,Request replacement) {}
    record Convert(Integer expectedRowVersion,String patientId,String evidenceRef,Boolean confirmedInPerson) { @JsonAnySetter void unknown(String key,Object value) { throw ApiError.bad(); } }
    record Review(Boolean valid,Boolean exempt,String reason) { @JsonAnySetter void unknown(String key,Object value) { throw ApiError.bad(); } }
    private final Store db;
    private final BookingRules rules;
    private final GuestOccupancy occupancy;
    private final FulfillmentService fulfillment;
    GuestBookingService(Store db,BookingRules rules,GuestOccupancy occupancy,FulfillmentService fulfillment) { this.db=db;this.rules=rules;this.occupancy=occupancy;this.fulfillment=fulfillment; }
    private static final String COLUMNS="guest_reservation_id,reservation_no,clinic_id,dentist_id,schedule_id,scheduled_start,scheduled_end,contact_name,contact_phone,requested_treatments,special_needs,reservation_status,row_version,converted_appointment_id,created_at";
    private Map<String,Object> row(long id) { return db.one("SELECT "+COLUMNS+",guest_subject_id,cancelled_at,change_reason,conversion_evidence FROM guest_reservations WHERE guest_reservation_id=?",id); }
    private Object result(long id,boolean replay) { return Store.json(Map.of("replayed",replay,"verificationStatus","医疗身份待到店核验","reservation",db.one("SELECT "+COLUMNS+" FROM guest_reservations WHERE guest_reservation_id=?",id))); }
    private String fingerprint(Request b) { return Identity.digest(Store.encode(Arrays.asList(b.reservationNo(),b.clinicId(),b.dentistId(),b.scheduleId(),Store.offset(b.scheduledStart()).toInstant().toString(),Store.offset(b.scheduledEnd()).toInstant().toString(),b.contactName(),b.contactPhone(),b.requestedTreatments(),b.specialNeeds()))); }
    private void validate(Request b) {
        if(b.reservationNo()==null||!b.reservationNo().matches("G-[A-Za-z0-9_-]{22,30}"))throw ApiError.bad();
        Store.required(b.contactName(),80);Store.required(b.contactPhone(),32);Store.required(b.requestedTreatments(),8000);
        if(b.specialNeeds()!=null&&b.specialNeeds().length()>8000)throw ApiError.bad();
    }
    private void allow(long subject) {
        if(db.jdbc.queryForObject("SELECT COUNT(*) FROM guest_restrictions WHERE guest_subject_id=? AND revoked_at IS NULL AND starts_at<=? AND ends_at>?",Integer.class,subject,db.now(),db.now())>0)
            throw ApiError.conflict("BOOKING_RESTRICTED");
    }
    private void availableAppointments(long dentist,Long patient,Timestamp start,Timestamp end) {
        if(db.jdbc.queryForObject("SELECT COUNT(*) FROM appointments WHERE (dentist_id=? OR patient_id=?) AND appointment_status IN ('待到诊','已到诊','待核实') AND scheduled_start<? AND scheduled_end>?",Integer.class,dentist,patient,end,start)>0)throw ApiError.conflict("TIME_CONFLICT");
    }
    @Transactional
    public Object create(long subject,Request b) {
        validate(b);long clinic=Store.parseId(b.clinicId()),dentist=Store.parseId(b.dentistId()),schedule=Store.parseId(b.scheduleId());
        Timestamp start=Store.offset(b.scheduledStart()),end=Store.offset(b.scheduledEnd());if(!end.after(start))throw ApiError.bad();
        var candidate=db.one("SELECT wechat_app_id,wechat_open_id,conversion_verified_patient_id FROM guest_subjects WHERE guest_subject_id=?",subject);
        if(!db.jdbc.queryForList("SELECT account_id FROM accounts WHERE wechat_app_id=? AND wechat_open_id=? FOR SHARE",candidate.get("wechat_app_id"),candidate.get("wechat_open_id")).isEmpty())throw ApiError.denied();
        Long patient=(Long)candidate.get("conversion_verified_patient_id");
        var c=db.one("SELECT active FROM clinics WHERE clinic_id=? FOR UPDATE",clinic);
        var d=db.one("SELECT active FROM dentists WHERE dentist_id=? FOR UPDATE",dentist);
        if(patient!=null) {
            var p=db.one("SELECT active FROM patients WHERE patient_id=? FOR UPDATE",patient);
            if(!Store.enabled(p,"active"))throw ApiError.conflict("RESOURCE_INACTIVE");
        }
        var coverage=rules.lockCoverage(schedule,clinic,dentist);
        var owner=db.one("SELECT active,ever_verified_patient_id,conversion_verified_patient_id FROM guest_subjects WHERE guest_subject_id=? FOR UPDATE",subject);
        if(!GuestPolicy.guestEligible(Store.enabled(owner,"active"),false,owner.get("ever_verified_patient_id")!=null))throw ApiError.denied();
        if(!Objects.equals(patient,owner.get("conversion_verified_patient_id")))throw ApiError.conflict("RETRY_TRANSACTION");
        var prior=db.jdbc.queryForList("SELECT * FROM guest_reservations WHERE reservation_no=? FOR UPDATE",b.reservationNo());
        if(!prior.isEmpty()) {
            var p=prior.getFirst();if(Store.number(p,"guest_subject_id")!=subject||!fingerprint(b).equals(Identity.digest(Store.encode(Arrays.asList(p.get("reservation_no"),p.get("clinic_id").toString(),p.get("dentist_id").toString(),p.get("schedule_id").toString(),((Timestamp)p.get("scheduled_start")).toInstant().toString(),((Timestamp)p.get("scheduled_end")).toInstant().toString(),p.get("contact_name"),p.get("contact_phone"),p.get("requested_treatments"),p.get("special_needs"))))))throw ApiError.conflict("IDEMPOTENCY_CONFLICT");
            return result(Store.number(p,"guest_reservation_id"),true);
        }
        if(start.before(db.now()))throw ApiError.bad();allow(subject);if(patient!=null)fulfillment.allow(patient);
        if(!Store.enabled(c,"active")||!Store.enabled(d,"active"))throw ApiError.conflict("RESOURCE_INACTIVE");
        rules.coverage(coverage,clinic,dentist,start,end);
        availableAppointments(dentist,patient,start,end);occupancy.availableForGuest(dentist,subject,patient,start,end);
        long id=db.id();
        db.jdbc.update("INSERT INTO guest_reservations(guest_reservation_id,reservation_no,guest_subject_id,clinic_id,dentist_id,schedule_id,scheduled_start,scheduled_end,contact_name,contact_phone,requested_treatments,special_needs,reservation_status,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'待到诊',?)",id,b.reservationNo(),subject,clinic,dentist,schedule,start,end,b.contactName(),b.contactPhone(),b.requestedTreatments(),b.specialNeeds(),db.now());
        return result(id,false);
    }
    Object own(long subject) { return Store.json(db.jdbc.queryForList("SELECT "+COLUMNS+" FROM guest_reservations WHERE guest_subject_id=? ORDER BY created_at DESC LIMIT 100",subject)); }
    Object read(long subject,String number) {
        if(number==null||!number.matches("G-[A-Za-z0-9_-]{22,30}"))throw ApiError.bad();
        return Store.json(db.one("SELECT "+COLUMNS+" FROM guest_reservations WHERE guest_subject_id=? AND reservation_no=?",subject,number));
    }
    @Transactional
    public Object reschedule(long subject,long id,Reschedule request) {
        if(request.expectedRowVersion()==null||request.expectedRowVersion()<0||request.replacement()==null)throw ApiError.bad();
        var replacement=request.replacement();
        validate(replacement);
        var candidate=row(id);
        if(Store.number(candidate,"guest_subject_id")!=subject)throw ApiError.denied();
        if(replacement.reservationNo().equals(candidate.get("reservation_no")))throw ApiError.bad();
        var owner=db.one("SELECT wechat_app_id,wechat_open_id FROM guest_subjects WHERE guest_subject_id=?",subject);
        if(!db.jdbc.queryForList("SELECT account_id FROM accounts WHERE wechat_app_id=? AND wechat_open_id=? FOR SHARE",
            owner.get("wechat_app_id"),owner.get("wechat_open_id")).isEmpty())throw ApiError.denied();
        var clinics=new TreeSet<>(List.of(Store.number(candidate,"clinic_id"),Store.parseId(replacement.clinicId())));
        var dentists=new TreeSet<>(List.of(Store.number(candidate,"dentist_id"),Store.parseId(replacement.dentistId())));
        for(long clinic:clinics)db.one("SELECT clinic_id FROM clinics WHERE clinic_id=? FOR UPDATE",clinic);
        for(long dentist:dentists)db.one("SELECT dentist_id FROM dentists WHERE dentist_id=? FOR UPDATE",dentist);
        var original=db.one("SELECT * FROM guest_reservations WHERE guest_reservation_id=? FOR UPDATE",id);
        if(Store.number(original,"guest_subject_id")!=subject)throw ApiError.denied();
        String reference="改约至 "+replacement.reservationNo();
        long version=Store.number(original,"row_version");
        if("已改约".equals(original.get("reservation_status"))&&version==request.expectedRowVersion()+1L
            &&reference.equals(original.get("change_reason")))return create(subject,replacement);
        if(!"待到诊".equals(original.get("reservation_status"))||version!=request.expectedRowVersion())throw ApiError.conflict("ROW_VERSION_CONFLICT");
        var now=db.now();
        var oldStart=(Timestamp)original.get("scheduled_start");
        if(!oldStart.after(now))throw ApiError.conflict("INVALID_STATE");
        if(oldStart.equals(Store.offset(replacement.scheduledStart()))&&Store.number(original,"schedule_id")==Store.parseId(replacement.scheduleId())){
            throw new ApiError(409,"SAME_APPOINTMENT_SLOT","请选择其他预约时间。");
        }
        if(!db.jdbc.queryForList("SELECT guest_reservation_id FROM guest_reservations WHERE reservation_no=? FOR UPDATE",replacement.reservationNo()).isEmpty()){
            throw ApiError.conflict("IDEMPOTENCY_CONFLICT");
        }
        db.jdbc.update("UPDATE guest_reservations SET reservation_status='已改约',change_reason=?,row_version=row_version+1 WHERE guest_reservation_id=?",reference,id);
        // Same transaction: a conflict on the replacement rolls back the old status.
        var result=create(subject,replacement);
        if(GuestPolicy.late(now,oldStart)){event(id,subject,"late_reschedule",now,null,reference);trigger(subject);}
        return result;
    }
    @Transactional
    public Object cancel(long subject,long id,Cancel decision) {
        if(decision.expectedRowVersion()==null||decision.expectedRowVersion()<0)throw ApiError.bad();Store.required(decision.reason(),8000);
        var candidate=row(id);if(Store.number(candidate,"guest_subject_id")!=subject)throw ApiError.denied();
        long clinic=Store.number(candidate,"clinic_id"),dentist=Store.number(candidate,"dentist_id");
        db.one("SELECT clinic_id FROM clinics WHERE clinic_id=? FOR UPDATE",clinic);db.one("SELECT dentist_id FROM dentists WHERE dentist_id=? FOR UPDATE",dentist);
        db.one("SELECT guest_subject_id FROM guest_subjects WHERE guest_subject_id=? FOR UPDATE",subject);
        var r=db.one("SELECT * FROM guest_reservations WHERE guest_reservation_id=? FOR UPDATE",id);
        if(Store.number(r,"guest_subject_id")!=subject)throw ApiError.denied();
        if("患者取消".equals(r.get("reservation_status"))) {
            if(Store.number(r,"row_version")!=decision.expectedRowVersion()+1||!decision.reason().equals(r.get("change_reason")))throw ApiError.conflict("IDEMPOTENCY_CONFLICT");
            return result(id,true);
        }
        if(!"待到诊".equals(r.get("reservation_status")))throw ApiError.conflict("MANUAL_REVIEW_REQUIRED");
        if(Store.number(r,"row_version")!=decision.expectedRowVersion())throw ApiError.conflict("ROW_VERSION_CONFLICT");
        Timestamp now=db.now();
        db.jdbc.update("UPDATE guest_reservations SET reservation_status='患者取消',cancelled_at=?,change_reason=?,row_version=row_version+1 WHERE guest_reservation_id=?",now,decision.reason(),id);
        if(GuestPolicy.late(now,(Timestamp)r.get("scheduled_start"))) { event(id,subject,"late_cancel",now,null,decision.reason());trigger(subject); }
        return result(id,false);
    }
    private void event(long reservation,long subject,String kind,Timestamp when,Long staff,String reason) {
        db.jdbc.update("INSERT INTO guest_breaches(guest_breach_id,guest_reservation_id,guest_subject_id,breach_kind,occurred_at,valid,exempt,responsibility_reason,updated_by,updated_at) VALUES(?,?,?,?,?,1,0,?,?,?)",db.id(),reservation,subject,kind,when,reason,staff,db.now());
    }
    private void trigger(long subject) {
        if(db.jdbc.queryForObject("SELECT COUNT(*) FROM guest_restrictions WHERE guest_subject_id=? AND revoked_at IS NULL AND ends_at>?",Integer.class,subject,db.now())>0)return;
        var events=db.jdbc.queryForList("SELECT guest_breach_id FROM guest_breaches WHERE guest_subject_id=? AND valid=1 AND exempt=0 AND restriction_id IS NULL AND occurred_at>? AND occurred_at<=? ORDER BY occurred_at,guest_breach_id LIMIT 2 FOR UPDATE",subject,Timestamp.from(db.clock.instant().minus(Duration.ofDays(30))),db.now());
        if(events.size()<2)return;
        long restriction=db.id();db.jdbc.update("INSERT INTO guest_restrictions(guest_restriction_id,guest_subject_id,starts_at,ends_at,created_at) VALUES(?,?,?,?,?)",restriction,subject,db.now(),Timestamp.from(db.clock.instant().plus(Duration.ofDays(14))),db.now());
        for(var e:events)db.jdbc.update("UPDATE guest_breaches SET restriction_id=? WHERE guest_breach_id=?",restriction,e.get("guest_breach_id"));
    }
    Object staff(Identity.Actor actor,long clinic) { actor.front(clinic);var rows=Store.json(db.jdbc.queryForList("SELECT "+COLUMNS+" FROM guest_reservations WHERE clinic_id=? ORDER BY scheduled_start DESC LIMIT 100",clinic));db.audit(actor.id(),"读取","guest_reservations",clinic);return rows; }
    Object status(long subject) {
        var rows=db.jdbc.queryForList("SELECT ends_at FROM guest_restrictions WHERE guest_subject_id=? AND revoked_at IS NULL AND starts_at<=? AND ends_at>? ORDER BY ends_at DESC LIMIT 1",subject,db.now(),db.now());
        var owner=db.one("SELECT conversion_verified_patient_id FROM guest_subjects WHERE guest_subject_id=?",subject);
        Long patient=(Long)owner.get("conversion_verified_patient_id");
        var patientRows=patient==null?List.<Map<String,Object>>of():db.jdbc.queryForList("SELECT ends_at FROM booking_restrictions WHERE patient_id=? AND status='active' AND starts_at<=? AND ends_at>? ORDER BY ends_at DESC LIMIT 1",patient,db.now(),db.now());
        Object until=rows.isEmpty()?"":rows.getFirst().get("ends_at");
        if(!patientRows.isEmpty()&&(rows.isEmpty()||((Timestamp)patientRows.getFirst().get("ends_at")).after((Timestamp)until)))until=patientRows.getFirst().get("ends_at");
        return Store.json(Map.of("restricted",!rows.isEmpty()||!patientRows.isEmpty(),"restrictedUntil",until));
    }
    @Transactional
    public Object clinicCancel(Identity.Actor actor,long id,Cancel decision) {
        if(decision.expectedRowVersion()==null||decision.expectedRowVersion()<0)throw ApiError.bad();
        String reason=OrganizationRules.label(decision.reason(),500);
        var candidate=row(id);
        actor.front(Store.number(candidate,"clinic_id"));
        db.one("SELECT clinic_id FROM clinics WHERE clinic_id=? FOR UPDATE",candidate.get("clinic_id"));
        db.one("SELECT dentist_id FROM dentists WHERE dentist_id=? FOR UPDATE",candidate.get("dentist_id"));
        db.one("SELECT guest_subject_id FROM guest_subjects WHERE guest_subject_id=? FOR UPDATE",candidate.get("guest_subject_id"));
        var current=db.one("SELECT * FROM guest_reservations WHERE guest_reservation_id=? FOR UPDATE",id);
        long version=Store.number(current,"row_version");
        if("诊所撤销".equals(current.get("reservation_status")) && version==decision.expectedRowVersion()+1L
            && reason.equals(current.get("change_reason")))return result(id,true);
        if(!"待到诊".equals(current.get("reservation_status"))||version!=decision.expectedRowVersion()) {
            throw ApiError.conflict("ROW_VERSION_CONFLICT");
        }
        db.jdbc.update("UPDATE guest_reservations SET reservation_status='诊所撤销',cancelled_at=?,change_reason=?,row_version=row_version+1 WHERE guest_reservation_id=?",db.now(),reason,id);
        db.audit(actor.id(),"诊所撤销访客预约","guest_reservation",id,reason,
            Map.of("status","待到诊","rowVersion",version),Map.of("status","诊所撤销","rowVersion",version+1));
        return result(id,false);
    }

    @Transactional
    public Object noShow(Identity.Actor actor,long id,Cancel decision) {
        if(decision.expectedRowVersion()==null||decision.expectedRowVersion()<0)throw ApiError.bad();Store.required(decision.reason(),8000);
        var candidate=row(id);actor.front(Store.number(candidate,"clinic_id"));
        db.one("SELECT clinic_id FROM clinics WHERE clinic_id=? FOR UPDATE",candidate.get("clinic_id"));db.one("SELECT dentist_id FROM dentists WHERE dentist_id=? FOR UPDATE",candidate.get("dentist_id"));
        long subject=Store.number(candidate,"guest_subject_id");db.one("SELECT guest_subject_id FROM guest_subjects WHERE guest_subject_id=? FOR UPDATE",subject);
        var r=db.one("SELECT * FROM guest_reservations WHERE guest_reservation_id=? FOR UPDATE",id);
        if(!"待到诊".equals(r.get("reservation_status"))||Store.number(r,"row_version")!=decision.expectedRowVersion())throw ApiError.conflict("ROW_VERSION_CONFLICT");
        if(!db.now().after((Timestamp)r.get("scheduled_end")))throw ApiError.conflict("APPOINTMENT_NOT_ENDED");
        db.jdbc.update("UPDATE guest_reservations SET reservation_status='未到诊',change_reason=?,row_version=row_version+1 WHERE guest_reservation_id=?",decision.reason(),id);
        event(id,subject,"no_show",(Timestamp)r.get("scheduled_end"),actor.id(),decision.reason());trigger(subject);
        db.audit(actor.id(),"访客未到诊核实","guest_reservation",id,decision.reason(),r,row(id));return result(id,false);
    }
    @Transactional
    public Object convert(Identity.Actor actor,long id,Convert request) {
        if(!Boolean.TRUE.equals(request.confirmedInPerson())||request.expectedRowVersion()==null||request.expectedRowVersion()<0||request.evidenceRef()==null||!request.evidenceRef().matches("[A-Za-z0-9_.:/-]{1,80}"))throw ApiError.bad();
        long patient=Store.parseId(request.patientId());var candidate=row(id);long clinic=Store.number(candidate,"clinic_id"),dentist=Store.number(candidate,"dentist_id");actor.front(clinic);
        var clinicRow=db.one("SELECT active FROM clinics WHERE clinic_id=? FOR UPDATE",clinic);var dentistRow=db.one("SELECT active FROM dentists WHERE dentist_id=? FOR UPDATE",dentist);
        var p=db.one("SELECT active FROM patients WHERE patient_id=? FOR UPDATE",patient);
        var coverage=rules.lockCoverage(Store.number(candidate,"schedule_id"),clinic,dentist);
        var owner=db.one("SELECT guest_subject_id,ever_verified_patient_id,conversion_verified_patient_id FROM guest_subjects WHERE guest_subject_id=? FOR UPDATE",candidate.get("guest_subject_id"));
        if(owner.get("ever_verified_patient_id")!=null&&Store.number(owner,"ever_verified_patient_id")!=patient)throw ApiError.conflict("VERIFIED_IDENTITY_CONFLICT");
        if(owner.get("conversion_verified_patient_id")!=null&&Store.number(owner,"conversion_verified_patient_id")!=patient)throw ApiError.conflict("VERIFIED_IDENTITY_CONFLICT");
        var r=db.one("SELECT * FROM guest_reservations WHERE guest_reservation_id=? FOR UPDATE",id);
        if(Store.number(r,"clinic_id")!=clinic||Store.number(r,"dentist_id")!=dentist||Store.number(r,"schedule_id")!=Store.number(candidate,"schedule_id"))throw ApiError.conflict("RETRY_TRANSACTION");
        if("已转换".equals(r.get("reservation_status"))) {
            if(Store.number(r,"row_version")!=request.expectedRowVersion()+1||!request.evidenceRef().equals(r.get("conversion_evidence"))||db.jdbc.queryForObject("SELECT COUNT(*) FROM appointments WHERE appointment_id=? AND patient_id=?",Integer.class,r.get("converted_appointment_id"),patient)!=1)throw ApiError.conflict("IDEMPOTENCY_CONFLICT");
            return result(id,true);
        }
        if(!"待到诊".equals(r.get("reservation_status"))||Store.number(r,"row_version")!=request.expectedRowVersion())throw ApiError.conflict("ROW_VERSION_CONFLICT");
        if(!Store.enabled(clinicRow,"active")||!Store.enabled(dentistRow,"active")||!Store.enabled(p,"active"))throw ApiError.conflict("RESOURCE_INACTIVE");
        rules.coverage(coverage,clinic,dentist,(Timestamp)r.get("scheduled_start"),(Timestamp)r.get("scheduled_end"));
        availableAppointments(dentist,patient,(Timestamp)r.get("scheduled_start"),(Timestamp)r.get("scheduled_end"));
        occupancy.availableForConversion(dentist,patient,id,(Timestamp)r.get("scheduled_start"),(Timestamp)r.get("scheduled_end"));
        if(db.jdbc.queryForObject("SELECT COUNT(*) FROM appointments WHERE appointment_no=?",Integer.class,r.get("reservation_no"))!=0)throw ApiError.conflict("IDEMPOTENCY_CONFLICT");
        long appointment=db.id();
        db.jdbc.update("INSERT INTO appointments(appointment_id,appointment_no,patient_id,booking_channel,dentist_id,clinic_id,schedule_id,scheduled_start,scheduled_end,requested_treatments,special_needs,appointment_status,treatment_status,created_by,created_at) VALUES(?,?,?,'柜台',?,?,?,?,?,?,?,'待到诊','未开始',?,?)",appointment,r.get("reservation_no"),patient,dentist,clinic,r.get("schedule_id"),r.get("scheduled_start"),r.get("scheduled_end"),r.get("requested_treatments"),r.get("special_needs"),actor.id(),db.now());
        db.jdbc.update("UPDATE guest_reservations SET reservation_status='已转换',converted_appointment_id=?,conversion_evidence=?,row_version=row_version+1 WHERE guest_reservation_id=?",appointment,request.evidenceRef(),id);
        db.jdbc.update("UPDATE guest_subjects SET conversion_verified_patient_id=COALESCE(conversion_verified_patient_id,?) WHERE guest_subject_id=?",patient,r.get("guest_subject_id"));
        db.audit(actor.id(),"访客到店核验转换","guest_reservation",id,request.evidenceRef(),r,row(id));
        return Store.json(Map.of("replayed",false,"appointmentId",Long.toString(appointment),"reservation",row(id)));
    }
    @Transactional
    public Object review(Identity.Actor actor,long breach,Review request) {
        if(request.valid()==null||request.exempt()==null)throw ApiError.bad();Store.required(request.reason(),8000);
        var candidate=db.one("SELECT b.guest_subject_id,r.clinic_id FROM guest_breaches b JOIN guest_reservations r ON r.guest_reservation_id=b.guest_reservation_id WHERE b.guest_breach_id=?",breach);actor.front(Store.number(candidate,"clinic_id"));
        long subject=Store.number(candidate,"guest_subject_id");db.one("SELECT guest_subject_id FROM guest_subjects WHERE guest_subject_id=? FOR UPDATE",subject);
        var event=db.one("SELECT * FROM guest_breaches WHERE guest_breach_id=? FOR UPDATE",breach);
        db.jdbc.update("UPDATE guest_breaches SET valid=?,exempt=?,responsibility_reason=?,updated_by=?,updated_at=? WHERE guest_breach_id=?",request.valid(),request.exempt(),request.reason(),actor.id(),db.now(),breach);
        if(event.get("restriction_id")!=null) {
            var restriction=db.one("SELECT * FROM guest_restrictions WHERE guest_restriction_id=? FOR UPDATE",event.get("restriction_id"));
            int valid=db.jdbc.queryForObject("SELECT COUNT(*) FROM guest_breaches WHERE restriction_id=? AND valid=1 AND exempt=0",Integer.class,event.get("restriction_id"));
            if(valid<2&&restriction.get("revoked_at")==null)db.jdbc.update("UPDATE guest_restrictions SET revoked_at=?,reviewed_by=?,review_reason=? WHERE guest_restriction_id=?",db.now(),actor.id(),request.reason(),event.get("restriction_id"));
        }
        trigger(subject);db.audit(actor.id(),"访客违约复核","guest_breach",breach,request.reason(),event,db.one("SELECT * FROM guest_breaches WHERE guest_breach_id=?",breach));
        return Store.json(db.one("SELECT * FROM guest_breaches WHERE guest_breach_id=?",breach));
    }
}
