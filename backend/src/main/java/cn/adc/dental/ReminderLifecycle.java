package cn.adc.dental;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** A only. Public business services must pre-read Chain before acquiring any business locks. */
@Component
class ReminderLifecycle {
    static final String APP="appointment_id,patient_id,clinic_id,dentist_id,previous_appointment_id,followup_plan_id,scheduled_start,appointment_status,treatment_status,attended";
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final Store db;private final ReminderService reminders;private final ReminderMessageWriter writer;private final ReminderChainReader chains;
    ReminderLifecycle(Store db,ReminderService reminders,ReminderMessageWriter writer,ReminderChainReader chains){this.db=db;this.reminders=reminders;this.writer=writer;this.chains=chains;}
    record Chain(long origin,Map<Long,Map<String,Object>> rows,boolean complete,boolean linked) {
        List<Long> clinics(){return rows.values().stream().map(a->Store.number(a,"clinic_id")).distinct().sorted().toList();}
        List<Long> dentists(){return rows.values().stream().map(a->Store.number(a,"dentist_id")).distinct().sorted().toList();}
        Object identity(){return rows.values().stream().sorted(Comparator.comparingLong(a->Store.number(a,"appointment_id"))).map(a->Arrays.asList(a.get("appointment_id"),a.get("patient_id"),a.get("clinic_id"),a.get("dentist_id"),a.get("previous_appointment_id"),a.get("followup_plan_id"))).toList();}
    }
    Chain candidate(long id){return chains.candidate(id);}
    String include(Chain chain,List<Object> args){return chains.include(chain,args);}
    void verify(Chain before){chains.verify(before);}
    Object original(long id,String action){var audits=db.jdbc.queryForList("SELECT after_value FROM audit_logs WHERE object_type='appointment' AND object_id=? AND action=? ORDER BY happened_at DESC,audit_id DESC LIMIT 1",Long.toString(id),action);if(audits.isEmpty()||audits.getFirst().get("after_value")==null)return ReminderLifecycleRules.originalOutcome(null);var saved=JSON.readTree(audits.getFirst().get("after_value").toString()).get("reminderOutcome");return ReminderLifecycleRules.originalOutcome(saved==null||saved.isNull()?null:saved);}
    Object originalDetail(long id,String action,String field){var audits=db.jdbc.queryForList("SELECT after_value FROM audit_logs WHERE object_type='appointment' AND object_id=? AND action=? ORDER BY happened_at DESC,audit_id DESC LIMIT 1",Long.toString(id),action);if(audits.isEmpty()||audits.getFirst().get("after_value")==null)return null;var saved=JSON.readTree(audits.getFirst().get("after_value").toString()).get(field);return saved==null||saved.isNull()?null:saved;}
    Object attach(Object response,Object outcome){if(!(response instanceof Map<?,?> map))throw new IllegalStateException("business response must remain a map");var result=new LinkedHashMap<String,Object>();map.forEach((k,v)->result.put(k.toString(),v));result.put("reminderOutcome",outcome);return Store.json(result);}
    Map<String,Object> apply(Identity.Actor actor,Chain chain,long focus,Long generate,String phone,boolean resourcesValid){
        if(chain==null||chain.linked())return ReminderLifecycleRules.empty("NOT_CONNECTED");var ids=new TreeSet<>(chain.rows().keySet());ids.add(focus);var appointments=new TreeMap<Long,Map<String,Object>>();for(long id:ids){var a=db.one("SELECT "+APP+" FROM appointments WHERE appointment_id=?",id);if(a.get("followup_plan_id")!=null)return ReminderLifecycleRules.empty("NOT_CONNECTED");appointments.put(id,a);}
        long patient=Store.number(appointments.get(focus),"patient_id");if(appointments.values().stream().anyMatch(a->Store.number(a,"patient_id")!=patient))throw ApiError.conflict("RETRY_TRANSACTION");
        var settingsByClinic=new HashMap<Long,ReminderRules.Settings>();var clinics=new TreeSet<Long>();appointments.values().forEach(a->clinics.add(Store.number(a,"clinic_id")));for(long clinic:clinics)settingsByClinic.put(clinic,reminders.settings(reminders.config(clinic)));
        var args=new ArrayList<Object>(ids);var messages=db.jdbc.queryForList("SELECT "+ReminderMessageWriter.COLUMNS+" FROM reminder_messages WHERE appointment_id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") ORDER BY message_id FOR UPDATE",args.toArray());var now=db.now().toInstant();var history=new ArrayList<ReminderLifecycleRules.Scoped>();var businesses=new TreeMap<Long,ReminderRules.Business>();boolean complete=chain.complete();
        for(var a:appointments.values()){long id=Store.number(a,"appointment_id");Instant start=((Timestamp)a.get("scheduled_start")).toInstant();String invalid=ReminderRules.appointmentValidity(resourcesValid,a.get("appointment_status").toString(),Store.enabled(a,"attended"),a.get("treatment_status").toString(),start,now);if(!Objects.equals(generate,id)&&invalid==null)complete=false;var b=new ReminderRules.Business(ReminderRules.Kind.APPOINTMENT,id,patient,Store.number(a,"clinic_id"),invalid,start,null,phone);businesses.put(id,b);for(var m:messages)if(Objects.equals(m.get("appointment_id"),id))history.add(new ReminderLifecycleRules.Scoped(id,writer.history(b,m)));}
        var issues=new LinkedHashSet<String>();var counts=new HashMap<String,Integer>();boolean reconcile=!complete;int unresolved=(int)history.stream().filter(h->ReminderRules.unknown(h.history())).count();
        for(var b:businesses.values()){
            // An unrelated still-active row in a malformed lineage is retained, never silently cancelled.
            if(b.id()!=focus&&!Objects.equals(generate,b.id())&&b.invalidReason()==null){issues.add("CHAIN_OTHER_ACTIVE_APPOINTMENT");reconcile=true;continue;}
            var e=ReminderLifecycleRules.evaluate(b,settingsByClinic.get(b.clinic()),now,history,complete,Objects.equals(generate,b.id()));issues.addAll(e.result().issues());issues.addAll(e.extraIssues());reconcile|=e.result().reconciliation();writer.apply(actor,b,e.result().actions(),messages,"普通预约生命周期当前事实维护");for(var action:e.result().actions())counts.merge(action.operation(),1,Integer::sum);
        }
        if(!complete)issues.add("CHAIN_INCOMPLETE");boolean skipped=counts.getOrDefault("CREATE",0)+counts.getOrDefault("REUSE",0)+counts.getOrDefault("RESTORE",0)+counts.getOrDefault("CANCEL",0)==0;var out=new LinkedHashMap<String,Object>();out.put("scheduling",issues.isEmpty()?"APPLIED":skipped?"SKIPPED":"PARTIAL");out.put("issues",List.copyOf(issues));out.put("reconciliationRequired",reconcile);out.put("createdCount",counts.getOrDefault("CREATE",0));out.put("reusedCount",counts.getOrDefault("REUSE",0));out.put("restoredCount",counts.getOrDefault("RESTORE",0));out.put("cancelledCount",counts.getOrDefault("CANCEL",0));out.put("unresolvedCount",unresolved);out.put("delivery","NOT_IMPLEMENTED");return out;
    }
    Chain fresh(long id){var row=db.one("SELECT "+APP+" FROM appointments WHERE appointment_id=?",id);return new Chain(id,Map.of(id,row),true,false);}
}
