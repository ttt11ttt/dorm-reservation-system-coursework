package cn.adc.dental;

import java.time.Instant;
import java.util.*;

final class ReminderLifecycleRules {
    static final int CHAIN_LIMIT=64;
    record Scoped(long appointmentId,ReminderRules.History history) {}
    record Link(long id,long patient,Long previous,Long plan,String status) {}
    record Evaluation(ReminderRules.Result result,List<String> extraIssues) {}
    private ReminderLifecycleRules() {}
    static boolean validChain(List<Link> links,long patient){
        if(links.isEmpty()||links.size()>CHAIN_LIMIT)return false;var rows=new HashMap<Long,Link>();var children=new HashMap<Long,Integer>();int roots=0;
        for(var row:links){if(row.id()<=0||row.patient()!=patient||row.plan()!=null||rows.put(row.id(),row)!=null)return false;if(row.previous()==null)roots++;else children.merge(row.previous(),1,Integer::sum);}
        if(roots!=1)return false;for(var row:links){if(children.getOrDefault(row.id(),0)>1||children.containsKey(row.id())&&!row.status().equals("已改约"))return false;var seen=new HashSet<Long>();Link cursor=row;while(cursor!=null){if(!seen.add(cursor.id()))return false;if(cursor.previous()==null)break;cursor=rows.get(cursor.previous());if(cursor==null)return false;}}
        return true;
    }
    static Evaluation evaluate(ReminderRules.Business b,ReminderRules.Settings s,Instant now,List<Scoped> chain,boolean complete,boolean generate){
        var own=chain.stream().filter(h->h.appointmentId()==b.id()).map(Scoped::history).toList();return guard(b,ReminderRules.plan(b,s,now,own),chain,complete,generate);
    }
    static Evaluation guard(ReminderRules.Business b,ReminderRules.Result base,List<Scoped> chain,boolean complete,boolean generate){
        var extra=new ArrayList<String>();var actions=new ArrayList<ReminderRules.Action>();
        chain.stream().filter(h->h.appointmentId()!=b.id()&&ReminderRules.unknown(h.history())).map(h->"CHAIN_RECONCILIATION_REQUIRED:"+h.history().channel()).distinct().forEach(extra::add);
        for(var action:base.actions()){
            if(!Set.of("CREATE","REUSE","RESTORE").contains(action.operation())){actions.add(action);continue;}
            boolean unknown=chain.stream().anyMatch(h->h.appointmentId()!=b.id()&&h.history().channel().equals(action.channel())&&ReminderRules.unknown(h.history()));
            boolean sent=chain.stream().anyMatch(h->h.appointmentId()!=b.id()&&h.history().channel().equals(action.channel())&&ReminderRules.sent(h.history())&&h.history().content().equals(action.draft().content()));
            String issue=!complete?"CHAIN_INCOMPLETE":unknown?"CHAIN_RECONCILIATION_REQUIRED:"+action.channel():sent?"CHAIN_SENT_SUPPRESSED:"+action.channel():!generate?"CHAIN_OTHER_ACTIVE_APPOINTMENT":null;
            if(issue==null){actions.add(action);continue;}extra.add(issue);
            if(action.operation().equals("REUSE"))actions.add(new ReminderRules.Action("CANCEL",action.messageId(),action.channel(),action.plannedAt(),"已取消",null));
        }
        if(!complete)extra.add("CHAIN_INCOMPLETE");boolean reconciliation=base.reconciliation()||!complete||extra.stream().anyMatch(i->i.startsWith("CHAIN_RECONCILIATION")||i.equals("CHAIN_OTHER_ACTIVE_APPOINTMENT"));return new Evaluation(new ReminderRules.Result(base.issues(),List.copyOf(actions),reconciliation),extra.stream().distinct().toList());
    }
    static Map<String,Object> empty(String scheduling){return Map.of("scheduling",scheduling,"issues",List.of(),"reconciliationRequired",false,"createdCount",0,"reusedCount",0,"restoredCount",0,"cancelledCount",0,"unresolvedCount",0,"delivery","NOT_IMPLEMENTED");}
    static Object originalOutcome(Object saved){return saved==null?empty("LEGACY_NOT_APPLIED"):saved;}
}
