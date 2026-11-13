package cn.adc.dental;

import java.time.Instant;
import java.util.*;

/** Shared evaluation only; caller supplies facts and history after the full batch is locked. */
final class ReminderBatchRules {
    record Input(ReminderRules.Business business,ReminderRules.Settings settings,List<ReminderRules.History> history,List<ReminderLifecycleRules.Scoped> chain,boolean complete) {}
    record Evaluated(ReminderRules.Business business,ReminderRules.Result result) {}
    record Batch(List<Evaluated> items,Map<String,Object> outcome) {}
    private ReminderBatchRules() {}
    static Batch evaluate(List<Input> inputs,Instant now){
        var items=new ArrayList<Evaluated>();var issues=new LinkedHashSet<String>();var counts=new HashMap<String,Integer>();var unresolved=new HashSet<Long>();var identities=new HashSet<String>();boolean reconcile=false;
        for(var input:inputs){var b=input.business();ReminderRules.Result result;
            if(!input.complete())throw ApiError.conflict("RETRY_TRANSACTION");
            if(!identities.add(b.kind().name()+":"+b.id()))throw new IllegalArgumentException("duplicate business in reminder batch");
            if(b.kind()==ReminderRules.Kind.APPOINTMENT){var base=ReminderRules.plan(b,input.settings(),now,input.history());var e=ReminderLifecycleRules.guard(b,base,input.chain(),input.complete(),true);issues.addAll(e.extraIssues());result=e.result();}
            else result=ReminderRules.plan(b,input.settings(),now,input.history());
            items.add(new Evaluated(b,result));issues.addAll(result.issues());reconcile|=result.reconciliation()||result.issues().stream().anyMatch(i->i.contains("RECONCILIATION"));input.history().stream().filter(ReminderRules::unknown).forEach(h->unresolved.add(h.id()));for(var action:result.actions())counts.merge(action.operation(),1,Integer::sum);
        }
        boolean noWrites=counts.getOrDefault("CREATE",0)+counts.getOrDefault("RESTORE",0)+counts.getOrDefault("CANCEL",0)==0;var outcome=new LinkedHashMap<String,Object>();outcome.put("scheduling",issues.isEmpty()?"APPLIED":noWrites?"SKIPPED":"PARTIAL");outcome.put("issues",List.copyOf(issues));outcome.put("reconciliationRequired",reconcile);outcome.put("createdCount",counts.getOrDefault("CREATE",0));outcome.put("reusedCount",counts.getOrDefault("REUSE",0));outcome.put("restoredCount",counts.getOrDefault("RESTORE",0));outcome.put("cancelledCount",counts.getOrDefault("CANCEL",0));outcome.put("unresolvedCount",unresolved.size());outcome.put("delivery","NOT_IMPLEMENTED");return new Batch(List.copyOf(items),Collections.unmodifiableMap(outcome));
    }
}
