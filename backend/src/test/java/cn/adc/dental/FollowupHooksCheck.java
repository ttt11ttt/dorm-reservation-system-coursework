package cn.adc.dental;

import java.time.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Ordinary Java main, no Spring context, SQL fixture/mock, DataSource, HTTP or server. */
public class FollowupHooksCheck {
    private static final List<Map<String,Object>> checks=new ArrayList<>();
    private static void check(String name,boolean ok) {checks.add(Map.of("name",name,"passed",ok));if(!ok)throw new AssertionError(name);}
    private static void reject(String name,int status,String code,Runnable fn) {try{fn.run();throw new AssertionError(name+" accepted");}catch(ApiError e){check(name,e.status==status&&e.code.equals(code));}}
    private static Identity.Actor employee(long id,Long dentist,long... clinics) {var grants=new ArrayList<Map<String,Object>>();for(long c:clinics)grants.add(Map.of("role_code","前台","scope_kind","门店","clinic_id",c));return new Identity.Actor(id,"员工",null,dentist,grants);}
    private static FollowupLinkRules.BookingIdentity booking(Long plan,long actor,long patient,String channel) {return new FollowupLinkRules.BookingIdentity(plan,null,actor,patient,2,30,40,Instant.parse("2026-11-01T01:00:00Z"),Instant.parse("2026-11-01T01:30:00Z"),channel,"复查",null);}
    private static void jsonReject(String name,JsonMapper mapper,String value,Class<?> type){try{mapper.readValue(value,type);throw new AssertionError(name+" accepted");}catch(RuntimeException error){check(name,true);}}
    public static void main(String[] args) {
        var report=new LinkedHashMap<String,Object>();report.put("mode","no-db-no-http");report.put("startedAt",Instant.now().toString());boolean ok=false;
        try{run();ok=true;}catch(Throwable e){report.put("error",e.getClass().getSimpleName()+": "+e.getMessage());}
        report.put("status",ok?"passed":"failed");report.put("checks",checks);report.put("finishedAt",Instant.now().toString());System.out.println(Store.encode(report));if(!ok)System.exit(1);
    }
    private static void run() {
        var owner=new FollowupRules.Owner(10,20,1,100);var plan=new FollowupLinkRules.Plan(500,owner,"待复诊",null,null);
        check("new completion exposes combined reminder outcome",FollowupHooks.reminderScheduling(Map.of("scheduling","PARTIAL","delivery","NOT_IMPLEMENTED")).equals("SEE_REMINDER_OUTCOME"));
        check("legacy completion fallback reports unavailable scheduling",FollowupHooks.reminderScheduling(null).equals("NOT_IMPLEMENTED"));
        var patient=new Identity.Actor(4,"患者",10L,null,List.of());var front=employee(3,null,1,2);
        FollowupLinkRules.authorize(patient,plan,2,true);check("session patient may choose a different target clinic",true);
        FollowupLinkRules.authorize(front,plan,2,false);check("front desk with responsible and target clinic grants accepted",true);
        reject("other session patient denied",403,"FORBIDDEN",()->FollowupLinkRules.authorize(new Identity.Actor(5,"患者",11L,null,List.of()),plan,2,true));
        reject("target-only front grant insufficient",403,"FORBIDDEN",()->FollowupLinkRules.authorize(employee(6,null,2),plan,2,false));
        reject("responsible-only front grant insufficient",403,"FORBIDDEN",()->FollowupLinkRules.authorize(employee(6,null,1),plan,2,false));
        reject("staff cannot use self identity",403,"FORBIDDEN",()->FollowupLinkRules.authorize(front,plan,2,true));
        FollowupLinkRules.newAssociation(plan,10);check("pending same-patient association accepted without target dentist matching source",booking(500L,4,10,"自助").dentist()!=owner.dentist());
        reject("cross-patient association rejected",409,"FOLLOWUP_SOURCE_INVALID",()->FollowupLinkRules.newAssociation(plan,11));
        reject("cancelled plan cannot receive new association",409,"INVALID_STATE",()->FollowupLinkRules.newAssociation(new FollowupLinkRules.Plan(500,owner,"已取消",null,null),10));
        reject("completed plan cannot receive new association",409,"INVALID_STATE",()->FollowupLinkRules.newAssociation(new FollowupLinkRules.Plan(500,owner,"已完成",200L,Instant.parse("2026-10-22T01:00:00Z")),10));
        var resources=FollowupLinkRules.resources(List.of(3L,1L,2L,1L),List.of(30L,20L,10L,20L));
        check("all responsible original target clinic IDs sorted and unique",resources.clinics().equals(List.of(1L,2L,3L)));
        check("all responsible original target dentist IDs sorted and unique",resources.dentists().equals(List.of(10L,20L,30L)));
        var ordinary=booking(null,4,10,"自助");var linked=booking(500L,4,10,"自助");var otherPlan=booking(501L,4,10,"自助");
        check("ordinary exact creation identity is stable",FollowupLinkRules.sameCreation(ordinary,ordinary,null));
        check("linked exact creation identity is stable",FollowupLinkRules.sameCreation(linked,linked,500L));
        check("ordinary operation number cannot be replayed as association",!FollowupLinkRules.sameCreation(linked,ordinary,500L));
        check("association operation cannot be replayed as ordinary creation",!FollowupLinkRules.sameCreation(ordinary,linked,500L));
        check("association operation cannot switch plan",!FollowupLinkRules.sameCreation(otherPlan,linked,501L));
        check("association replay verifies current foreign key too",!FollowupLinkRules.sameCreation(linked,linked,501L));
        check("creation replay verifies actor",!FollowupLinkRules.sameCreation(booking(500L,5,10,"自助"),linked,500L));
        check("creation replay verifies patient",!FollowupLinkRules.sameCreation(booking(500L,4,11,"自助"),linked,500L));
        check("creation replay verifies channel",!FollowupLinkRules.sameCreation(booking(500L,4,10,"电话"),linked,500L));
        check("legacy ordinary retry after association backfill preserves original intent",FollowupLinkRules.sameCreation(ordinary,ordinary,500L));
        var changedTime=new FollowupLinkRules.BookingIdentity(500L,null,4,10,2,30,40,linked.start().plusSeconds(1),linked.end(),"自助","复查",null);
        check("creation replay verifies normalized exact time",!FollowupLinkRules.sameCreation(changedTime,linked,500L));
        var successor=new FollowupLinkRules.BookingIdentity(500L,199L,4,10,2,30,40,linked.start(),linked.end(),"自助","复查",null);
        check("reschedule successor cannot masquerade as new creation",!FollowupLinkRules.sameCreation(linked,successor,500L));
        var time=Instant.parse("2026-10-22T01:02:03Z");var clinical=new FollowupLinkRules.Clinical(200,10,500L,true,"已到诊","已完成",time);
        var close=FollowupLinkRules.completion(plan,clinical,true);
        check("actual completion closes pending plan",close.updatePlan()&&close.status().equals("已完成")&&close.outcome().equals("COMPLETED_NOW"));
        check("actual completion copies exact clinical identity and instant",close.completedId()==200&&close.completedAt().equals(time));
        check("linked closure is independent of source dentist and target clinic",owner.dentist()!=linked.dentist()&&owner.clinic()!=linked.clinic()&&close.updatePlan());
        var closed=new FollowupLinkRules.Plan(500,owner,close.status(),close.completedId(),close.completedAt());
        var repeat=FollowupLinkRules.completion(closed,clinical,false);
        check("same clinical retry does not mutate plan",!repeat.updatePlan()&&repeat.outcome().equals("ALREADY_COMPLETED_BY_THIS")&&!repeat.reconciliation());
        var later=new FollowupLinkRules.Clinical(201,10,500L,true,"已到诊","已完成",time.plusSeconds(60));var second=FollowupLinkRules.completion(closed,later,true);
        check("second completion preserves first identity",!second.updatePlan()&&second.completedId()==200&&second.completedAt().equals(time));
        check("second completion clearly reports other original completer",second.outcome().equals("COMPLETED_BY_OTHER")&&second.reconciliation());
        var cancelled=FollowupLinkRules.completion(new FollowupLinkRules.Plan(500,owner,"已取消",null,null),clinical,true);
        check("cancelled plan stays cancelled while real clinical completion is retained",!cancelled.updatePlan()&&cancelled.status().equals("已取消")&&cancelled.outcome().equals("CANCELLED_PRESERVED"));
        var legacy=FollowupLinkRules.completion(plan,clinical,false);
        check("legacy completed encounter retry never silently repairs plan",!legacy.updatePlan()&&legacy.status().equals("待复诊")&&legacy.reconciliation()&&legacy.outcome().equals("LEGACY_RECONCILIATION_REQUIRED"));
        reject("source cannot serve as own followup completion",409,"FOLLOWUP_SOURCE_INVALID",()->FollowupLinkRules.completion(plan,new FollowupLinkRules.Clinical(100,10,500L,true,"已到诊","已完成",time),true));
        reject("cross-patient clinical completion rejected",409,"FOLLOWUP_SOURCE_INVALID",()->FollowupLinkRules.completion(plan,new FollowupLinkRules.Clinical(200,11,500L,true,"已到诊","已完成",time),true));
        reject("wrong associated plan rejected",409,"FOLLOWUP_SOURCE_INVALID",()->FollowupLinkRules.completion(plan,new FollowupLinkRules.Clinical(200,10,501L,true,"已到诊","已完成",time),true));
        reject("not attended cannot complete plan",409,"FOLLOWUP_SOURCE_INVALID",()->FollowupLinkRules.completion(plan,new FollowupLinkRules.Clinical(200,10,500L,false,"待到诊","未开始",null),true));
        reject("arrival and contact cannot replace clinical completion",409,"FOLLOWUP_SOURCE_INVALID",()->FollowupLinkRules.completion(plan,new FollowupLinkRules.Clinical(200,10,500L,true,"已到诊","进行中",null),true));
        var pending=FollowupLinkRules.notification("待发送",false,true,true);check("only known pending unsent reminder is cancelled",pending.cancel()&&!pending.reconcile());
        for(String status:List.of("发送中","失败")){var unknown=FollowupLinkRules.notification(status,false,true,true);check(status+" preserved and flagged without blocking clinical decision",!unknown.cancel()&&unknown.reconcile()&&close.updatePlan());}
        check("sent reminder history retained",!FollowupLinkRules.notification("已发送",true,true,true).cancel());
        check("already cancelled reminder retained",!FollowupLinkRules.notification("已取消",false,true,true).cancel());
        check("inconsistent pending timestamp is kept and flagged",FollowupLinkRules.notification("待发送",true,true,true).reconcile()&&!FollowupLinkRules.notification("待发送",true,true,true).cancel());
        check("wrong reminder ownership kept for reconciliation",FollowupLinkRules.notification("待发送",false,false,true).reconcile()&&!FollowupLinkRules.notification("待发送",false,false,true).cancel());
        check("read-only clinical retry does not clean legacy pending reminders",!FollowupLinkRules.notification("待发送",false,true,false).cancel()&&FollowupLinkRules.notification("待发送",false,true,false).reconcile());
        var before=new FollowupRules.Snapshot("10","100","20","1","2026-10-01","复查","待复诊",null,null,null,"2026-10-01T01:00Z","2026-10-22T01:02:03Z","1");
        var after=new FollowupRules.Snapshot("10","100","20","1","2026-10-01","复查","已完成","200","2026-10-22T01:02:03Z",null,"2026-10-01T01:00Z","2026-10-22T01:02:03Z","2");
        reject("completion snapshot and audit count invalidate previous maintenance snapshot",409,"FOLLOWUP_CONFLICT",()->FollowupRules.compare(before,after));
        var builder=JsonMapper.builder();new JsonConfiguration().strictJson().customize(builder);var mapper=builder.build();
        String request="{\"appointmentNo\":\"FUP-1\",\"clinicId\":\"2\",\"dentistId\":\"30\",\"scheduleId\":\"40\",\"scheduledStart\":\"2026-11-01T01:00:00Z\",\"scheduledEnd\":\"2026-11-01T01:30:00Z\",\"requestedTreatments\":\"复查\"}";
        var dto=mapper.readValue(request,PatientSchedulingApi.SelfBooking.class);check("linked patient route reuses existing self-booking DTO",dto.clinicId().equals("2")&&dto.dentistId().equals("30"));
        jsonReject("linked patient route cannot specify patientId",mapper,request.replace("}",",\"patientId\":\"11\"}"),PatientSchedulingApi.SelfBooking.class);
        jsonReject("linked patient route cannot choose planId inside ordinary body",mapper,request.replace("}",",\"planId\":\"501\"}"),PatientSchedulingApi.SelfBooking.class);
        jsonReject("linked patient route cannot forge channel",mapper,request.replace("}",",\"bookingChannel\":\"电话\"}"),PatientSchedulingApi.SelfBooking.class);
        jsonReject("linked staff route cannot choose arbitrary patient",mapper,request.replace("}",",\"bookingChannel\":\"电话\",\"patientId\":\"11\"}"),FollowupApi.StaffAppointment.class);
        var staff=mapper.readValue(request.replace("}",",\"bookingChannel\":\"电话\"}"),FollowupApi.StaffAppointment.class);check("linked staff route retains explicit manual channel",staff.bookingChannel().equals("电话"));
    }
}
