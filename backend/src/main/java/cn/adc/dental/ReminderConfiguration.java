package cn.adc.dental;

import java.util.*;
import org.springframework.stereotype.Component;

/** Current rule read. Business writers own the clinic gate before calling this method. */
@Component
class ReminderConfiguration {
    private final Store db;
    ReminderConfiguration(Store db){this.db=db;}
    Map<String,Object> config(long clinic){var rows=db.jdbc.queryForList("SELECT rule_id,clinic_id,appointment_lead_minutes,followup_lead_days,followup_send_time,in_app_enabled,sms_enabled,updated_by,updated_at FROM clinic_booking_rules WHERE clinic_id=? FOR SHARE",clinic);return rows.isEmpty()?null:rows.getFirst();}
    static ReminderRules.Settings settings(Map<String,Object> c){return c==null?null:new ReminderRules.Settings((Integer)c.get("appointment_lead_minutes"),(Integer)c.get("followup_lead_days"),ReminderRules.canonicalTime(c.get("followup_send_time")==null?null:c.get("followup_send_time").toString()),Store.enabled(c,"in_app_enabled"),Store.enabled(c,"sms_enabled"));}
}
