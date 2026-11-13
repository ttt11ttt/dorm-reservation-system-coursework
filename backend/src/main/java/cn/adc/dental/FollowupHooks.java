package cn.adc.dental;

import java.sql.Timestamp;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Runs in caller's transaction; parent and plan locks must already precede appointment locks. */
@Component
class FollowupHooks {
    private final Store db;
    private final FollowupService followups;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    FollowupHooks(Store db,FollowupService followups) { this.db=db;this.followups=followups; }
    FollowupLinkRules.Plan value(Map<String,Object> p) { return new FollowupLinkRules.Plan(Store.number(p,"followup_plan_id"),new FollowupRules.Owner(Store.number(p,"patient_id"),Store.number(p,"dentist_id"),Store.number(p,"clinic_id"),Store.number(p,"source_appointment_id")),p.get("status").toString(),(Long)p.get("completed_appointment_id"),p.get("completed_at")==null?null:((Timestamp)p.get("completed_at")).toInstant()); }
    FollowupLinkRules.Plan candidate(long id) { return value(db.one("SELECT "+FollowupService.PLAN+" FROM followup_plans WHERE followup_plan_id=?",id)); }
    Map<String,Object> lock(FollowupLinkRules.Plan candidate) {
        var p=db.one("SELECT "+FollowupService.PLAN+" FROM followup_plans WHERE followup_plan_id=? FOR UPDATE",candidate.id());if(!value(p).owner().equals(candidate.owner()))throw ApiError.conflict("RETRY_TRANSACTION");return p;
    }
    void source(Map<String,Object> p,Map<String,Object> a) {
        var owner=new FollowupRules.Owner(Store.number(a,"patient_id"),Store.number(a,"dentist_id"),Store.number(a,"clinic_id"),Store.number(a,"appointment_id"));
        FollowupRules.source(new FollowupRules.Source(owner,Store.enabled(a,"attended"),a.get("appointment_status").toString(),a.get("treatment_status").toString(),a.get("completed_at")==null?null:((Timestamp)a.get("completed_at")).toInstant()),value(p).owner(),db.clock);
    }
    Long originalPlan(long appointmentId) {
        var rows=db.jdbc.queryForList("SELECT after_value FROM audit_logs WHERE object_type='appointment' AND object_id=? AND action='创建'",Long.toString(appointmentId));Long found=null;
        for(var row:rows)if(row.get("after_value")!=null){var root=JSON.readTree(row.get("after_value").toString());var v=root.get("requestedFollowupPlanId");if(v!=null&&!v.isNull()){long parsed=Store.parseId(v.asString());if(found!=null&&!found.equals(parsed))throw ApiError.conflict("IDEMPOTENCY_CONFLICT");found=parsed;}}
        return found;
    }
    record CompletionTransition(Map<String,Object> plan,FollowupLinkRules.Decision decision,FollowupRules.Snapshot before,long appointmentId) {}
    record MessageCleanup(int cancelled,int unresolved) {}
    static String reminderScheduling(Object outcome){return outcome==null?"NOT_IMPLEMENTED":"SEE_REMINDER_OUTCOME";}
    CompletionTransition prepareCompletion(Map<String,Object> p,Map<String,Object> a,boolean allowNew) {
        var clinical=new FollowupLinkRules.Clinical(Store.number(a,"appointment_id"),Store.number(a,"patient_id"),(Long)a.get("followup_plan_id"),Store.enabled(a,"attended"),a.get("appointment_status").toString(),a.get("treatment_status").toString(),a.get("completed_at")==null?null:((Timestamp)a.get("completed_at")).toInstant());
        var decision=FollowupLinkRules.completion(value(p),clinical,allowNew);return new CompletionTransition(p,decision,decision.updatePlan()?followups.snapshot(p):null,clinical.id());
    }
    Map<String,Object> applyCompletionState(CompletionTransition transition){
        var p=transition.plan();var decision=transition.decision();
        if(decision.updatePlan()) {
            var time=Timestamp.from(decision.completedAt());int changed=db.jdbc.update("UPDATE followup_plans SET status='已完成',completed_appointment_id=?,completed_at=?,updated_at=? WHERE followup_plan_id=? AND status='待复诊'",decision.completedId(),time,time,p.get("followup_plan_id"));if(changed!=1)throw ApiError.conflict("RETRY_TRANSACTION");
        }
        return db.one("SELECT "+FollowupService.PLAN+" FROM followup_plans WHERE followup_plan_id=?",p.get("followup_plan_id"));
    }
    Map<String,Object> finishCompletion(Identity.Actor actor,CompletionTransition transition,Map<String,Object> after,MessageCleanup cleanup){
        return finishCompletion(actor,transition,after,cleanup,null);
    }
    Map<String,Object> finishCompletion(Identity.Actor actor,CompletionTransition transition,Map<String,Object> after,MessageCleanup cleanup,Object reminderOutcome){
        var p=transition.plan();var decision=transition.decision();int cancelled=cleanup.cancelled(),unresolved=cleanup.unresolved();
        if(decision.updatePlan()){var auditAfter=new LinkedHashMap<String,Object>();auditAfter.put("plan",after);auditAfter.put("request",Map.of("operation","clinical-completion","appointmentId",Long.toString(transition.appointmentId())));auditAfter.put("cancelledReminderCount",cancelled);auditAfter.put("unresolvedReminderCount",unresolved);if(reminderOutcome!=null)auditAfter.put("reminderOutcome",reminderOutcome);db.audit(actor.id(),"复诊完成","followup_plan",Store.number(p,"followup_plan_id"),"关联就诊实际完成",transition.before(),auditAfter);}
        var result=new LinkedHashMap<String,Object>();result.put("planId",Long.toString(Store.number(p,"followup_plan_id")));result.put("status",decision.status());result.put("outcome",decision.outcome());result.put("completedAppointmentId",decision.completedId());result.put("completedAt",decision.completedAt()==null?null:Timestamp.from(decision.completedAt()));result.put("planReconciliationRequired",decision.reconciliation());result.put("cancelledReminderCount",cancelled);result.put("unresolvedReminderCount",unresolved);result.put("reminderReconciliationRequired",unresolved>0);result.put("reminderScheduling",reminderScheduling(reminderOutcome));return result;
    }
    Map<String,Object> replayCompletion(Identity.Actor actor,Map<String,Object> p,Map<String,Object> a){var transition=prepareCompletion(p,a,false);return finishCompletion(actor,transition,p,new MessageCleanup(0,0));}
}
