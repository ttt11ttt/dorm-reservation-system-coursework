package cn.adc.dental;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
class FollowupService {
    static final String PLAN="followup_plan_id,patient_id,source_appointment_id,dentist_id,clinic_id,suggested_date,recommendation,status,completed_appointment_id,completed_at,cancel_reason,created_at,updated_at";
    private static final String APPOINTMENT="appointment_id,patient_id,dentist_id,clinic_id,schedule_id,previous_appointment_id,followup_plan_id,scheduled_start,scheduled_end,appointment_status,treatment_status,attended,arrived_at,completed_at";
    private static final String CONTACT="contact_id,followup_plan_id,responsible_account_id,contacted_at,contact_channel,result,note,next_contact_at";
    private static final String MUTATIONS="'复诊创建','复诊更新','复诊取消','复诊完成'";
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final Store db;
    private final ReminderFollowupContext reminderContexts;
    private final ReminderFollowupMaintenance reminderMaintenance;
    FollowupService(Store db,ReminderFollowupContext reminderContexts,ReminderFollowupMaintenance reminderMaintenance) { this.db=db;this.reminderContexts=reminderContexts;this.reminderMaintenance=reminderMaintenance; }
    private FollowupRules.Owner owner(Map<String,Object> p) { return new FollowupRules.Owner(Store.number(p,"patient_id"),Store.number(p,"dentist_id"),Store.number(p,"clinic_id"),Store.number(p,"source_appointment_id")); }
    private FollowupRules.Source source(Map<String,Object> a) { return new FollowupRules.Source(new FollowupRules.Owner(Store.number(a,"patient_id"),Store.number(a,"dentist_id"),Store.number(a,"clinic_id"),Store.number(a,"appointment_id")),Store.enabled(a,"attended"),a.get("appointment_status").toString(),a.get("treatment_status").toString(),a.get("completed_at")==null?null:((Timestamp)a.get("completed_at")).toInstant()); }
    private record ParentFacts(boolean active,String phone) {}
    private ParentFacts parents(FollowupRules.Owner o) {
        var clinic=db.one("SELECT active FROM clinics WHERE clinic_id=? FOR UPDATE",o.clinic());var dentist=db.one("SELECT active FROM dentists WHERE dentist_id=? FOR UPDATE",o.dentist());var patient=db.one("SELECT active,phone FROM patients WHERE patient_id=? FOR UPDATE",o.patient());return new ParentFacts(Store.enabled(clinic,"active")&&Store.enabled(dentist,"active")&&Store.enabled(patient,"active"),(String)patient.get("phone"));
    }
    private ParentFacts parents(FollowupRules.Owner o,ReminderFollowupContext.Candidate closure){
        boolean enabled=true;for(long id:closure.clinics()){var row=db.one("SELECT active FROM clinics WHERE clinic_id=? FOR UPDATE",id);if(id==o.clinic())enabled&=Store.enabled(row,"active");}for(long id:closure.dentists()){var row=db.one("SELECT active FROM dentists WHERE dentist_id=? FOR UPDATE",id);if(id==o.dentist())enabled&=Store.enabled(row,"active");}var patient=db.one("SELECT active,phone FROM patients WHERE patient_id=? FOR UPDATE",o.patient());return new ParentFacts(enabled&&Store.enabled(patient,"active"),(String)patient.get("phone"));
    }
    private Map<String,Object> plan(long id) { return db.one("SELECT "+PLAN+" FROM followup_plans WHERE followup_plan_id=?",id); }
    private List<Map<String,Object>> appointments(long id,long source,boolean lock) { return db.jdbc.queryForList("SELECT "+APPOINTMENT+" FROM appointments WHERE appointment_id=? OR followup_plan_id=? ORDER BY appointment_id"+(lock?" FOR UPDATE":""),source,id); }
    private void relations(Map<String,Object> p,List<Map<String,Object>> appointments) {
        var expected=owner(p);var a=appointments.stream().filter(row->Store.number(row,"appointment_id")==expected.source()).findFirst().orElseThrow(()->ApiError.conflict("FOLLOWUP_SOURCE_INVALID"));
        FollowupRules.source(source(a),expected,db.clock);
        if(appointments.stream().anyMatch(row->Store.number(row,"patient_id")!=expected.patient()))throw ApiError.conflict("FOLLOWUP_SOURCE_INVALID");
    }
    private boolean actualCompleted(List<Map<String,Object>> appointments,long source) { return appointments.stream().anyMatch(a->Store.number(a,"appointment_id")!=source&&Store.enabled(a,"attended")&&"已到诊".equals(a.get("appointment_status"))&&"已完成".equals(a.get("treatment_status"))&&a.get("completed_at")!=null); }
    private record Locked(Map<String,Object> plan,List<Map<String,Object>> appointments,boolean active,String phone,ReminderFollowupContext.Candidate reminderClosure) {}
    private Locked lock(Identity.Actor actor,long id,EncounterAccess.Mode mode) {
        var candidate=plan(id);FollowupRules.authorize(actor,owner(candidate),mode);var closure=reminderContexts.candidate(id);if(!owner(candidate).equals(owner(closure.plan())))throw ApiError.conflict("RETRY_TRANSACTION");var parents=parents(owner(candidate),closure);
        var p=db.one("SELECT "+PLAN+" FROM followup_plans WHERE followup_plan_id=? FOR UPDATE",id);
        if(!owner(candidate).equals(owner(p)))throw ApiError.conflict("RETRY_TRANSACTION");FollowupRules.authorize(actor,owner(p),mode);
        var appointments=appointments(id,Store.number(p,"source_appointment_id"),true);reminderContexts.verify(closure,p,appointments);relations(p,appointments);return new Locked(p,appointments,parents.active(),parents.phone(),closure);
    }
    private String string(Object value) { return value==null?null:Store.json(value).toString(); }
    FollowupRules.Snapshot snapshot(Map<String,Object> p) {
        Long count=db.jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE object_type='followup_plan' AND object_id=? AND action IN ("+MUTATIONS+")",Long.class,string(p.get("followup_plan_id")));
        return new FollowupRules.Snapshot(string(p.get("patient_id")),string(p.get("source_appointment_id")),string(p.get("dentist_id")),string(p.get("clinic_id")),string(p.get("suggested_date")),(String)p.get("recommendation"),(String)p.get("status"),string(p.get("completed_appointment_id")),string(p.get("completed_at")),(String)p.get("cancel_reason"),string(p.get("created_at")),string(p.get("updated_at")),Long.toString(count));
    }
    private Map<String,Object> view(Map<String,Object> p) { return view(p,FollowupRules.today(db.clock)); }
    private Map<String,Object> view(Map<String,Object> p,LocalDate date) { var result=new LinkedHashMap<>(p);result.put("overdue",FollowupRules.overdueOn(new FollowupRules.Observation(p.get("status").toString(),false,null),LocalDate.parse(p.get("suggested_date").toString()),date));return result; }
    private Map<String,Object> canonical(Identity.Actor actor,String operation,Object request) { return Map.of("actorId",Long.toString(actor.id()),"operation",operation,"body",request); }
    private record Replay(int cancelled,Object outcome) {}
    private Replay sameAudit(List<Map<String,Object>> rows,Object request) {
        if(rows.size()!=1||rows.getFirst().get("after_value")==null)throw ApiError.conflict("FOLLOWUP_CONFLICT");
        var value=JSON.readTree(rows.getFirst().get("after_value").toString());
        if(value.get("request")==null||!value.get("request").equals(JSON.valueToTree(Store.json(request))))throw ApiError.conflict("IDEMPOTENCY_CONFLICT");
        var outcome=value.get("reminderOutcome");return new Replay(value.get("cancelledReminderCount")==null?0:value.get("cancelledReminderCount").asInt(),ReminderLifecycleRules.originalOutcome(outcome==null||outcome.isNull()?null:outcome));
    }
    private Replay mutationReplay(long id,FollowupRules.Snapshot expected,Object request) {
        var rows=db.jdbc.queryForList("SELECT after_value FROM audit_logs WHERE object_type='followup_plan' AND object_id=? AND action IN ("+MUTATIONS+") AND JSON_UNQUOTE(JSON_EXTRACT(before_value,'$.mutationCount'))=?",Long.toString(id),expected.mutationCount());
        return rows.isEmpty()?null:sameAudit(rows,request);
    }
    private Object response(long id,boolean replayed,int cancelled,Object outcome) { var p=plan(id);return Store.json(Map.of("replayed",replayed,"plan",view(p),"snapshot",snapshot(p),"cancelledReminderCount",cancelled,"reminderScheduling","SEE_REMINDER_OUTCOME","reminderOutcome",outcome)); }
    private void mutationAudit(Identity.Actor actor,long id,String action,String reason,FollowupRules.Snapshot before,Object request,int cancelled,Object outcome) {
        // Count is derived from committed append-only mutations, independent of timestamp resolution or random audit ID order.
        db.audit(actor.id(),action,"followup_plan",id,reason,before,Map.of("request",request,"plan",view(plan(id)),"cancelledReminderCount",cancelled,"reminderOutcome",outcome));
    }
    @Transactional
    public Object create(Identity.Actor actor,long sourceId,FollowupRules.Creation request) {
        long id=Store.parseId(request.planId());FollowupRules.date(request.suggestedDate());FollowupRules.text(request.recommendation());
        var candidate=db.one("SELECT "+APPOINTMENT+" FROM appointments WHERE appointment_id=?",sourceId);var expected=source(candidate).owner();FollowupRules.authorize(actor,expected,EncounterAccess.Mode.DENTIST);
        var known=db.jdbc.queryForList("SELECT "+PLAN+" FROM followup_plans WHERE followup_plan_id=?",id);if(!known.isEmpty()&&!owner(known.getFirst()).equals(expected))throw ApiError.conflict("IDEMPOTENCY_CONFLICT");
        var parents=parents(expected);var existing=db.jdbc.queryForList("SELECT "+PLAN+" FROM followup_plans WHERE followup_plan_id=? FOR UPDATE",id);
        var a=db.one("SELECT "+APPOINTMENT+" FROM appointments WHERE appointment_id=? FOR UPDATE",sourceId);if(!source(a).owner().equals(expected))throw ApiError.conflict("RETRY_TRANSACTION");FollowupRules.source(source(a),expected,db.clock);
        var canonical=canonical(actor,"create",Map.of("sourceId",Long.toString(sourceId),"request",request));
        if(!existing.isEmpty()) {if(!owner(existing.getFirst()).equals(expected))throw ApiError.conflict("IDEMPOTENCY_CONFLICT");var replay=sameAudit(db.jdbc.queryForList("SELECT after_value FROM audit_logs WHERE object_type='followup_plan' AND object_id=? AND action='复诊创建'",Long.toString(id)),canonical);return response(id,true,replay.cancelled(),replay.outcome());}
        if(!parents.active())throw ApiError.conflict("RESOURCE_INACTIVE");
        db.jdbc.update("INSERT INTO followup_plans(followup_plan_id,patient_id,source_appointment_id,dentist_id,clinic_id,suggested_date,recommendation,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,'待复诊',?,?)",id,expected.patient(),sourceId,expected.dentist(),expected.clinic(),java.sql.Date.valueOf(FollowupRules.date(request.suggestedDate())),request.recommendation(),db.now(),db.now());
        var outcome=reminderMaintenance.apply(actor,plan(id),List.of(a),parents.active(),parents.phone());int cancelled=((Number)outcome.get("cancelledCount")).intValue();mutationAudit(actor,id,"复诊创建",request.recommendation(),null,canonical,cancelled,outcome);return response(id,false,cancelled,outcome);
    }
    @Transactional
    public Object change(Identity.Actor actor,long id,FollowupRules.Change request) {
        FollowupRules.expected(request.expected());FollowupRules.date(request.suggestedDate());FollowupRules.text(request.recommendation());FollowupRules.text(request.reason());
        return mutate(actor,id,request.expected(),request.suggestedDate(),request.recommendation(),request.reason(),false,canonical(actor,"change",request));
    }
    @Transactional
    public Object cancel(Identity.Actor actor,long id,FollowupRules.Cancellation request) {
        FollowupRules.expected(request.expected());FollowupRules.text(request.reason());return mutate(actor,id,request.expected(),null,null,request.reason(),true,canonical(actor,"cancel",request));
    }
    private Object mutate(Identity.Actor actor,long id,FollowupRules.Snapshot expected,String date,String recommendation,String reason,boolean cancel,Object request) {
        var c=lock(actor,id,EncounterAccess.Mode.DENTIST);var replay=mutationReplay(id,expected,request);if(replay!=null)return response(id,true,replay.cancelled(),replay.outcome());
        var before=snapshot(c.plan());FollowupRules.compare(expected,before);FollowupRules.pending(before.status(),actualCompleted(c.appointments(),owner(c.plan()).source()));if(!cancel&&!c.active())throw ApiError.conflict("RESOURCE_INACTIVE");
        if(cancel)db.jdbc.update("UPDATE followup_plans SET status='已取消',cancel_reason=?,updated_at=? WHERE followup_plan_id=?",reason,db.now(),id);
        else db.jdbc.update("UPDATE followup_plans SET suggested_date=?,recommendation=?,updated_at=? WHERE followup_plan_id=?",java.sql.Date.valueOf(FollowupRules.date(date)),recommendation,db.now(),id);
        var outcome=reminderMaintenance.apply(actor,plan(id),c.appointments(),c.active(),c.phone());int cancelled=((Number)outcome.get("cancelledCount")).intValue();mutationAudit(actor,id,cancel?"复诊取消":"复诊更新",reason,before,request,cancelled,outcome);return response(id,false,cancelled,outcome);
    }
    private Map<String,Object> currentAppointments(long id,long patient) {
        var rows=db.jdbc.queryForList("SELECT appointment_id,clinic_id,dentist_id,scheduled_start,scheduled_end,appointment_status,treatment_status,arrived_at,completed_at FROM appointments WHERE followup_plan_id=? AND patient_id=? AND appointment_status IN ('待到诊','已到诊','待核实') ORDER BY scheduled_start DESC,appointment_id DESC LIMIT 21",id,patient);
        return Map.of("items",rows.stream().limit(20).toList(),"truncated",rows.size()>20);
    }
    private Map<String,Object> detail(Map<String,Object> p,boolean withHistory) { return detail(p,withHistory,FollowupRules.today(db.clock)); }
    private Map<String,Object> detail(Map<String,Object> p,boolean withHistory,LocalDate asOfDate) {
        long id=Store.number(p,"followup_plan_id"),patient=Store.number(p,"patient_id"),source=Store.number(p,"source_appointment_id");
        var linked=appointments(id,source,false);relations(p,linked);
        var result=new LinkedHashMap<String,Object>();result.put("plan",view(p,asOfDate));result.put("patient",db.one("SELECT patient_id,patient_no,name,phone FROM patients WHERE patient_id=?",patient));result.put("appointments",currentAppointments(id,patient));result.put("completionReconciliationRequired",actualCompleted(linked,source)&&"待复诊".equals(p.get("status")));
        if(withHistory) {result.put("snapshot",snapshot(p));var contacts=db.jdbc.queryForList("SELECT "+CONTACT+" FROM followup_contacts WHERE followup_plan_id=? ORDER BY contacted_at DESC,contact_id DESC LIMIT 21",id);result.put("contacts",Map.of("items",contacts.stream().limit(20).toList(),"truncated",contacts.size()>20));}
        return result;
    }
    @Transactional
    public Object read(Identity.Actor actor,long id,EncounterAccess.Mode mode) {
        var c=lock(actor,id,mode);db.audit(actor.id(),"读取","followup_plan",id);return Store.json(mode==EncounterAccess.Mode.PATIENT?view(c.plan()):detail(c.plan(),true));
    }
    @Transactional
    public Object list(Identity.Actor actor,Long clinic,String status,boolean overdueOnly,String afterId,int limit,boolean patient) {
        return listFor(actor,clinic,status,overdueOnly,afterId,limit,patient?EncounterAccess.Mode.PATIENT:EncounterAccess.Mode.FRONT);
    }
    @Transactional
    public Object dentistList(Identity.Actor actor,long clinic,String status,String afterId,int limit) {
        return listFor(actor,clinic,status,false,afterId,limit,EncounterAccess.Mode.DENTIST);
    }
    private Object listFor(Identity.Actor actor,Long clinic,String status,boolean overdueOnly,String afterId,int limit,EncounterAccess.Mode mode) {
        boolean patient=mode==EncounterAccess.Mode.PATIENT,dentist=mode==EncounterAccess.Mode.DENTIST;
        FollowupRules.page(status,overdueOnly,limit);long after=afterId==null?0:Store.parseId(afterId);
        FollowupRules.DentistListScope dentistScope=null;
        if(patient)actor.patient();else if(dentist)actor.front(clinic);else actor.front(clinic);
        var date=FollowupRules.today(db.clock);var sql=new StringBuilder("SELECT "+PLAN+" FROM followup_plans WHERE "+(patient?"patient_id":"clinic_id")+"=? AND followup_plan_id>?");var args=new ArrayList<Object>();args.add(patient?actor.patientId():clinic);args.add(after);
        if(dentistScope!=null) { sql.append(" AND dentist_id=?");args.add(dentistScope.dentist()); }
        if(!status.equals("all")){sql.append(" AND status=?");args.add(status);}if(overdueOnly){sql.append(" AND status='待复诊' AND suggested_date<?");args.add(java.sql.Date.valueOf(date.minusDays(3)));}sql.append(" ORDER BY followup_plan_id LIMIT ?");args.add(limit+1);
        var rows=db.jdbc.queryForList(sql.toString(),args.toArray());var items=new ArrayList<Object>();for(var p:rows.stream().limit(limit).toList()){
            if(patient||dentist){
                if(dentistScope!=null&&!dentistScope.includes(owner(p)))throw ApiError.denied();
                relations(p,appointments(Store.number(p,"followup_plan_id"),Store.number(p,"source_appointment_id"),false));
                items.add(patient?view(p,date):Map.of("plan",view(p,date),"patient",db.one("SELECT patient_no,name FROM patients WHERE patient_id=?",p.get("patient_id"))));
            }else items.add(detail(p,false,date));
        }
        db.audit(actor.id(),"读取",patient?"own_followup_list":dentist?"dentist_followup_list":"followup_list",patient?actor.patientId():clinic);
        return Store.json(pageResult(items,rows.size()>limit,rows.size()>limit?string(rows.get(limit-1).get("followup_plan_id")):null,date.toString()));
    }
    private Map<String,Object> pageResult(List<?> items,boolean more,String next,String date) {var result=new LinkedHashMap<String,Object>();result.put("items",items);result.put("hasMore",more);result.put("nextAfterId",next);if(date!=null)result.put("asOfDate",date);return result;}
    @Transactional
    public Object contacts(Identity.Actor actor,long id,String afterId,int limit) {
        FollowupRules.page("all",false,limit);long after=afterId==null?0:Store.parseId(afterId);lock(actor,id,EncounterAccess.Mode.FRONT);
        var rows=db.jdbc.queryForList("SELECT "+CONTACT+" FROM followup_contacts WHERE followup_plan_id=? AND contact_id>? ORDER BY contact_id LIMIT ?",id,after,limit+1);db.audit(actor.id(),"读取","followup_contacts",id);
        return Store.json(pageResult(rows.stream().limit(limit).toList(),rows.size()>limit,rows.size()>limit?string(rows.get(limit-1).get("contact_id")):null,null));
    }
    @Transactional
    public Object contact(Identity.Actor actor,long id,FollowupRules.Contact request) {
        FollowupRules.contact(request,db.clock);long contactId=Store.parseId(request.contactId());var c=lock(actor,id,EncounterAccess.Mode.FRONT);
        var existing=db.jdbc.queryForList("SELECT "+CONTACT+" FROM followup_contacts WHERE contact_id=? FOR UPDATE",contactId);
        Timestamp time=Store.offset(request.contactedAt()),next=request.nextContactAt()==null?null:Store.offset(request.nextContactAt());
        if(!existing.isEmpty()) {
            var old=existing.getFirst();boolean same=Store.number(old,"followup_plan_id")==id&&Store.number(old,"responsible_account_id")==actor.id()&&"电话".equals(old.get("contact_channel"))&&time.equals(old.get("contacted_at"))&&Objects.equals(next,old.get("next_contact_at"))&&request.result().equals(old.get("result"))&&request.note().equals(old.get("note"));
            if(!same)throw ApiError.conflict("IDEMPOTENCY_CONFLICT");return Store.json(Map.of("replayed",true,"contact",old,"planStatus",c.plan().get("status")));
        }
        FollowupRules.pending(c.plan().get("status").toString(),false);
        db.jdbc.update("INSERT INTO followup_contacts(contact_id,followup_plan_id,responsible_account_id,contacted_at,contact_channel,result,note,next_contact_at) VALUES(?,?,?,?,'电话',?,?,?)",contactId,id,actor.id(),time,request.result(),request.note(),next);
        var record=db.one("SELECT "+CONTACT+" FROM followup_contacts WHERE contact_id=?",contactId);db.audit(actor.id(),"追加电话联系","followup_contact",contactId,request.note(),null,record);
        String status=FollowupRules.observedStatus(new FollowupRules.Observation(c.plan().get("status").toString(),false,request.result()));return Store.json(Map.of("replayed",false,"contact",record,"planStatus",status));
    }
}
