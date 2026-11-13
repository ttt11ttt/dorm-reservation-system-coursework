package cn.adc.dental;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class BookingService {
    static final String COLUMNS="appointment_id,appointment_no,patient_id,clinic_id,dentist_id,schedule_id,scheduled_start,scheduled_end,booking_channel,requested_treatments,special_needs,appointment_status,treatment_status,row_version";
    record Booking(String appointmentNo,String patientId,String clinicId,String dentistId,String scheduleId,String scheduledStart,String scheduledEnd,String bookingChannel,String requestedTreatments,String specialNeeds) {}
    record Patient(String clinicId,String patientNo,String name,String birthDate,String billingAddress,String phone,String email,String emergencyName,String emergencyPhone) {}
    private final Store db;
    private final BookingRules rules;
    private final GuestOccupancy guestOccupancy;
    private final FulfillmentService fulfillment;
    private final FollowupHooks hooks;
    private final ReminderLifecycle lifecycle;
    private final ReminderFollowupContext reminderContexts;
    private final ReminderLinkedMaintenance linkedReminders;
    BookingService(Store db,BookingRules rules,GuestOccupancy guestOccupancy,FulfillmentService fulfillment,FollowupHooks hooks,ReminderLifecycle lifecycle,ReminderFollowupContext reminderContexts,ReminderLinkedMaintenance linkedReminders) { this.db=db;this.rules=rules;this.guestOccupancy=guestOccupancy;this.fulfillment=fulfillment;this.hooks=hooks;this.lifecycle=lifecycle;this.reminderContexts=reminderContexts;this.linkedReminders=linkedReminders; }
    private Timestamp time(String value) {
        try { var t=OffsetDateTime.parse(value).toInstant();
            if(t.getNano()!=0 || t.getEpochSecond()<1 || t.getEpochSecond()>2147483647L)throw ApiError.bad(); return Timestamp.from(t);
        } catch(Exception e) { throw ApiError.bad(); }
    }
    @Transactional
    public Object create(Identity.Actor actor,Booking b) {
        return createFor(actor,b,false);
    }
    @Transactional
    public Object createOwn(Identity.Actor actor,Booking b) {
        actor.patient();if(Store.parseId(b.patientId())!=actor.patientId())throw ApiError.denied();return createFor(actor,b,true);
    }
    private Object createFor(Identity.Actor actor,Booking b,boolean self) {
        return createFor(actor,b,self,null);
    }
    @Transactional
    public Object createLinked(Identity.Actor actor,long planId,Booking b,boolean self) { return createFor(actor,b,self,planId); }
    private Object createFor(Identity.Actor actor,Booking b,boolean self,Long planId) {
        var planCandidate=planId==null?null:hooks.candidate(planId);
        long clinic=Store.parseId(b.clinicId),dentist=Store.parseId(b.dentistId),patient=planCandidate==null?Store.parseId(b.patientId):planCandidate.owner().patient(),schedule=Store.parseId(b.scheduleId);
        if(planCandidate!=null)FollowupLinkRules.authorize(actor,planCandidate,clinic,self);
        var reminderClosure=planId==null?null:reminderContexts.candidate(planId);
        if(self){actor.patient();if(actor.patientId()!=patient)throw ApiError.denied();}
        if(!self)actor.front(clinic);rules.validate(b,self);
        Timestamp start=time(b.scheduledStart),end=time(b.scheduledEnd); if(!end.after(start))throw ApiError.bad();
        var clinics=new ArrayList<>(planCandidate==null?List.of(clinic):List.of(clinic,planCandidate.owner().clinic()));var dentists=new ArrayList<>(planCandidate==null?List.of(dentist):List.of(dentist,planCandidate.owner().dentist()));if(reminderClosure!=null){clinics.addAll(reminderClosure.clinics());dentists.addAll(reminderClosure.dentists());}var resources=FollowupLinkRules.resources(clinics,dentists);
        var clinicActive=new HashMap<Long,Boolean>();var dentistActive=new HashMap<Long,Boolean>();
        for(long clinicId:resources.clinics()){var row=db.one("SELECT active FROM clinics WHERE clinic_id=? FOR UPDATE",clinicId);clinicActive.put(clinicId,Store.enabled(row,"active"));}
        for(long dentistId:resources.dentists()){var row=db.one("SELECT active FROM dentists WHERE dentist_id=? FOR UPDATE",dentistId);dentistActive.put(dentistId,Store.enabled(row,"active"));}
        var p=db.one("SELECT active,phone FROM patients WHERE patient_id=? FOR UPDATE",patient);
        boolean enabled=Boolean.TRUE.equals(clinicActive.get(clinic))&&Boolean.TRUE.equals(dentistActive.get(dentist));
        boolean planActive=planCandidate!=null&&Boolean.TRUE.equals(clinicActive.get(planCandidate.owner().clinic()))&&Boolean.TRUE.equals(dentistActive.get(planCandidate.owner().dentist()))&&Store.enabled(p,"active");
        var coverage=rules.lockCoverage(schedule,clinic,dentist);
        var followupPlan=planCandidate==null?null:hooks.lock(planCandidate);
        var appointmentArgs=new ArrayList<Object>(List.of(b.appointmentNo,planCandidate==null?-1L:planCandidate.owner().source(),dentist,patient,end,start));String extra=reminderClosure==null?"":reminderContexts.include(reminderClosure,appointmentArgs);var locked=db.jdbc.queryForList("SELECT * FROM appointments WHERE appointment_no=? OR appointment_id=? OR ((dentist_id=? OR patient_id=?) AND appointment_status IN ('待到诊','已到诊','待核实') AND scheduled_start<? AND scheduled_end>?)"+extra+" ORDER BY appointment_id FOR UPDATE",appointmentArgs.toArray());if(reminderClosure!=null)reminderContexts.verify(reminderClosure,followupPlan,locked);
        var prior=locked.stream().filter(a->b.appointmentNo.equals(a.get("appointment_no"))).toList();
        if(!prior.isEmpty()) {
            var a=prior.getFirst();
            var requested=new FollowupLinkRules.BookingIdentity(planId,null,actor.id(),patient,clinic,dentist,schedule,start.toInstant(),end.toInstant(),b.bookingChannel,b.requestedTreatments,b.specialNeeds);
            var stored=new FollowupLinkRules.BookingIdentity(hooks.originalPlan(number(a,"appointment_id")),(Long)a.get("previous_appointment_id"),number(a,"created_by"),number(a,"patient_id"),number(a,"clinic_id"),number(a,"dentist_id"),number(a,"schedule_id"),((Timestamp)a.get("scheduled_start")).toInstant(),((Timestamp)a.get("scheduled_end")).toInstant(),(String)a.get("booking_channel"),(String)a.get("requested_treatments"),(String)a.get("special_needs"));
            if(!FollowupLinkRules.sameCreation(requested,stored,(Long)a.get("followup_plan_id")))throw ApiError.conflict("IDEMPOTENCY_CONFLICT");return lifecycle.attach(result(number(a,"appointment_id"),true,planId!=null),lifecycle.original(number(a,"appointment_id"),"创建"));
        }
        if(followupPlan!=null){FollowupLinkRules.newAssociation(hooks.value(followupPlan),patient);var source=locked.stream().filter(a->number(a,"appointment_id")==planCandidate.owner().source()).findFirst().orElseThrow(()->ApiError.conflict("FOLLOWUP_SOURCE_INVALID"));hooks.source(followupPlan,source);}
        if(start.before(db.now()))throw ApiError.bad();
        if(self)fulfillment.allow(patient);
        if(!enabled || !enabled(p))throw ApiError.conflict("RESOURCE_INACTIVE");
        rules.coverage(coverage,clinic,dentist,start,end);
        rules.available(locked,dentist,patient,start,end,-1);
        guestOccupancy.available(dentist,patient,start,end);
        long id=db.id();
        if(planCandidate!=null&&id==planCandidate.owner().source())throw ApiError.conflict("FOLLOWUP_SOURCE_INVALID");
        db.jdbc.update("INSERT INTO appointments(appointment_id,appointment_no,patient_id,booking_channel,dentist_id,clinic_id,followup_plan_id,schedule_id,scheduled_start,scheduled_end,requested_treatments,special_needs,appointment_status,treatment_status,created_by,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'待到诊','未开始',?,?)",id,b.appointmentNo,patient,b.bookingChannel,dentist,clinic,planId,schedule,start,end,b.requestedTreatments,b.specialNeeds,actor.id(),db.now());
        Map<String,Object> outcome;
        if(planId==null)outcome=lifecycle.apply(actor,lifecycle.fresh(id),id,id,(String)p.get("phone"),true);
        else {var afterRows=new ArrayList<>(locked);afterRows.add(db.one("SELECT * FROM appointments WHERE appointment_id=?",id));outcome=linkedReminders.apply(actor,id,followupPlan,afterRows,true,planActive,(String)p.get("phone")).outcome();}
        var audit=new LinkedHashMap<String,Object>();audit.put("requestedFollowupPlanId",planId==null?null:Long.toString(planId));audit.put("appointment",db.one("SELECT "+COLUMNS+",followup_plan_id FROM appointments WHERE appointment_id=?",id));audit.put("reminderOutcome",outcome);db.audit(actor.id(),"创建","appointment",id,null,null,audit);
        return lifecycle.attach(result(id,false,planId!=null),outcome);
    }
    private boolean enabled(Map<String,Object> row) { return Boolean.TRUE.equals(row.get("active")) || row.get("active") instanceof Number n && n.intValue()==1; }
    private long number(Map<String,Object> row,String column) { return ((Number)row.get(column)).longValue(); }
    private boolean covered(Map<String,Object> row,Timestamp start,Timestamp end) { return "有效".equals(row.get("status")) && !((Timestamp)row.get("start_at")).after(start) && !((Timestamp)row.get("end_at")).before(end); }
    private Object result(long id,boolean replayed,boolean linked) { return Map.of("replayed",replayed,"appointment",Store.json(db.one("SELECT "+COLUMNS+(linked?",followup_plan_id":"")+" FROM appointments WHERE appointment_id=?",id))); }
    @Transactional
    public Object patient(Identity.Actor actor,Patient p) {
        actor.front(Store.parseId(p.clinicId));
        Store.required(p.patientNo,32); Store.required(p.name,80); Store.required(p.billingAddress,255); Store.required(p.phone,32); Store.required(p.emergencyName,80); Store.required(p.emergencyPhone,32);
        if(p.email!=null && p.email.length()>254)throw ApiError.bad();
        java.time.LocalDate birth;
        try { birth=java.time.LocalDate.parse(p.birthDate); if(birth.isAfter(java.time.LocalDate.now(db.clock.withZone(java.time.ZoneId.of("Asia/Shanghai")))))throw ApiError.bad(); }catch(Exception e){throw ApiError.bad();}
        long id=db.id();
        db.jdbc.update("INSERT INTO patients(patient_id,patient_no,name,birth_date,billing_address,phone,email,emergency_name,emergency_phone,allergy_declaration,created_at) VALUES(?,?,?,?,?,?,?,?,?,'未询问',?)",id,p.patientNo,p.name,java.sql.Date.valueOf(birth),p.billingAddress,p.phone,p.email,p.emergencyName,p.emergencyPhone,db.now());
        db.audit(actor.id(),"创建","patient",id);
        return Store.json(db.one("SELECT * FROM patients WHERE patient_id=?",id));
    }
}
