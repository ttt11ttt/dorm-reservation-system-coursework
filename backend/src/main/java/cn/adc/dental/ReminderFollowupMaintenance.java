package cn.adc.dental;

import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Component;

/** Followup-only B slice. All source/linked appointment and responsible parent locks precede this call. */
@Component
class ReminderFollowupMaintenance {
    private final Store db;private final ReminderBatchMaintenance batch;
    ReminderFollowupMaintenance(Store db,ReminderBatchMaintenance batch){this.db=db;this.batch=batch;}
    Map<String,Object> apply(Identity.Actor actor,Map<String,Object> plan,List<Map<String,Object>> appointments,boolean active,String phone){
        long id=Store.number(plan,"followup_plan_id"),patient=Store.number(plan,"patient_id"),source=Store.number(plan,"source_appointment_id");if(appointments.stream().anyMatch(a->Store.number(a,"patient_id")!=patient))throw ApiError.conflict("FOLLOWUP_SOURCE_INVALID");
        boolean actualComplete=appointments.stream().anyMatch(a->Store.number(a,"appointment_id")!=source&&Store.enabled(a,"attended")&&"已到诊".equals(a.get("appointment_status"))&&"已完成".equals(a.get("treatment_status"))&&a.get("completed_at")!=null);
        var business=new ReminderRules.Business(ReminderRules.Kind.FOLLOWUP,id,patient,Store.number(plan,"clinic_id"),ReminderRules.followupValidity(active,plan.get("status").toString(),actualComplete),null,LocalDate.parse(plan.get("suggested_date").toString()),phone);var facts=List.of(new ReminderBatchMaintenance.Fact(business,List.of(),true));var settings=batch.settingsBeforeMessages(List.of(business));var locked=batch.lockMessages(List.of(business));return batch.applyLocked(actor,facts,locked,settings,db.now().toInstant());
    }
}
