package cn.adc.dental;

import java.time.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Production-shared chain/guard/replay policy only. No database or transaction mocks. */
public class ReminderLifecycleRulesCheck {
    private static final List<Map<String,Object>> checks=new ArrayList<>();
    private static final Instant NOW=Instant.parse("2026-10-22T08:00:00Z");
    private static void check(String name,boolean ok){checks.add(Map.of("name",name,"passed",ok));if(!ok)throw new AssertionError(name);}
    private static ReminderLifecycleRules.Link link(long id,Long previous,String status){return new ReminderLifecycleRules.Link(id,10,previous,null,status);}
    private static ReminderRules.Business b(long id,String invalid,String phone){return new ReminderRules.Business(ReminderRules.Kind.APPOINTMENT,id,10,1,invalid,NOW.plusSeconds(7200),null,phone);}
    private static ReminderRules.Settings s(int lead,boolean sms){return new ReminderRules.Settings(lead,1,"09:00:00",true,sms);}
    private static ReminderRules.Draft draft(ReminderRules.Business b,ReminderRules.Settings s,String channel){return ReminderRules.plan(b,s,NOW,List.of()).actions().stream().filter(a->a.channel().equals(channel)&&a.draft()!=null).findFirst().orElseThrow().draft();}
    private static ReminderRules.History h(long id,ReminderRules.Draft d,String state,Instant sent){return new ReminderRules.History(id,true,d.channel(),d.plannedAt(),d.recipient(),d.content(),state,sent);}
    private static ReminderLifecycleRules.Scoped history(long owner,ReminderRules.History h){return new ReminderLifecycleRules.Scoped(owner,h);}
    private static long count(ReminderLifecycleRules.Evaluation e,String op){return e.result().actions().stream().filter(a->a.operation().equals(op)).count();}
    public static void main(String[] args){var report=new LinkedHashMap<String,Object>();report.put("mode","no-db-no-http");report.put("startedAt",Instant.now().toString());boolean passed=false;try{run();passed=true;}catch(Throwable e){report.put("error",e.getClass().getSimpleName()+": "+e.getMessage());}report.put("status",passed?"passed":"failed");report.put("checks",checks);report.put("finishedAt",Instant.now().toString());System.out.println(Store.encode(report));if(!passed)System.exit(1);}
    private static void run(){
        check("single independent booking is closed ordinary chain",ReminderLifecycleRules.validChain(List.of(link(1,null,"待到诊")),10));
        var links=List.of(link(1,null,"已改约"),link(2,1L,"已改约"),link(3,2L,"待到诊"));check("all predecessor successor identities form closed chain",ReminderLifecycleRules.validChain(links,10));
        check("chain rows need not be supplied in ID order",ReminderLifecycleRules.validChain(List.of(links.get(2),links.get(0),links.get(1)),10));
        check("missing predecessor is not complete chain",!ReminderLifecycleRules.validChain(List.of(link(3,2L,"待到诊")),10));
        check("two independent roots cannot masquerade as one patient chain",!ReminderLifecycleRules.validChain(List.of(link(1,null,"待到诊"),link(2,null,"待到诊")),10));
        check("parent still active with successor is contradictory",!ReminderLifecycleRules.validChain(List.of(link(1,null,"待到诊"),link(2,1L,"待到诊")),10));
        check("forked successors do not pass closure guard",!ReminderLifecycleRules.validChain(List.of(link(1,null,"已改约"),link(2,1L,"待到诊"),link(3,1L,"待到诊")),10));
        check("predecessor cycle not mistaken as visited-complete",!ReminderLifecycleRules.validChain(List.of(link(1,2L,"已改约"),link(2,1L,"已改约")),10));
        check("duplicate identity rejected by chain policy",!ReminderLifecycleRules.validChain(List.of(link(1,null,"已改约"),link(1,null,"待到诊")),10));
        check("other patient never merged in appointment lineage",!ReminderLifecycleRules.validChain(List.of(new ReminderLifecycleRules.Link(1,11,null,null,"待到诊")),10));
        check("linked followup not admitted into A chain",!ReminderLifecycleRules.validChain(List.of(new ReminderLifecycleRules.Link(1,10,null,9L,"待到诊")),10));
        var longChain=new ArrayList<ReminderLifecycleRules.Link>();for(int n=1;n<=64;n++)longChain.add(link(n,n==1?null:(long)n-1,n==64?"待到诊":"已改约"));check("sixty four appointments accepted when complete",ReminderLifecycleRules.validChain(longChain,10));longChain.set(63,link(64,63L,"已改约"));longChain.add(link(65,64L,"待到诊"));check("sixty fifth does not silently become complete first page",!ReminderLifecycleRules.validChain(longChain,10));
        var business=b(3,null,"10000000000");var station=s(60,false);var both=s(60,true);var d=draft(business,station,"站内");var sms=draft(business,both,"短信");
        var fresh=ReminderLifecycleRules.evaluate(business,both,NOW,List.of(),true,true);check("independent new booking plans both configured channels",count(fresh,"CREATE")==2&&!fresh.result().reconciliation());
        var missing=ReminderLifecycleRules.evaluate(business,null,NOW,List.of(),true,true);check("no configuration means no default plans",count(missing,"CREATE")==0&&missing.result().issues().contains("RULE_MISSING"));
        var noPhone=ReminderLifecycleRules.evaluate(b(3,null,null),both,NOW,List.of(),true,true);check("missing phone keeps in-app patient plan and skips SMS",count(noPhone,"CREATE")==1&&noPhone.result().issues().contains("SMS_RECIPIENT_MISSING"));
        check("station recipient is patient master not account",fresh.result().actions().stream().filter(a->a.channel().equals("站内")).findFirst().orElseThrow().draft().recipient().equals("patient:10"));
        for(String state:List.of("发送中","失败")){
            var old=history(1,h(101,d,state,null));var guarded=ReminderLifecycleRules.evaluate(business,both,NOW,List.of(old),true,true);check(state+" predecessor blocks successor same channel new ID",count(guarded,"CREATE")==1&&guarded.result().actions().stream().noneMatch(a->a.operation().equals("CREATE")&&a.channel().equals("站内"))&&guarded.result().reconciliation());
            var reused=ReminderLifecycleRules.evaluate(business,station,NOW,List.of(old,history(3,h(102,d,"待发送",null))),true,true);check(state+" cancels known successor pending rather than duplicate reuse",count(reused,"REUSE")==0&&count(reused,"CANCEL")==1);
            var restored=ReminderLifecycleRules.evaluate(business,station,NOW,List.of(old,history(3,h(103,d,"已取消",null))),true,true);check(state+" cannot restore a successor while predecessor result unknown",count(restored,"RESTORE")==0&&count(restored,"CREATE")==0);
        }
        var disabled=ReminderLifecycleRules.evaluate(business,new ReminderRules.Settings(60,1,"09:00:00",false,false),NOW,List.of(history(1,h(104,d,"失败",null))),true,true);check("unknown chain still flagged when channels disabled",disabled.result().reconciliation()&&disabled.extraIssues().contains("CHAIN_RECONCILIATION_REQUIRED:站内"));
        var sent=history(1,h(105,d,"已发送",NOW.minusSeconds(10)));var suppression=ReminderLifecycleRules.evaluate(business,station,NOW,List.of(sent),true,true);check("same content sent predecessor suppresses successor per channel",count(suppression,"CREATE")==0&&!suppression.result().reconciliation()&&suppression.extraIssues().contains("CHAIN_SENT_SUPPRESSED:站内"));
        check("sent station predecessor does not suppress SMS successor",count(ReminderLifecycleRules.evaluate(business,both,NOW,List.of(sent),true,true),"CREATE")==1);
        var changed=new ReminderRules.Draft(d.channel(),d.plannedAt(),d.recipient(),"其他预约时间",false);check("different sent content does not suppress new fact",count(ReminderLifecycleRules.evaluate(business,station,NOW,List.of(history(1,h(106,changed,"已发送",NOW.minusSeconds(10)))),true,true),"CREATE")==1);
        var own=ReminderLifecycleRules.evaluate(business,station,NOW,List.of(history(3,h(107,d,"待发送",null))),true,true);check("current business known pending reused stable identity",count(own,"REUSE")==1&&own.result().actions().getFirst().messageId()==107);
        var restored=ReminderLifecycleRules.evaluate(business,station,NOW,List.of(history(3,h(108,d,"已取消",null))),true,true);check("current known canceled unsent restored same identity",count(restored,"RESTORE")==1&&restored.result().actions().getFirst().messageId()==108);
        var immediate=s(180,false);var first=ReminderLifecycleRules.evaluate(business,immediate,NOW,List.of(),true,true);var immediateDraft=first.result().actions().getFirst().draft();var retry=ReminderLifecycleRules.evaluate(business,immediate,NOW.plusSeconds(60),List.of(history(3,h(109,immediateDraft,"待发送",null))),true,true);check("immediate reconciliation retains original message and planned timestamp",count(retry,"REUSE")==1&&retry.result().actions().getFirst().plannedAt().equals(NOW));
        var incomplete=ReminderLifecycleRules.evaluate(business,both,NOW,List.of(),false,true);check("incomplete closure forbids all new channels and requires reconciliation",count(incomplete,"CREATE")==0&&incomplete.result().reconciliation()&&incomplete.extraIssues().contains("CHAIN_INCOMPLETE"));
        var terminal=b(3,"APPOINTMENT_STARTED_OR_INVALID",null);var known=history(3,h(110,d,"待发送",null));var unknown=history(3,h(111,sms,"发送中",null));var closed=ReminderLifecycleRules.evaluate(terminal,both,NOW,List.of(known,unknown),true,false);check("terminal policy cancels known unsent and retains unknown",count(closed,"CANCEL")==1&&count(closed,"RECONCILE")==1&&count(closed,"CREATE")==0&&closed.result().reconciliation());
        var incompleteTerminal=ReminderLifecycleRules.evaluate(terminal,station,NOW,List.of(known),false,false);check("incomplete chain never prevents safe terminal cleanup of locked known history",count(incompleteTerminal,"CANCEL")==1&&incompleteTerminal.result().reconciliation());
        var independent=ReminderLifecycleRules.evaluate(b(50,null,"10000000000"),station,NOW,List.of(),true,true);check("unrelated same patient booking not globally locked out by caller's other chain",count(independent,"CREATE")==1);
        var foreign=new ReminderRules.History(112,false,"站内",d.plannedAt(),d.recipient(),d.content(),"待发送",null);var foreignGuard=ReminderLifecycleRules.evaluate(business,station,NOW,List.of(history(1,foreign)),true,true);check("foreign predecessor history blocks without relabelling it as related",count(foreignGuard,"CREATE")==0&&foreignGuard.result().reconciliation());
        var occupied=history(3,h(113,changed,"已取消",null));var collision=ReminderLifecycleRules.evaluate(business,station,NOW,List.of(occupied),true,true);check("current historical natural key occupation no overwrite or new ID",collision.result().reconciliation()&&count(collision,"CREATE")==0);
        var saved=Map.of("scheduling","PARTIAL","createdCount",1,"cancelledCount",2,"delivery","NOT_IMPLEMENTED");check("same original request returns saved result object without recomputation",ReminderLifecycleRules.originalOutcome(saved)==saved);
        var legacy=ReminderLifecycleRules.originalOutcome(null);check("legacy audit lacks outcome explicitly not silently repaired",legacy instanceof Map<?,?> m&&"LEGACY_NOT_APPLIED".equals(m.get("scheduling")));
        check("ordinary and linked legacy replay share explicit fallback",ReminderLifecycleRules.originalOutcome(null).equals(legacy));
        var previousLinked=ReminderLifecycleRules.empty("NOT_CONNECTED");check("older linked audit keeps its original result",ReminderLifecycleRules.originalOutcome(previousLinked)==previousLinked);
        var mapper=JsonMapper.builder().build();var decoded=mapper.readTree(Store.encode(saved));check("stored JSON outcome replay retains fixed historical counts",ReminderLifecycleRules.originalOutcome(decoded)==decoded&&decoded.get("createdCount").asInt()==1);
    }
}
