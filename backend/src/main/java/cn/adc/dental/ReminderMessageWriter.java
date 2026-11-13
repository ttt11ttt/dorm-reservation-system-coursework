package cn.adc.dental;

import java.sql.Timestamp;
import java.util.*;
import org.springframework.stereotype.Component;

/** Caller owns the transaction and all parent/appointment/message locks. Never reacquires a parent. */
@Component
class ReminderMessageWriter {
    static final String COLUMNS="message_id,patient_id,clinic_id,appointment_id,followup_plan_id,reminder_kind,planned_at,channel,recipient_snapshot,content,send_status,sent_at";
    private final Store db;
    ReminderMessageWriter(Store db){this.db=db;}
    ReminderRules.History history(ReminderRules.Business b,Map<String,Object> m){
        boolean appointment=b.kind()==ReminderRules.Kind.APPOINTMENT;
        boolean related=Store.number(m,"patient_id")==b.patient()&&Store.number(m,"clinic_id")==b.clinic()&&Objects.equals(m.get(appointment?"appointment_id":"followup_plan_id"),b.id())&&m.get(appointment?"followup_plan_id":"appointment_id")==null&&Objects.equals(m.get("reminder_kind"),appointment?"到诊提醒":"复诊提醒");
        return new ReminderRules.History(Store.number(m,"message_id"),related,(String)m.get("channel"),((Timestamp)m.get("planned_at")).toInstant(),(String)m.get("recipient_snapshot"),(String)m.get("content"),(String)m.get("send_status"),m.get("sent_at")==null?null:((Timestamp)m.get("sent_at")).toInstant());
    }
    Map<ReminderRules.Action,Long> apply(Identity.Actor actor,ReminderRules.Business b,List<ReminderRules.Action> actions,List<Map<String,Object>> locked,String reason){
        var assigned=new HashMap<ReminderRules.Action,Long>();boolean appointment=b.kind()==ReminderRules.Kind.APPOINTMENT;
        for(var a:actions){if(a.operation().equals("CREATE")){long id=db.id();var d=a.draft();db.jdbc.update("INSERT INTO reminder_messages(message_id,patient_id,clinic_id,appointment_id,followup_plan_id,reminder_kind,planned_at,channel,recipient_snapshot,content,send_status) VALUES(?,?,?,?,?,?,?,?,?,?,'待发送')",id,b.patient(),b.clinic(),appointment?b.id():null,appointment?null:b.id(),appointment?"到诊提醒":"复诊提醒",Timestamp.from(d.plannedAt()),d.channel(),d.recipient(),d.content());assigned.put(a,id);db.audit(actor.id(),"新增待发计划","reminder_message",id,reason,null,db.one("SELECT "+COLUMNS+" FROM reminder_messages WHERE message_id=?",id));}
            else if(Set.of("CANCEL","RESTORE").contains(a.operation())){var before=locked.stream().filter(m->Store.number(m,"message_id")==a.messageId()).findFirst().orElseThrow(()->ApiError.conflict("RETRY_TRANSACTION"));if(!history(b,before).related())throw ApiError.conflict("RETRY_TRANSACTION");int changed=db.jdbc.update("UPDATE reminder_messages SET send_status=? WHERE message_id=? AND send_status=? AND sent_at IS NULL",a.status(),a.messageId(),a.operation().equals("CANCEL")?"待发送":"已取消");if(changed!=1)throw ApiError.conflict("RETRY_TRANSACTION");var after=new LinkedHashMap<>(before);after.put("send_status",a.status());db.audit(actor.id(),a.operation().equals("CANCEL")?"撤销旧待发":"恢复未发计划","reminder_message",a.messageId(),reason,before,after);}
        }return assigned;
    }
}
