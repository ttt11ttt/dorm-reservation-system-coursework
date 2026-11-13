package cn.adc.dental;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.*;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AppointmentChanges {
    record Adjustment(Integer expectedRowVersion,String appointmentNo,String clinicId,String dentistId,String scheduleId,String scheduledStart,String scheduledEnd,String requestedTreatments,String specialNeeds,String reason,String bookingChannel,String responsibility,Boolean exempt) {
        @JsonAnySetter void unknown(String key,Object value) { throw ApiError.bad(); }
    }
    record Decision(Integer expectedRowVersion,String reason,String responsibility,Boolean exempt) {
        @JsonAnySetter void unknown(String key,Object value) { throw ApiError.bad(); }
    }
    record Review(Boolean valid,Boolean exempt,String reason) { @JsonAnySetter void unknown(String key,Object value) { throw ApiError.bad(); } }
    record ResultCorrection(Integer expectedRowVersion,String reason) {}
    private final Store db;
    private final BookingRules rules;
    private final GuestOccupancy guestOccupancy;
    private final EncounterAccess access;
    private final FulfillmentService fulfillment;
    private final FollowupHooks hooks;
    private final ReminderLifecycle lifecycle;
    private final ReminderFollowupContext reminderContexts;
    private final ReminderLinkedMaintenance linkedReminders;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    AppointmentChanges(Store db,BookingRules rules,GuestOccupancy guestOccupancy,EncounterAccess access,FulfillmentService fulfillment,FollowupHooks hooks,ReminderLifecycle lifecycle,ReminderFollowupContext reminderContexts,ReminderLinkedMaintenance linkedReminders) { this.db=db;this.rules=rules;this.guestOccupancy=guestOccupancy;this.access=access;this.fulfillment=fulfillment;this.hooks=hooks;this.lifecycle=lifecycle;this.reminderContexts=reminderContexts;this.linkedReminders=linkedReminders; }
    private void decision(boolean self,String responsibility,Boolean exempt,String reason) {
        Store.required(reason,8000);
        if(self) { if(responsibility!=null||exempt!=null)throw ApiError.bad(); }
        else if(!Set.of("患者","诊所","误录").contains(Objects.toString(responsibility,""))||exempt==null)throw ApiError.bad();
    }
    private Map<String,Object> canonical(Identity.Actor actor,boolean self,Integer version,String reason,String responsibility,Boolean exempt) {
        if(version==null||version<0)throw ApiError.bad();var request=new LinkedHashMap<String,Object>();request.put("actor",Long.toString(actor.id()));request.put("self",self);request.put("version",version);request.put("reason",reason);request.put("responsibility",self?"患者":responsibility);request.put("exempt",!self&&exempt);return request;
    }
    private void replay(long id,String action,Object request) {
        var rows=db.jdbc.queryForList("SELECT after_value FROM audit_logs WHERE object_type='appointment' AND object_id=? AND action=? ORDER BY happened_at DESC,audit_id DESC LIMIT 1",Long.toString(id),action);
        if(rows.isEmpty()||rows.getFirst().get("after_value")==null)throw ApiError.conflict("IDEMPOTENCY_CONFLICT");
        var saved=JSON.readTree(rows.getFirst().get("after_value").toString()).get("request");
        if(saved==null||!saved.equals(JSON.valueToTree(Store.json(request))))throw ApiError.conflict("IDEMPOTENCY_CONFLICT");
    }
    private Map<String,Object> current(long id) { return db.one("SELECT "+BookingService.COLUMNS+",previous_appointment_id,followup_plan_id,change_reason,cancelled_at FROM appointments WHERE appointment_id=?",id); }
    private Object result(long id,boolean replayed) { return Store.json(Map.of("replayed",replayed,"appointment",current(id))); }
    private void pending(Map<String,Object> a) {
        if(!Set.of("待到诊","待核实").contains(a.get("appointment_status"))||Store.enabled(a,"attended")||!"未开始".equals(a.get("treatment_status")))throw ApiError.conflict("MANUAL_REVIEW_REQUIRED");
        access.noInvoice(Store.number(a,"appointment_id"));
        if(db.jdbc.queryForObject("SELECT COUNT(*) FROM treatments WHERE appointment_id=?",Integer.class,a.get("appointment_id"))!=0)throw ApiError.conflict("MANUAL_REVIEW_REQUIRED");
    }
    private void audit(Identity.Actor actor,long id,String action,String reason,Object before,Object request,Object outcome) { db.audit(actor.id(),action,"appointment",id,reason,before,Map.of("request",request,"appointment",current(id),"reminderOutcome",outcome)); }
    private boolean late(Map<String,Object> a,Timestamp now) { return Duration.between(now.toInstant(),((Timestamp)a.get("scheduled_start")).toInstant()).getSeconds()<10800; }
    @Transactional
    public Object cancel(Identity.Actor actor,long id,Decision request,boolean self,boolean noShow) {
        decision(self,request.responsibility(),request.exempt(),request.reason());var canonical=canonical(actor,self,request.expectedRowVersion(),request.reason(),request.responsibility(),request.exempt());
        var c=access.lockLifecycle(actor,id,self?EncounterAccess.Mode.PATIENT:EncounterAccess.Mode.FRONT);var a=c.appointment();String action=noShow?"未到诊核实":"预约取消";
        String responsibility=self?"患者":request.responsibility();boolean exempt=!self&&request.exempt();
        String state=noShow?"未到诊":switch(responsibility){case "诊所"->"诊所撤销";case "误录"->"误录作废";default->"患者取消";};
        if(state.equals(a.get("appointment_status"))) { replay(id,action,canonical);return lifecycle.attach(result(id,true),lifecycle.original(id,action)); }
        pending(a);access.version(a,request.expectedRowVersion());Timestamp now=db.now();
        if(noShow&&!now.after((Timestamp)a.get("scheduled_end")))throw ApiError.conflict("APPOINTMENT_NOT_ENDED");
        db.jdbc.update("UPDATE appointments SET appointment_status=?,cancelled_at=?,change_reason=?,row_version=row_version+1 WHERE appointment_id=?",state,Set.of("患者取消","诊所撤销").contains(state)?now:null,request.reason(),id);
        if(noShow||late(a,now))fulfillment.event(actor,a,noShow?"no_show":"late_cancel",noShow?(Timestamp)a.get("scheduled_end"):now,responsibility,exempt,request.reason());
        fulfillment.trigger(actor,Store.number(a,"patient_id"));Map<String,Object> outcome;
        if(c.followupPlan()==null)outcome=lifecycle.apply(actor,c.reminderChain(),id,null,null,true);
        else {var afterRows=new ArrayList<>(c.lockedAppointments());afterRows.removeIf(row->Store.number(row,"appointment_id")==id);afterRows.add(db.one("SELECT * FROM appointments WHERE appointment_id=?",id));outcome=linkedReminders.apply(actor,id,c.followupPlan(),afterRows,false,c.planResourcesActive(),(String)c.patient().get("phone")).outcome();}
        audit(actor,id,action,request.reason(),a,canonical,outcome);return lifecycle.attach(result(id,false),outcome);
    }
    @Transactional
    public Object correctResult(Identity.Actor actor,long id,ResultCorrection request){
        if(request.expectedRowVersion()==null||request.expectedRowVersion()<0)throw ApiError.bad();
        String reason=OrganizationRules.label(request.reason(),500);
        var c=access.lockLifecycle(actor,id,EncounterAccess.Mode.FRONT);
        var before=c.appointment();
        access.version(before,request.expectedRowVersion());
        if(!Set.of("已到诊","未到诊").contains(before.get("appointment_status"))||!"未开始".equals(before.get("treatment_status"))
            ||before.get("checked_at")!=null||db.jdbc.queryForObject("SELECT COUNT(*) FROM treatments WHERE appointment_id=?",Long.class,id)>0){
            throw ApiError.conflict("MANUAL_REVIEW_REQUIRED");
        }
        access.noInvoice(id);
        long patient=Store.number(before,"patient_id"),dentist=Store.number(before,"dentist_id");
        var start=(Timestamp)before.get("scheduled_start");
        var end=(Timestamp)before.get("scheduled_end");
        if(db.jdbc.queryForObject("""
            SELECT COUNT(*) FROM appointments WHERE appointment_id<>? AND (dentist_id=? OR patient_id=?)
              AND appointment_status IN ('待到诊','待核实','已到诊') AND scheduled_start<? AND scheduled_end>?
            """,Long.class,id,dentist,patient,end,start)>0)throw ApiError.conflict("TIME_CONFLICT");
        guestOccupancy.available(dentist,patient,start,end);
        String state=end.after(db.now())?"待到诊":"待核实";
        db.jdbc.update("""
            UPDATE appointments SET attended=0,arrived_at=NULL,appointment_status=?,change_reason=?,row_version=row_version+1
            WHERE appointment_id=?
            """,state,reason,id);
        for(var event:db.jdbc.queryForList("SELECT * FROM appointment_breaches WHERE appointment_id=? AND breach_kind='no_show' AND valid=1 FOR UPDATE",id)){
            long breach=Store.number(event,"breach_id");
            db.jdbc.update("UPDATE appointment_breaches SET valid=0,responsibility_reason=?,updated_by=?,updated_at=? WHERE breach_id=?",reason,actor.id(),db.now(),breach);
            db.audit(actor.id(),"更正未到诊事件","appointment_breach",breach,reason,event,db.one("SELECT * FROM appointment_breaches WHERE breach_id=?",breach));
            fulfillment.revokeIfInsufficient(actor,(Long)event.get("restriction_id"),reason);
        }
        var updated=db.one("SELECT * FROM appointments WHERE appointment_id=?",id);
        Map<String,Object> outcome;
        if(c.followupPlan()==null)outcome=lifecycle.apply(actor,c.reminderChain(),id,id,(String)c.patient().get("phone"),c.resourcesActive());
        else{
            var rows=new ArrayList<>(c.lockedAppointments());
            rows.removeIf(row->Store.number(row,"appointment_id")==id);
            rows.add(updated);
            outcome=linkedReminders.apply(actor,id,c.followupPlan(),rows,c.resourcesActive(),c.planResourcesActive(),(String)c.patient().get("phone")).outcome();
        }
        audit(actor,id,"就诊结果更正",reason,before,request,outcome);
        return lifecycle.attach(result(id,false),outcome);
    }

    @Transactional
    public Object reschedule(Identity.Actor actor,long id,Adjustment request,boolean self) {
        decision(self,request.responsibility(),request.exempt(),request.reason());
        if(self&&request.bookingChannel()!=null)throw ApiError.bad();
        var candidate=db.one("SELECT * FROM appointments WHERE appointment_id=?",id);access.authorize(actor,candidate,self?EncounterAccess.Mode.PATIENT:EncounterAccess.Mode.FRONT);
        var reminderChain=lifecycle.candidate(id);
        long clinic=Store.parseId(request.clinicId()),dentist=Store.parseId(request.dentistId()),schedule=Store.parseId(request.scheduleId()),patient=Store.number(candidate,"patient_id");
        if(!self)actor.front(clinic);
        var planCandidate=candidate.get("followup_plan_id")==null?null:hooks.candidate(Store.number(candidate,"followup_plan_id"));
        if(planCandidate!=null){if(planCandidate.owner().patient()!=patient||planCandidate.owner().source()==id)throw ApiError.conflict("FOLLOWUP_SOURCE_INVALID");FollowupLinkRules.authorize(actor,planCandidate,clinic,self);}
        var reminderClosure=planCandidate==null?null:reminderContexts.candidate(planCandidate.id());
        String channel=self?"自助":request.bookingChannel();
        var b=new BookingService.Booking(request.appointmentNo(),Long.toString(patient),request.clinicId(),request.dentistId(),request.scheduleId(),request.scheduledStart(),request.scheduledEnd(),channel,request.requestedTreatments(),request.specialNeeds());rules.validate(b,self);
        Timestamp start=Store.offset(b.scheduledStart()),end=Store.offset(b.scheduledEnd());
        var canonical=canonical(actor,self,request.expectedRowVersion(),request.reason(),request.responsibility(),request.exempt());
        canonical.put("target",Arrays.asList(b.appointmentNo(),b.clinicId(),b.dentistId(),b.scheduleId(),start.toInstant().toString(),end.toInstant().toString(),b.requestedTreatments(),b.specialNeeds(),channel));
        boolean enabled=true;var clinicActive=new HashMap<Long,Boolean>();var dentistActive=new HashMap<Long,Boolean>();
        var clinics=new ArrayList<>(List.of(clinic,Store.number(candidate,"clinic_id")));var dentists=new ArrayList<>(List.of(dentist,Store.number(candidate,"dentist_id")));if(planCandidate!=null){clinics.add(planCandidate.owner().clinic());dentists.add(planCandidate.owner().dentist());}
        if(reminderChain!=null){clinics.addAll(reminderChain.clinics());dentists.addAll(reminderChain.dentists());}
        if(reminderClosure!=null){clinics.addAll(reminderClosure.clinics());dentists.addAll(reminderClosure.dentists());}
        var resources=FollowupLinkRules.resources(clinics,dentists);
        for(long clinicId:resources.clinics()) { var row=db.one("SELECT active FROM clinics WHERE clinic_id=? FOR UPDATE",clinicId);clinicActive.put(clinicId,Store.enabled(row,"active"));if(clinicId==clinic)enabled&=Store.enabled(row,"active"); }
        for(long dentistId:resources.dentists()) {var row=db.one("SELECT active FROM dentists WHERE dentist_id=? FOR UPDATE",dentistId);dentistActive.put(dentistId,Store.enabled(row,"active"));if(dentistId==dentist)enabled&=Store.enabled(row,"active");}
        var patientRow=db.one("SELECT active,phone FROM patients WHERE patient_id=? FOR UPDATE",patient);enabled&=Store.enabled(patientRow,"active");
        boolean planActive=planCandidate!=null&&Boolean.TRUE.equals(clinicActive.get(planCandidate.owner().clinic()))&&Boolean.TRUE.equals(dentistActive.get(planCandidate.owner().dentist()))&&Store.enabled(patientRow,"active");
        var coverage=rules.lockCoverage(schedule,clinic,dentist);
        var followupPlan=planCandidate==null?null:hooks.lock(planCandidate);
        var appointmentArgs=new ArrayList<Object>(List.of(id,planCandidate==null?-1L:planCandidate.owner().source(),id,b.appointmentNo(),dentist,patient,end,start));String extra=lifecycle.include(reminderChain,appointmentArgs);if(reminderClosure!=null)extra+=reminderContexts.include(reminderClosure,appointmentArgs);
        var rows=db.jdbc.queryForList("SELECT * FROM appointments WHERE appointment_id=? OR appointment_id=? OR previous_appointment_id=? OR appointment_no=? OR ((dentist_id=? OR patient_id=?) AND appointment_status IN ('待到诊','已到诊','待核实') AND scheduled_start<? AND scheduled_end>?)"+extra+" ORDER BY appointment_id FOR UPDATE",appointmentArgs.toArray());lifecycle.verify(reminderChain);if(reminderClosure!=null)reminderContexts.verify(reminderClosure,followupPlan,rows);
        var a=rows.stream().filter(x->Store.number(x,"appointment_id")==id).findFirst().orElseThrow(()->ApiError.conflict("RETRY_TRANSACTION"));
        for(String key:List.of("clinic_id","dentist_id","patient_id","followup_plan_id"))if(!Objects.equals(candidate.get(key),a.get(key)))throw ApiError.conflict("RETRY_TRANSACTION");
        if(followupPlan!=null){var source=rows.stream().filter(row->Store.number(row,"appointment_id")==planCandidate.owner().source()).findFirst().orElseThrow(()->ApiError.conflict("FOLLOWUP_SOURCE_INVALID"));hooks.source(followupPlan,source);}
        if("已改约".equals(a.get("appointment_status"))) { replay(id,"预约改约",canonical);var successor=rows.stream().filter(x->x.get("previous_appointment_id") instanceof Number n&&n.longValue()==id).findFirst().orElseThrow(()->ApiError.conflict("IDEMPOTENCY_CONFLICT"));return lifecycle.attach(result(Store.number(successor,"appointment_id"),true),lifecycle.original(id,"预约改约")); }
        pending(a);access.version(a,request.expectedRowVersion());if(self)fulfillment.allow(patient);
        if(!enabled)throw ApiError.conflict("RESOURCE_INACTIVE");if(start.before(db.now()))throw ApiError.bad();rules.coverage(coverage,clinic,dentist,start,end);rules.available(rows,dentist,patient,start,end,id);guestOccupancy.available(dentist,patient,start,end);
        if(rows.stream().anyMatch(x->b.appointmentNo().equals(x.get("appointment_no"))))throw ApiError.conflict("IDEMPOTENCY_CONFLICT");
        long successor=db.id();Timestamp now=db.now();
        db.jdbc.update("INSERT INTO appointments(appointment_id,appointment_no,patient_id,booking_channel,dentist_id,clinic_id,followup_plan_id,schedule_id,scheduled_start,scheduled_end,requested_treatments,special_needs,appointment_status,treatment_status,previous_appointment_id,created_by,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'待到诊','未开始',?,?,?)",successor,b.appointmentNo(),patient,channel,dentist,clinic,a.get("followup_plan_id"),schedule,start,end,b.requestedTreatments(),b.specialNeeds(),id,actor.id(),now);
        db.jdbc.update("UPDATE appointments SET appointment_status='已改约',change_reason=?,row_version=row_version+1 WHERE appointment_id=?",request.reason(),id);
        if(late(a,now))fulfillment.event(actor,a,"late_reschedule",now,self?"患者":request.responsibility(),!self&&request.exempt(),request.reason());
        fulfillment.trigger(actor,patient);Map<String,Object> outcome;
        if(followupPlan==null)outcome=lifecycle.apply(actor,reminderChain,successor,successor,(String)patientRow.get("phone"),true);
        else {var afterRows=new ArrayList<>(rows);afterRows.removeIf(row->Store.number(row,"appointment_id")==id);afterRows.add(db.one("SELECT * FROM appointments WHERE appointment_id=?",id));afterRows.add(db.one("SELECT * FROM appointments WHERE appointment_id=?",successor));outcome=linkedReminders.apply(actor,successor,followupPlan,afterRows,true,planActive,(String)patientRow.get("phone")).outcome();}
        audit(actor,id,"预约改约",request.reason(),a,canonical,outcome);var successorAudit=new LinkedHashMap<>(current(successor));successorAudit.put("reminderOutcome",outcome);db.audit(actor.id(),"创建","appointment",successor,null,null,successorAudit);return lifecycle.attach(result(successor,false),outcome);
    }
    @Transactional
    public Object read(Identity.Actor actor,long id) {
        var c=access.lock(actor,id,EncounterAccess.Mode.FRONT);var events=db.jdbc.queryForList("SELECT * FROM appointment_breaches WHERE appointment_id=?",id);var result=new LinkedHashMap<String,Object>();result.put("appointment",current(id));result.put("event",events.isEmpty()?null:events.getFirst());result.put("bookingStatus",fulfillment.status(Store.number(c.appointment(),"patient_id")));db.audit(actor.id(),"读取","fulfillment",id);return Store.json(result);
    }
    @Transactional
    public Object review(Identity.Actor actor,long id,Review request) {
        if(request.valid()==null||request.exempt()==null)throw ApiError.bad();Store.required(request.reason(),8000);
        var candidate=db.one("SELECT appointment_id FROM appointment_breaches WHERE breach_id=?",id);var c=access.lock(actor,Store.number(candidate,"appointment_id"),EncounterAccess.Mode.FRONT);
        var before=db.one("SELECT * FROM appointment_breaches WHERE breach_id=? FOR UPDATE",id);
        if(Store.number(before,"appointment_id")!=Store.number(candidate,"appointment_id"))throw ApiError.conflict("RETRY_TRANSACTION");
        boolean same=Store.enabled(before,"valid")==request.valid()&&Store.enabled(before,"exempt")==request.exempt()&&request.reason().equals(before.get("responsibility_reason"))&&Store.number(before,"updated_by")==actor.id();
        if(!same) {db.jdbc.update("UPDATE appointment_breaches SET valid=?,exempt=?,responsibility_reason=?,updated_by=?,updated_at=? WHERE breach_id=?",request.valid(),request.exempt(),request.reason(),actor.id(),db.now(),id);db.audit(actor.id(),"违约复核","appointment_breach",id,request.reason(),before,db.one("SELECT * FROM appointment_breaches WHERE breach_id=?",id));fulfillment.revokeIfInsufficient(actor,(Long)before.get("restriction_id"),request.reason());fulfillment.trigger(actor,Store.number(c.appointment(),"patient_id"));}
        return Store.json(Map.of("replayed",same,"event",db.one("SELECT * FROM appointment_breaches WHERE breach_id=?",id),"bookingStatus",fulfillment.status(Store.number(c.appointment(),"patient_id"))));
    }
}
