package cn.adc.dental;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import tools.jackson.databind.json.JsonMapper;

/** Bounded migration policy only; no database, delivery or transaction simulation. */
final class ReminderMigrationRules {
    static final int BUSINESS_LIMIT=50,HISTORY_LIMIT=500,RESOURCE_LIMIT=200,AUDIT_BYTES=48*1024;
    static final Duration TTL=Duration.ofMinutes(5);
    private static final JsonMapper JSON=JsonMapper.builder().build();
    record Request(String migrationId,String ruleId,ReminderRules.Snapshot expected,Integer appointmentLeadMinutes,Integer followupLeadDays,String followupSendTime,Boolean inAppEnabled,Boolean smsEnabled,String reason) {
        @JsonAnySetter void unknown(String key,Object value){throw ApiError.bad();}
    }
    record Commit(String previewDigest){@JsonAnySetter void unknown(String key,Object value){throw ApiError.bad();}}
    record Binding(String clinicId,String actorId,String migrationId,ReminderRules.Save request) {}
    record Evaluation(ReminderRules.Result result,boolean blocked,List<Map<String,Object>> semantic) {}
    record Lock(String table,long id) {}
    static final List<String> LOCK_CATEGORIES=List.of("clinics","dentists","patients","clinic_openings","dentist_schedules","followup_plans","appointments","reminder_messages");
    private ReminderMigrationRules() {}
    static Binding bind(long clinic,long actor,Request r){
        Store.parseId(r.migrationId());Store.parseId(r.ruleId());ReminderRules.expected(r.expected());Store.required(r.reason(),1000);
        if(r.expected()==null||!r.ruleId().equals(r.expected().ruleId())||!Long.toString(clinic).equals(r.expected().clinicId()))throw ApiError.bad();
        var s=ReminderRules.settings(new ReminderRules.Save(r.ruleId(),r.expected(),r.appointmentLeadMinutes(),r.followupLeadDays(),r.followupSendTime(),r.inAppEnabled(),r.smsEnabled(),r.reason()));ReminderRules.settings(s);
        return new Binding(Long.toString(clinic),Long.toString(actor),r.migrationId(),new ReminderRules.Save(r.ruleId(),r.expected(),s.appointmentLeadMinutes(),s.followupLeadDays(),s.followupSendTime(),s.inAppEnabled(),s.smsEnabled(),r.reason()));
    }
    static void limit(String dimension,int count,int max){if(count>max)throw new ApiError(413,"MIGRATION_SCOPE_TOO_LARGE",dimension+"超过安全上限"+max);}
    static int businessCount(Collection<String> writable,Collection<Long> guardAppointments){var refs=new HashSet<>(writable);guardAppointments.forEach(id->refs.add("APPOINTMENT:"+id));limit("业务对象含只读链",refs.size(),BUSINESS_LIMIT);return refs.size();}
    static void localAction(long clinic,ReminderRules.Business b,ReminderRules.Action action,Long storedClinic,boolean related){if(!Set.of("CREATE","CANCEL","RESTORE").contains(action.operation()))return;if(b.clinic()!=clinic||!action.operation().equals("CREATE")&&(storedClinic==null||storedClinic!=clinic||!related))throw ApiError.conflict("MIGRATION_ACTION_SCOPE_INVALID");}
    static void auditSize(Object value){if(value!=null&&Store.encode(value).getBytes(StandardCharsets.UTF_8).length>AUDIT_BYTES)throw new ApiError(413,"MIGRATION_AUDIT_TOO_LARGE","审计内容超过48KiB安全上限");}
    static void digestInput(String value){if(value==null||!value.matches("[0-9a-f]{64}"))throw ApiError.bad();}
    static void fresh(Instant created,Instant now){if(now.isBefore(created)||!now.isBefore(created.plus(TTL)))throw ApiError.conflict("MIGRATION_PREVIEW_EXPIRED");}
    static void same(Object expected,Object current,String code){if(!canonical(expected).equals(canonical(current)))throw ApiError.conflict(code);}
    static List<Lock> lockOrder(Map<String,? extends Collection<Long>> resources){
        if(!LOCK_CATEGORIES.containsAll(resources.keySet()))throw new IllegalArgumentException("unknown lock category");var result=new ArrayList<Lock>();for(String category:LOCK_CATEGORIES){var ids=resources.get(category);if(ids!=null)ids.stream().distinct().sorted().forEach(id->result.add(new Lock(category,id)));}return List.copyOf(result);
    }
    private static Object ordered(Object v){if(v instanceof Map<?,?> m){var sorted=new TreeMap<String,Object>();m.forEach((k,x)->sorted.put(k.toString(),ordered(x)));return sorted;}if(v instanceof List<?> l)return l.stream().map(ReminderMigrationRules::ordered).toList();return v;}
    static String canonical(Object value){return JSON.writeValueAsString(ordered(JSON.readValue(Store.encode(value),Object.class)));}
    static String digest(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(value).getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    static Evaluation evaluate(ReminderRules.Business b,ReminderRules.Settings s,Instant now,List<ReminderRules.History> history){
        var original=ReminderRules.plan(b,s,now,history);var issues=new ArrayList<>(original.issues());var actions=new ArrayList<>(original.actions());
        boolean cleared=b.kind()==ReminderRules.Kind.APPOINTMENT?s.appointmentLeadMinutes()==null:s.followupLeadDays()==null;
        if(cleared||!s.inAppEnabled()&&!s.smsEnabled()){
            issues.removeAll(List.of("APPOINTMENT_LEAD_MISSING","FOLLOWUP_CONFIG_MISSING","CHANNELS_DISABLED"));issues.add("EXPLICIT_UNSCHEDULED");
        }else if(issues.contains("SMS_RECIPIENT_MISSING")){
            // A sent content can suppress this channel without inventing any recipient.
            var probe=ReminderRules.plan(b,new ReminderRules.Settings(s.appointmentLeadMinutes(),s.followupLeadDays(),s.followupSendTime(),true,false),now,List.of());
            var content=probe.actions().stream().filter(a->a.draft()!=null).map(a->a.draft().content()).findFirst();
            var sent=content.isEmpty()?Optional.<ReminderRules.History>empty():history.stream().filter(h->ReminderRules.sent(h)&&h.channel().equals("短信")&&h.content().equals(content.get())).min(Comparator.comparingLong(ReminderRules.History::id));
            if(sent.isPresent()){issues.remove("SMS_RECIPIENT_MISSING");var h=sent.get();actions.replaceAll(a->Objects.equals(a.messageId(),h.id())?new ReminderRules.Action("SENT_SUPPRESSED",h.id(),h.channel(),h.plannedAt(),h.status(),null):a);}
        }
        boolean blocked=original.reconciliation()||issues.stream().anyMatch(i->Set.of("SMS_RECIPIENT_MISSING","PLANNED_TIME_OUT_OF_RANGE","RULE_MISSING","FOLLOWUP_COMPLETION_RECONCILIATION_REQUIRED").contains(i));
        var result=new ReminderRules.Result(List.copyOf(issues),List.copyOf(actions),original.reconciliation());
        return finish(result,blocked);
    }
    static Evaluation evaluateChain(ReminderRules.Business b,ReminderRules.Settings s,Instant now,List<ReminderRules.History> own,List<ReminderLifecycleRules.Scoped> chain,boolean complete){
        return evaluateChain(b,s,now,own,chain,complete,Set.of(b.id()));
    }
    static Evaluation evaluateChain(ReminderRules.Business b,ReminderRules.Settings s,Instant now,List<ReminderRules.History> own,List<ReminderLifecycleRules.Scoped> chain,boolean complete,Set<Long> writableAppointments){
        var base=evaluate(b,s,now,own);var guarded=ReminderLifecycleRules.guard(b,base.result(),chain,complete,true);var issues=new ArrayList<>(guarded.result().issues());issues.addAll(guarded.extraIssues());
        var plannedChannels=new HashSet<String>();guarded.result().actions().stream().filter(a->Set.of("CREATE","REUSE","RESTORE").contains(a.operation())).forEach(a->plannedChannels.add(a.channel()));var pendingGuard=new HashSet<String>();chain.stream().filter(h->!writableAppointments.contains(h.appointmentId())&&ReminderRules.pending(h.history())&&plannedChannels.contains(h.history().channel())).forEach(h->pendingGuard.add(h.history().channel()));
        var actions=new ArrayList<ReminderRules.Action>();for(var a:guarded.result().actions()){if(pendingGuard.contains(a.channel())&&Set.of("CREATE","REUSE","RESTORE").contains(a.operation())){if(a.operation().equals("REUSE"))actions.add(new ReminderRules.Action("CANCEL",a.messageId(),a.channel(),a.plannedAt(),"已取消",null));}else actions.add(a);}pendingGuard.stream().sorted().forEach(channel->issues.add("CHAIN_GUARD_PENDING_REQUIRES_OWNER:"+channel));
        if(issues.contains("SMS_RECIPIENT_MISSING")){
            var probe=ReminderRules.plan(b,new ReminderRules.Settings(s.appointmentLeadMinutes(),s.followupLeadDays(),s.followupSendTime(),true,false),now,List.of());var content=probe.actions().stream().filter(a->a.draft()!=null).map(a->a.draft().content()).findFirst();
            if(content.isPresent()&&chain.stream().anyMatch(h->ReminderRules.sent(h.history())&&h.history().channel().equals("短信")&&h.history().content().equals(content.get()))){issues.remove("SMS_RECIPIENT_MISSING");issues.add("CHAIN_SENT_SUPPRESSED:短信");}
        }
        var result=new ReminderRules.Result(issues.stream().distinct().toList(),List.copyOf(actions),guarded.result().reconciliation()||!pendingGuard.isEmpty());boolean blocked=result.reconciliation()||issues.stream().anyMatch(i->Set.of("SMS_RECIPIENT_MISSING","PLANNED_TIME_OUT_OF_RANGE","RULE_MISSING","FOLLOWUP_COMPLETION_RECONCILIATION_REQUIRED").contains(i));return finish(result,blocked);
    }
    private static Evaluation finish(ReminderRules.Result result,boolean blocked){
        var semantic=new ArrayList<Map<String,Object>>();
        for(var a:result.actions()){var row=new LinkedHashMap<String,Object>();row.put("operation",a.operation());row.put("messageId",a.messageId()==null?null:Long.toString(a.messageId()));row.put("channel",a.channel());row.put("status",a.status());row.put("plannedAt",a.operation().equals("CREATE")&&a.draft().immediate()?"AT_COMMIT":a.plannedAt().toString());row.put("timeMode",a.draft()==null?null:a.draft().immediate()?"IMMEDIATE":"FUTURE");row.put("contentDigest",a.draft()==null?null:digest(a.draft().content()));row.put("recipientDigest",a.draft()==null?null:digest(a.draft().recipient()));semantic.add(row);}
        return new Evaluation(result,blocked,List.copyOf(semantic));
    }
}
