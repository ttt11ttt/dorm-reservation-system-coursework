package cn.adc.dental;

import java.util.*;
import org.springframework.stereotype.Component;

/** Read candidates before any parent locks; verify only after all candidate appointments are locked. */
@Component
class ReminderChainReader {
    private final Store db;
    ReminderChainReader(Store db){this.db=db;}
    ReminderLifecycle.Chain candidate(long id){
        var initial=db.one("SELECT "+ReminderLifecycle.APP+" FROM appointments WHERE appointment_id=?",id);if(initial.get("followup_plan_id")!=null)return null;long patient=Store.number(initial,"patient_id");var rows=new TreeMap<Long,Map<String,Object>>();var todo=new TreeSet<Long>();todo.add(id);boolean complete=true,linked=false;
        while(!todo.isEmpty()){long next=todo.pollFirst();if(rows.containsKey(next))continue;if(rows.size()>=ReminderLifecycleRules.CHAIN_LIMIT){complete=false;break;}var found=db.jdbc.queryForList("SELECT "+ReminderLifecycle.APP+" FROM appointments WHERE appointment_id=?",next);if(found.isEmpty()){complete=false;continue;}var row=found.getFirst();if(Store.number(row,"patient_id")!=patient){complete=false;continue;}if(row.get("followup_plan_id")!=null){complete=false;linked=true;continue;}rows.put(next,row);if(row.get("previous_appointment_id")!=null)todo.add(Store.number(row,"previous_appointment_id"));var children=db.jdbc.queryForList("SELECT appointment_id FROM appointments WHERE previous_appointment_id=? ORDER BY appointment_id LIMIT 2",next);if(children.size()>1||!children.isEmpty()&&!"已改约".equals(row.get("appointment_status")))complete=false;children.forEach(c->todo.add(Store.number(c,"appointment_id")));}
        var links=rows.values().stream().map(row->new ReminderLifecycleRules.Link(Store.number(row,"appointment_id"),Store.number(row,"patient_id"),row.get("previous_appointment_id")==null?null:Store.number(row,"previous_appointment_id"),row.get("followup_plan_id")==null?null:Store.number(row,"followup_plan_id"),row.get("appointment_status").toString())).toList();complete&=ReminderLifecycleRules.validChain(links,patient);
        return new ReminderLifecycle.Chain(id,Collections.unmodifiableMap(rows),complete,linked);
    }
    String include(ReminderLifecycle.Chain chain,List<Object> args){if(chain==null||chain.rows().isEmpty())return "";args.addAll(chain.rows().keySet());return " OR appointment_id IN ("+String.join(",",Collections.nCopies(chain.rows().size(),"?"))+")";}
    void verify(ReminderLifecycle.Chain before){if(before==null)return;var current=candidate(before.origin());if(current==null||current.complete()!=before.complete()||current.linked()!=before.linked()||!Objects.equals(current.identity(),before.identity()))throw ApiError.conflict("RETRY_TRANSACTION");}
}
