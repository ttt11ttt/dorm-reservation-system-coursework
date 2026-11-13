package cn.adc.dental;

import java.util.*;
import org.springframework.stereotype.Component;

/** B preparatory reader. Does not acquire locks or enable any lifecycle caller. */
@Component
class ReminderFollowupContext {
    static final String APP=ReminderLifecycle.APP+",schedule_id,scheduled_end,arrived_at,completed_at";
    record Candidate(Map<String,Object> plan,SortedMap<Long,Map<String,Object>> appointments) {
        long id(){return Store.number(plan,"followup_plan_id");}
        List<Long> clinics(){var ids=new TreeSet<Long>();ids.add(Store.number(plan,"clinic_id"));appointments.values().forEach(a->ids.add(Store.number(a,"clinic_id")));return List.copyOf(ids);}
        List<Long> dentists(){var ids=new TreeSet<Long>();ids.add(Store.number(plan,"dentist_id"));appointments.values().forEach(a->ids.add(Store.number(a,"dentist_id")));return List.copyOf(ids);}
        long patient(){return Store.number(plan,"patient_id");}
        Object identity(){var owner=List.of(plan.get("followup_plan_id"),plan.get("patient_id"),plan.get("clinic_id"),plan.get("dentist_id"),plan.get("source_appointment_id"));var related=appointments.values().stream().map(a->Arrays.asList(a.get("appointment_id"),a.get("patient_id"),a.get("clinic_id"),a.get("dentist_id"),a.get("schedule_id"),a.get("previous_appointment_id"),a.get("followup_plan_id"))).toList();return Map.of("owner",owner,"appointments",related);}
    }
    private final Store db;
    ReminderFollowupContext(Store db){this.db=db;}
    Candidate candidate(long id){
        var p=db.one("SELECT "+FollowupService.PLAN+" FROM followup_plans WHERE followup_plan_id=?",id);var rows=db.jdbc.queryForList("SELECT "+APP+" FROM appointments WHERE appointment_id=? OR followup_plan_id=? ORDER BY appointment_id",p.get("source_appointment_id"),id);var appointments=new TreeMap<Long,Map<String,Object>>();
        for(var a:rows){if(Store.number(a,"patient_id")!=Store.number(p,"patient_id"))throw ApiError.conflict("FOLLOWUP_SOURCE_INVALID");appointments.put(Store.number(a,"appointment_id"),a);}if(!appointments.containsKey(Store.number(p,"source_appointment_id")))throw ApiError.conflict("FOLLOWUP_SOURCE_INVALID");return new Candidate(Collections.unmodifiableMap(new LinkedHashMap<>(p)),Collections.unmodifiableSortedMap(appointments));
    }
    String include(Candidate candidate,List<Object> args){args.add(candidate.id());args.addAll(candidate.appointments().keySet());return " OR followup_plan_id=? OR appointment_id IN ("+String.join(",",Collections.nCopies(candidate.appointments().size(),"?"))+")";}
    static boolean lockedIdentityMatches(Candidate before,Map<String,Object> lockedPlan,List<Map<String,Object>> lockedRows){
        var related=new TreeMap<Long,Map<String,Object>>();long source=Store.number(lockedPlan,"source_appointment_id");
        for(var row:lockedRows){long id=Store.number(row,"appointment_id");if(id==source||Objects.equals(row.get("followup_plan_id"),before.id()))related.put(id,row);}
        return Objects.equals(before.identity(),new Candidate(lockedPlan,related).identity());
    }
    void verify(Candidate before,Map<String,Object> lockedPlan,List<Map<String,Object>> lockedRows){
        // Use rows returned by the caller's single ordered FOR UPDATE query, not an RR snapshot reread.
        if(!lockedIdentityMatches(before,lockedPlan,lockedRows))throw ApiError.conflict("RETRY_TRANSACTION");
    }
}
