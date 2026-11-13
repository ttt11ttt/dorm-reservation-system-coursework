package cn.adc.dental;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Component;

/** Linked appointment and followup maintenance in the caller's business transaction.
 * Caller supplies post-mutation rows after all parent/plan/appointment locks and identity revalidation.
 * focusValid combines locked current parents and actual opening/schedule coverage for a
 * still-booked focus. Structural validation requires all its predecessors to be rescheduled.
 */
@Component
class ReminderLinkedMaintenance {
    private final Store db;private final ReminderBatchMaintenance batch;private final ReminderMessageWriter writer;
    ReminderLinkedMaintenance(Store db,ReminderBatchMaintenance batch,ReminderMessageWriter writer){this.db=db;this.batch=batch;this.writer=writer;}
    ReminderBatchMaintenance.Detailed apply(Identity.Actor actor,long focus,Map<String,Object> plan,List<Map<String,Object>> rows,boolean focusValid,boolean planActive,String phone){
        long patient=Store.number(plan,"patient_id"),planId=Store.number(plan,"followup_plan_id"),source=Store.number(plan,"source_appointment_id");
        rows=rows.stream().filter(a->Store.number(a,"appointment_id")==source||Objects.equals(a.get("followup_plan_id"),planId)).toList();
        if(rows.stream().noneMatch(a->Store.number(a,"appointment_id")==source)||rows.stream().anyMatch(a->Store.number(a,"patient_id")!=patient))throw ApiError.conflict("RETRY_TRANSACTION");
        var links=rows.stream().filter(a->Store.number(a,"appointment_id")!=source).map(a->new ReminderLifecycleRules.Link(Store.number(a,"appointment_id"),patient,a.get("previous_appointment_id")==null?null:Store.number(a,"previous_appointment_id"),a.get("followup_plan_id")==null?null:Store.number(a,"followup_plan_id"),a.get("appointment_status").toString())).toList();
        var chain=ReminderLinkedFacts.groups(patient,planId,links).get(focus);if(chain==null)throw ApiError.conflict("RETRY_TRANSACTION");
        var current=rows.stream().filter(a->chain.contains(Store.number(a,"appointment_id"))).toList();
        // Only this event's complete lineage plus plan: another independent appointment is not maintained.
        var scope=new ArrayList<ReminderRules.Business>();for(var a:current)scope.add(new ReminderRules.Business(ReminderRules.Kind.APPOINTMENT,Store.number(a,"appointment_id"),patient,Store.number(a,"clinic_id"),null,null,null,phone));
        scope.add(new ReminderRules.Business(ReminderRules.Kind.FOLLOWUP,planId,patient,Store.number(plan,"clinic_id"),null,null,null,phone));
        var settings=batch.settingsBeforeMessages(scope);var locked=batch.lockMessages(scope);var now=db.now().toInstant();
        var appointments=new ArrayList<ReminderLinkedFacts.Appointment>();var businesses=new TreeMap<Long,ReminderRules.Business>();
        boolean complete=rows.stream().anyMatch(a->Store.number(a,"appointment_id")!=source&&Store.enabled(a,"attended")&&"已到诊".equals(a.get("appointment_status"))&&"已完成".equals(a.get("treatment_status"))&&a.get("completed_at")!=null);
        for(var a:current){
            long id=Store.number(a,"appointment_id");
            var start=((Timestamp)a.get("scheduled_start")).toInstant();String status=a.get("appointment_status").toString(),treatment=a.get("treatment_status").toString();boolean attended=Store.enabled(a,"attended");
            // Every predecessor in a valid chain is 已改约; its terminal state needs no coverage lookup.
            boolean needsCurrentCoverage=id==focus&&"待到诊".equals(status)&&!attended&&"未开始".equals(treatment);
            var business=new ReminderRules.Business(ReminderRules.Kind.APPOINTMENT,id,patient,Store.number(a,"clinic_id"),ReminderRules.appointmentValidity(!needsCurrentCoverage||focusValid,status,attended,treatment,start,now),start,null,phone);
            var link=new ReminderLifecycleRules.Link(id,patient,a.get("previous_appointment_id")==null?null:Store.number(a,"previous_appointment_id"),planId,status);
            appointments.add(new ReminderLinkedFacts.Appointment(link,business));businesses.put(id,business);
        }
        var followup=new ReminderRules.Business(ReminderRules.Kind.FOLLOWUP,planId,patient,Store.number(plan,"clinic_id"),ReminderRules.followupValidity(planActive,plan.get("status").toString(),complete),null,LocalDate.parse(plan.get("suggested_date").toString()),phone);
        var history=new ArrayList<ReminderLifecycleRules.Scoped>();
        for(var message:locked){var id=message.get("appointment_id");if(id instanceof Number n&&businesses.containsKey(n.longValue()))history.add(new ReminderLifecycleRules.Scoped(n.longValue(),writer.history(businesses.get(n.longValue()),message)));}
        return batch.applyDetailedLocked(actor,ReminderLinkedFacts.facts(followup,appointments,history),locked,settings,now);
    }
}
