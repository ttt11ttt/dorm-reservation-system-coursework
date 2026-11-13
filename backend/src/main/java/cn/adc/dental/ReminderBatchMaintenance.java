package cn.adc.dental;

import java.util.*;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Applies one appointment/plan batch in the caller's locked business transaction. */
@Component
class ReminderBatchMaintenance {
    record Fact(ReminderRules.Business business,List<ReminderLifecycleRules.Scoped> chain,boolean complete) {}
    record Detailed(Map<String,Object> outcome,int followupCancelled,int followupUnresolved) {}
    private final Store db;private final ReminderConfiguration configuration;private final ReminderMessageWriter writer;
    ReminderBatchMaintenance(Store db,ReminderConfiguration configuration,ReminderMessageWriter writer){this.db=db;this.configuration=configuration;this.writer=writer;}
    List<Map<String,Object>> lockMessages(Collection<ReminderRules.Business> businesses){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("batch requires caller transaction and parent locks");
        var appointmentIds=new TreeSet<Long>();var planIds=new TreeSet<Long>();for(var b:businesses)(b.kind()==ReminderRules.Kind.APPOINTMENT?appointmentIds:planIds).add(b.id());if(appointmentIds.isEmpty()&&planIds.isEmpty())return List.of();
        var where=new ArrayList<String>();var args=new ArrayList<Object>();if(!appointmentIds.isEmpty()){where.add("appointment_id IN ("+String.join(",",Collections.nCopies(appointmentIds.size(),"?"))+")");args.addAll(appointmentIds);}if(!planIds.isEmpty()){where.add("followup_plan_id IN ("+String.join(",",Collections.nCopies(planIds.size(),"?"))+")");args.addAll(planIds);}
        return db.jdbc.queryForList("SELECT "+ReminderMessageWriter.COLUMNS+" FROM reminder_messages WHERE "+String.join(" OR ",where)+" ORDER BY message_id FOR UPDATE",args.toArray());
    }
    Map<Long,ReminderRules.Settings> settingsBeforeMessages(Collection<ReminderRules.Business> businesses){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("rules require caller transaction and clinic locks");
        var settings=new HashMap<Long,ReminderRules.Settings>();var clinics=new TreeSet<Long>();for(var b:businesses)clinics.add(b.clinic());
        for(long clinic:clinics)settings.put(clinic,ReminderConfiguration.settings(configuration.config(clinic)));
        return Collections.unmodifiableMap(settings);
    }
    Map<String,Object> applyLocked(Identity.Actor actor,List<Fact> facts,List<Map<String,Object>> locked,Map<Long,ReminderRules.Settings> settings,Instant now){
        return applyDetailedLocked(actor,facts,locked,settings,now).outcome();
    }
    Detailed applyDetailedLocked(Identity.Actor actor,List<Fact> facts,List<Map<String,Object>> locked,Map<Long,ReminderRules.Settings> settings,Instant now){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("batch requires caller transaction");var inputs=new ArrayList<ReminderBatchRules.Input>();
        for(var fact:facts){var b=fact.business();if(!settings.containsKey(b.clinic()))throw ApiError.conflict("RETRY_TRANSACTION");String fk=b.kind()==ReminderRules.Kind.APPOINTMENT?"appointment_id":"followup_plan_id";var history=locked.stream().filter(m->Objects.equals(m.get(fk),b.id())).map(m->writer.history(b,m)).toList();inputs.add(new ReminderBatchRules.Input(b,settings.get(b.clinic()),history,fact.chain(),fact.complete()));}
        var result=ReminderBatchRules.evaluate(inputs,now);int cancelled=0,unresolved=0;
        for(var item:result.items()){
            if(item.business().kind()==ReminderRules.Kind.FOLLOWUP){cancelled+=(int)item.result().actions().stream().filter(a->a.operation().equals("CANCEL")).count();var business=item.business();unresolved+=(int)locked.stream().filter(m->Objects.equals(m.get("followup_plan_id"),business.id())&&ReminderRules.unknown(writer.history(business,m))).count();}
            writer.apply(actor,item.business(),item.result().actions(),locked,"关联预约/复诊统一批次当前事实维护");
        }
        return new Detailed(result.outcome(),cancelled,unresolved);
    }
}
