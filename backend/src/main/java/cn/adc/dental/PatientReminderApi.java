package cn.adc.dental;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/patient/reminders")
class PatientReminderApi {
    private final Store db;
    private final Identity identity;

    PatientReminderApi(Store db,Identity identity){
        this.db=db;
        this.identity=identity;
    }

    @Scheduled(fixedDelay=60000,initialDelay=60000)
    void deliver(){
        var now=db.now();
        var due=db.jdbc.queryForList("""
            SELECT message_id FROM reminder_messages
            WHERE channel='站内' AND send_status='待发送' AND planned_at<=?
            ORDER BY planned_at,message_id LIMIT 100
            """,now);
        for(var row:due){
            // Publishing an inbox item and recording its delivery are the same database write.
            db.jdbc.update("""
                UPDATE reminder_messages m
                SET m.sent_at=CASE WHEN EXISTS(SELECT 1 FROM patients p WHERE p.patient_id=m.patient_id AND p.active=1) AND (
                    EXISTS(SELECT 1 FROM appointments a WHERE a.appointment_id=m.appointment_id
                        AND a.appointment_status='待到诊' AND a.scheduled_start>?)
                    OR EXISTS(SELECT 1 FROM followup_plans f WHERE f.followup_plan_id=m.followup_plan_id AND f.status='待复诊')
                  ) THEN ? ELSE NULL END,
                  m.send_status=IF(m.sent_at IS NULL,'已取消','已发送'),
                  m.send_result=IF(m.sent_at IS NULL,'提醒已失效','已投递至本人消息列表')
                WHERE m.message_id=? AND m.channel='站内' AND m.send_status='待发送'
                  AND m.sent_at IS NULL AND m.planned_at<=?
                """,now,now,Store.number(row,"message_id"),now);
        }
    }

    @GetMapping
    Object inbox(@RequestHeader(value="Authorization",required=false) String header){
        var actor=identity.require(header);
        actor.patient();
        return Store.json(db.jdbc.queryForList("""
            SELECT m.message_id,m.reminder_kind,m.content,m.sent_at,c.name AS clinic_name
            FROM reminder_messages m JOIN clinics c ON c.clinic_id=m.clinic_id
            WHERE m.patient_id=? AND m.channel='站内' AND m.send_status='已发送' AND m.sent_at IS NOT NULL
            ORDER BY m.sent_at DESC,m.message_id DESC LIMIT 100
            """,actor.patientId()));
    }
}
