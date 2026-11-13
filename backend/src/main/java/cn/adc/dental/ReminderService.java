package cn.adc.dental;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
class ReminderService {
    private static final String CONFIG="rule_id,clinic_id,appointment_lead_minutes,followup_lead_days,followup_send_time,in_app_enabled,sms_enabled,updated_by,updated_at";
    private static final String APPOINTMENT="appointment_id,patient_id,dentist_id,clinic_id,schedule_id,scheduled_start,scheduled_end,appointment_status,treatment_status,attended,arrived_at,completed_at,followup_plan_id";
    private static final String MESSAGE="message_id,patient_id,clinic_id,appointment_id,followup_plan_id,reminder_kind,planned_at,channel,recipient_snapshot,content,send_status,sent_at";
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final Store db;
    private final BookingRules bookingRules;
    private final FollowupHooks hooks;
    private final ReminderMessageWriter writer;
    private final ReminderChainReader chains;
    private final ReminderConfiguration configuration;
    ReminderService(Store db,BookingRules bookingRules,FollowupHooks hooks,ReminderMessageWriter writer,ReminderChainReader chains,ReminderConfiguration configuration){this.db=db;this.bookingRules=bookingRules;this.hooks=hooks;this.writer=writer;this.chains=chains;this.configuration=configuration;}
    private String string(Object v){return v==null?null:Store.json(v).toString();}
    Map<String,Object> config(long clinic){return configuration.config(clinic);}
    ReminderRules.Settings settings(Map<String,Object> c){return ReminderConfiguration.settings(c);}
    private Map<String,Object> rule(Map<String,Object> c){if(c==null)return null;var result=new LinkedHashMap<>(c);result.put("followup_send_time",settings(c).followupSendTime());return result;}
    ReminderRules.Snapshot snapshot(Map<String,Object> c){
        if(c==null)return null;var s=settings(c);Long count=db.jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE object_type='reminder_rule' AND object_id=? AND action IN ('提醒规则创建','提醒规则维护')",Long.class,string(c.get("clinic_id")));
        return new ReminderRules.Snapshot(string(c.get("rule_id")),string(c.get("clinic_id")),s.appointmentLeadMinutes(),s.followupLeadDays(),s.followupSendTime(),s.inAppEnabled(),s.smsEnabled(),string(c.get("updated_by")),string(c.get("updated_at")),Long.toString(count));
    }
    private Object configResponse(long clinic,boolean replayed,boolean write){var c=config(clinic);var result=new LinkedHashMap<String,Object>();result.put("clinicId",Long.toString(clinic));result.put("rule",rule(c));result.put("snapshot",snapshot(c));result.put("batchRebuild","NOT_IMPLEMENTED");result.put("delivery","IN_APP_ONLY");if(write){result.put("replayed",replayed);result.put("rebuildRequired",true);}return Store.json(result);}
    @Transactional
    public Object readConfig(Identity.Actor actor,long clinic){ReminderRules.authorize(actor,clinic,false);db.one("SELECT clinic_id FROM clinics WHERE clinic_id=?",clinic);db.audit(actor.id(),"读取","reminder_rule",clinic);return configResponse(clinic,false,false);}
    @Transactional
    public Object saveConfig(Identity.Actor actor,long clinic,ReminderRules.Save request){
        ReminderRules.authorize(actor,clinic,true);long id=Store.parseId(request.ruleId());ReminderRules.expected(request.expected());Store.required(request.reason(),1000);var desired=ReminderRules.settings(request);ReminderRules.settings(desired);
        db.one("SELECT clinic_id FROM clinics WHERE clinic_id=? FOR UPDATE",clinic);var existing=db.jdbc.queryForList("SELECT "+CONFIG+" FROM clinic_booking_rules WHERE clinic_id=? FOR UPDATE",clinic);var current=existing.isEmpty()?null:existing.getFirst();
        var canonical=new LinkedHashMap<String,Object>();canonical.put("actorId",Long.toString(actor.id()));canonical.put("ruleId",request.ruleId());canonical.put("expected",request.expected());canonical.put("settings",desired);canonical.put("reason",request.reason());
        var prior=request.expected()==null?db.jdbc.queryForList("SELECT after_value FROM audit_logs WHERE object_type='reminder_rule' AND object_id=? AND action='提醒规则创建'",Long.toString(clinic)):db.jdbc.queryForList("SELECT after_value FROM audit_logs WHERE object_type='reminder_rule' AND object_id=? AND action='提醒规则维护' AND JSON_UNQUOTE(JSON_EXTRACT(before_value,'$.mutationCount'))=?",Long.toString(clinic),request.expected().mutationCount());
        if(!prior.isEmpty()){if(prior.size()!=1||prior.getFirst().get("after_value")==null)throw ApiError.conflict("IDEMPOTENCY_CONFLICT");var saved=JSON.readTree(prior.getFirst().get("after_value").toString()).get("request");if(saved==null||!saved.equals(JSON.valueToTree(Store.json(canonical))))throw ApiError.conflict("IDEMPOTENCY_CONFLICT");return configResponse(clinic,true,true);}
        var before=snapshot(current);ReminderRules.compare(request.expected(),before);if(current!=null&&Store.number(current,"rule_id")!=id)throw ApiError.conflict("RULE_SNAPSHOT_CONFLICT");
        var blocked=db.jdbc.queryForList("SELECT message_id,send_status FROM reminder_messages WHERE clinic_id=? AND send_status IN ('待发送','发送中','失败') ORDER BY message_id LIMIT 1 FOR UPDATE",clinic);
        if(!ReminderRules.ruleChangeSafe(blocked.stream().map(row->row.get("send_status").toString()).toList()))throw new ApiError(409,"RULE_CHANGE_REQUIRES_RECONCILIATION","本店仍有待发送或结果待核实记录，需先逐对象核对；未执行批量重排");
        var collision=db.jdbc.queryForList("SELECT clinic_id FROM clinic_booking_rules WHERE rule_id=?",id);if(!collision.isEmpty()&&Store.number(collision.getFirst(),"clinic_id")!=clinic)throw ApiError.conflict("IDEMPOTENCY_CONFLICT");
        java.sql.Time time=desired.followupSendTime()==null?null:java.sql.Time.valueOf(desired.followupSendTime());
        if(current==null)db.jdbc.update("INSERT INTO clinic_booking_rules(rule_id,clinic_id,appointment_lead_minutes,followup_lead_days,followup_send_time,in_app_enabled,sms_enabled,updated_by,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",id,clinic,desired.appointmentLeadMinutes(),desired.followupLeadDays(),time,desired.inAppEnabled(),desired.smsEnabled(),actor.id(),db.now());
        else db.jdbc.update("UPDATE clinic_booking_rules SET appointment_lead_minutes=?,followup_lead_days=?,followup_send_time=?,in_app_enabled=?,sms_enabled=?,updated_by=?,updated_at=? WHERE clinic_id=?",desired.appointmentLeadMinutes(),desired.followupLeadDays(),time,desired.inAppEnabled(),desired.smsEnabled(),actor.id(),db.now(),clinic);
        db.audit(actor.id(),current==null?"提醒规则创建":"提醒规则维护","reminder_rule",clinic,request.reason(),before,Map.of("request",canonical,"rule",rule(config(clinic))));return configResponse(clinic,false,true);
    }
    private Map<String,Object> patient(long id){return db.one("SELECT patient_id,phone,active FROM patients WHERE patient_id=? FOR UPDATE",id);}
    private record AppointmentContext(ReminderRules.Business business,ReminderLifecycle.Chain chain) {}
    private AppointmentContext appointment(Identity.Actor actor,long id){
        var candidate=db.one("SELECT "+APPOINTMENT+" FROM appointments WHERE appointment_id=?",id);long clinic=Store.number(candidate,"clinic_id"),dentist=Store.number(candidate,"dentist_id"),patient=Store.number(candidate,"patient_id");ReminderRules.authorize(actor,clinic,false);
        var chain=chains.candidate(id);
        var planCandidate=candidate.get("followup_plan_id")==null?null:hooks.candidate(Store.number(candidate,"followup_plan_id"));if(planCandidate!=null&&(planCandidate.owner().patient()!=patient||planCandidate.owner().source()==id))throw ApiError.conflict("FOLLOWUP_SOURCE_INVALID");
        var clinics=new ArrayList<>(planCandidate==null?List.of(clinic):List.of(clinic,planCandidate.owner().clinic()));var dentists=new ArrayList<>(planCandidate==null?List.of(dentist):List.of(dentist,planCandidate.owner().dentist()));if(chain!=null){clinics.addAll(chain.clinics());dentists.addAll(chain.dentists());}var resources=FollowupLinkRules.resources(clinics,dentists);boolean active=true;
        for(long c:resources.clinics()){var row=db.one("SELECT active FROM clinics WHERE clinic_id=? FOR UPDATE",c);if(c==clinic)active&=Store.enabled(row,"active");}for(long d:resources.dentists()){var row=db.one("SELECT active FROM dentists WHERE dentist_id=? FOR UPDATE",d);if(d==dentist)active&=Store.enabled(row,"active");}
        var p=patient(patient);active&=Store.enabled(p,"active");var coverage=bookingRules.lockCoverage(Store.number(candidate,"schedule_id"),clinic,dentist);var plan=planCandidate==null?null:hooks.lock(planCandidate);
        var appointmentArgs=new ArrayList<Object>(List.of(id,planCandidate==null?id:planCandidate.owner().source()));String extra=chains.include(chain,appointmentArgs);var rows=db.jdbc.queryForList("SELECT "+APPOINTMENT+" FROM appointments WHERE appointment_id IN (?,?)"+extra+" ORDER BY appointment_id FOR UPDATE",appointmentArgs.toArray());chains.verify(chain);var a=rows.stream().filter(r->Store.number(r,"appointment_id")==id).findFirst().orElseThrow(()->ApiError.conflict("RETRY_TRANSACTION"));
        for(String key:List.of("clinic_id","dentist_id","patient_id","schedule_id","followup_plan_id"))if(!Objects.equals(candidate.get(key),a.get(key)))throw ApiError.conflict("RETRY_TRANSACTION");
        if(plan!=null){var source=rows.stream().filter(r->Store.number(r,"appointment_id")==planCandidate.owner().source()).findFirst().orElseThrow(()->ApiError.conflict("FOLLOWUP_SOURCE_INVALID"));hooks.source(plan,source);}
        boolean covered=true;try{bookingRules.coverage(coverage,clinic,dentist,(Timestamp)a.get("scheduled_start"),(Timestamp)a.get("scheduled_end"));}catch(ApiError e){if(!e.code.equals("OUTSIDE_COVERAGE"))throw e;covered=false;}
        Instant start=((Timestamp)a.get("scheduled_start")).toInstant();String invalid=ReminderRules.appointmentValidity(active,a.get("appointment_status").toString(),Store.enabled(a,"attended"),a.get("treatment_status").toString(),start,db.now().toInstant());if(invalid==null&&!covered)invalid="SCHEDULE_COVERAGE_INVALID";
        return new AppointmentContext(new ReminderRules.Business(ReminderRules.Kind.APPOINTMENT,id,patient,clinic,invalid,start,null,(String)p.get("phone")),chain);
    }
    private ReminderRules.Business followup(Identity.Actor actor,long id){
        var candidate=hooks.candidate(id);var owner=candidate.owner();ReminderRules.authorize(actor,owner.clinic(),false);boolean active=Store.enabled(db.one("SELECT active FROM clinics WHERE clinic_id=? FOR UPDATE",owner.clinic()),"active");db.one("SELECT dentist_id FROM dentists WHERE dentist_id=? FOR UPDATE",owner.dentist());var patient=patient(owner.patient());active&=Store.enabled(patient,"active");var plan=hooks.lock(candidate);
        var rows=db.jdbc.queryForList("SELECT "+APPOINTMENT+" FROM appointments WHERE appointment_id=? OR followup_plan_id=? ORDER BY appointment_id FOR UPDATE",owner.source(),id);var source=rows.stream().filter(r->Store.number(r,"appointment_id")==owner.source()).findFirst().orElseThrow(()->ApiError.conflict("FOLLOWUP_SOURCE_INVALID"));hooks.source(plan,source);
        if(rows.stream().anyMatch(r->Store.number(r,"patient_id")!=owner.patient()))throw ApiError.conflict("FOLLOWUP_SOURCE_INVALID");boolean completed=rows.stream().anyMatch(r->Store.number(r,"appointment_id")!=owner.source()&&Store.enabled(r,"attended")&&"已到诊".equals(r.get("appointment_status"))&&"已完成".equals(r.get("treatment_status"))&&r.get("completed_at")!=null);
        return new ReminderRules.Business(ReminderRules.Kind.FOLLOWUP,id,owner.patient(),owner.clinic(),ReminderRules.followupValidity(active,plan.get("status").toString(),completed),null,LocalDate.parse(plan.get("suggested_date").toString()),(String)patient.get("phone"));
    }
    private boolean related(ReminderRules.Business b,Map<String,Object> m){return Store.number(m,"patient_id")==b.patient()&&Store.number(m,"clinic_id")==b.clinic()&&(b.kind()==ReminderRules.Kind.APPOINTMENT?Objects.equals(m.get("appointment_id"),b.id())&&m.get("followup_plan_id")==null:Objects.equals(m.get("followup_plan_id"),b.id())&&m.get("appointment_id")==null);}
    private ReminderRules.History history(ReminderRules.Business b,Map<String,Object> m){return new ReminderRules.History(Store.number(m,"message_id"),related(b,m),(String)m.get("channel"),((Timestamp)m.get("planned_at")).toInstant(),(String)m.get("recipient_snapshot"),(String)m.get("content"),(String)m.get("send_status"),m.get("sent_at")==null?null:((Timestamp)m.get("sent_at")).toInstant());}
    @Transactional
    public Object plan(Identity.Actor actor,long id,boolean followup,boolean apply){
        var appointment=followup?null:appointment(actor,id);var b=followup?followup(actor,id):appointment.business();var c=config(b.clinic());var chain=followup?null:appointment.chain();
        var messageIds=new ArrayList<Object>();String messageFilter;if(chain==null){messageFilter=(followup?"followup_plan_id":"appointment_id")+"=?";messageIds.add(id);}else{messageIds.addAll(chain.rows().keySet());messageFilter="appointment_id IN ("+String.join(",",Collections.nCopies(messageIds.size(),"?"))+")";}
        var messages=db.jdbc.queryForList("SELECT "+MESSAGE+" FROM reminder_messages WHERE "+messageFilter+" ORDER BY message_id FOR UPDATE",messageIds.toArray());
        var now=db.now().toInstant();ReminderRules.Result result;
        if(chain==null)result=ReminderRules.plan(b,settings(c),now,messages.stream().map(m->writer.history(b,m)).toList());else{var history=new ArrayList<ReminderLifecycleRules.Scoped>();for(var m:messages){long owner=Store.number(m,"appointment_id");var a=chain.rows().get(owner);var ownerBusiness=new ReminderRules.Business(ReminderRules.Kind.APPOINTMENT,owner,Store.number(a,"patient_id"),Store.number(a,"clinic_id"),null,null,null,null);history.add(new ReminderLifecycleRules.Scoped(owner,writer.history(ownerBusiness,m)));}var e=ReminderLifecycleRules.evaluate(b,settings(c),now,history,chain.complete()&&!chain.linked(),true);var issues=new ArrayList<>(e.result().issues());issues.addAll(e.extraIssues());result=new ReminderRules.Result(issues.stream().distinct().toList(),e.result().actions(),e.result().reconciliation());}
        Map<ReminderRules.Action,Long> assigned=apply?writer.apply(actor,b,result.actions(),messages,"单对象当前规则重建"):Map.of();
        db.audit(actor.id(),apply?"重建提醒":"预览提醒",followup?"followup_reminder_plan":"appointment_reminder_plan",id);
        var output=new LinkedHashMap<String,Object>();output.put("businessType",b.kind().name());output.put("businessId",Long.toString(id));output.put("clinicId",Long.toString(b.clinic()));output.put("asOf",Timestamp.from(now));output.put("issues",result.issues());output.put("requiresReconciliation",result.reconciliation());output.put("applied",apply);output.put("delivery","IN_APP_ONLY");output.put("batchRebuild","NOT_IMPLEMENTED");output.put("actionCount",result.actions().size());output.put("actionsTruncated",result.actions().size()>100);
        var actions=new ArrayList<Map<String,Object>>();for(var a:result.actions().stream().limit(100).toList()){var row=new LinkedHashMap<String,Object>();row.put("operation",a.operation());Long messageId=assigned.getOrDefault(a,a.messageId());row.put("messageId",messageId==null?null:Long.toString(messageId));row.put("channel",a.channel());row.put("plannedAt",Timestamp.from(a.plannedAt()));row.put("status",a.status());row.put("content",a.draft()==null?null:a.draft().content());row.put("recipientMasked",a.draft()==null?null:ReminderRules.mask(a.draft().recipient(),a.channel()));actions.add(row);}output.put("actions",actions);return Store.json(output);
    }
}
