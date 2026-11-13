package cn.adc.dental;

import java.util.*;

/** Pure B fact preparation. A plan may own several independent reschedule chains. */
final class ReminderLinkedFacts {
    record Appointment(ReminderLifecycleRules.Link link,ReminderRules.Business business) {}
    private ReminderLinkedFacts() {}

    static Map<Long,List<Long>> groups(long patient,long plan,List<ReminderLifecycleRules.Link> links){
        var rows=new TreeMap<Long,ReminderLifecycleRules.Link>();
        for(var link:links){
            if(link.patient()!=patient||!Objects.equals(link.plan(),plan)||rows.put(link.id(),link)!=null)
                throw ApiError.conflict("RETRY_TRANSACTION");
        }
        var byRoot=new TreeMap<Long,List<ReminderLifecycleRules.Link>>();
        for(var link:rows.values()){
            var seen=new HashSet<Long>();var cursor=link;
            while(cursor.previous()!=null){
                if(!seen.add(cursor.id())||seen.size()>=ReminderLifecycleRules.CHAIN_LIMIT)
                    throw ApiError.conflict("RETRY_TRANSACTION");
                cursor=rows.get(cursor.previous());
                if(cursor==null)throw ApiError.conflict("RETRY_TRANSACTION");
            }
            byRoot.computeIfAbsent(cursor.id(),ignored->new ArrayList<>()).add(link);
        }
        var result=new TreeMap<Long,List<Long>>();
        for(var group:byRoot.values()){
            // Reuse A's exact structural rules; B separately checked each row's required plan.
            var ordinary=group.stream().map(a->new ReminderLifecycleRules.Link(a.id(),a.patient(),a.previous(),null,a.status())).toList();
            if(!ReminderLifecycleRules.validChain(ordinary,patient))throw ApiError.conflict("RETRY_TRANSACTION");
            var ids=group.stream().map(ReminderLifecycleRules.Link::id).sorted().toList();
            for(long id:ids)result.put(id,ids);
        }
        return Collections.unmodifiableMap(result);
    }

    static List<ReminderBatchMaintenance.Fact> facts(ReminderRules.Business plan,List<Appointment> appointments,List<ReminderLifecycleRules.Scoped> history){
        if(plan.kind()!=ReminderRules.Kind.FOLLOWUP)throw new IllegalArgumentException("followup business required");
        var groups=groups(plan.patient(),plan.id(),appointments.stream().map(Appointment::link).toList());
        var facts=new ArrayList<ReminderBatchMaintenance.Fact>();
        for(var appointment:appointments){
            var b=appointment.business();var link=appointment.link();
            if(b.kind()!=ReminderRules.Kind.APPOINTMENT||b.id()!=link.id()||b.patient()!=plan.patient())
                throw ApiError.conflict("RETRY_TRANSACTION");
            var chain=history.stream().filter(h->groups.get(b.id()).contains(h.appointmentId())).toList();
            facts.add(new ReminderBatchMaintenance.Fact(b,chain,true));
        }
        facts.add(new ReminderBatchMaintenance.Fact(plan,List.of(),true));
        return List.copyOf(facts);
    }
}
