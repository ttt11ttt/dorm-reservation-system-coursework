package cn.adc.dental;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Actual scope/window/fund/amount policy only. Does not simulate SQL, RR, snapshots or transactions. */
public class QuarterRulesCheck {
    private static final List<Map<String,Object>> checks=new ArrayList<>();
    private static void check(String name,boolean ok){checks.add(Map.of("name",name,"passed",ok));if(!ok)throw new AssertionError(name);}
    private static void reject(String name,int status,String code,Runnable run){try{run.run();throw new AssertionError(name+" accepted");}catch(ApiError e){check(name,e.status==status&&e.code.equals(code));}}
    private static Map<String,Object> grant(String role,String scope,Long clinic){var g=new HashMap<String,Object>();g.put("role_code",role);g.put("scope_kind",scope);g.put("clinic_id",clinic);return g;}
    private static Identity.Actor actor(String kind,Map<String,Object>... grants){return new Identity.Actor(7,kind,kind.equals("患者")?8L:null,null,List.of(grants));}
    private static Map<String,Object> count(String type,String reason,long n){return Map.of("source_type",type,"reason",reason,"source_count",n);}
    public static void main(String[] args){var report=new LinkedHashMap<String,Object>();report.put("mode","no-db-no-http");report.put("startedAt",Instant.now().toString());boolean passed=false;try{run();passed=true;}catch(Throwable e){report.put("error",e.getClass().getSimpleName()+": "+e.getMessage());}report.put("status",passed?"passed":"failed");report.put("checks",checks);report.put("finishedAt",Instant.now().toString());System.out.println(Store.encode(report));if(!passed)System.exit(1);}
    private static void run(){
        var q=QuarterRules.window(2026,4);check("quarter Shanghai DATE boundaries exclusive end",q.start().equals(LocalDate.of(2026,10,1))&&q.end().equals(LocalDate.of(2027,1,1)));
        check("quarter completion filter UTC start",q.utcStart().equals(Instant.parse("2026-09-30T16:00:00Z")));
        check("quarter completion filter UTC exclusive end",q.utcEnd().equals(Instant.parse("2026-12-31T16:00:00Z")));
        check("quarter end included only in next quarter",QuarterRules.window(2027,1).utcStart().equals(q.utcEnd()));
        check("one second before end remains in previous interval",q.utcEnd().minusSeconds(1).isAfter(q.utcStart()));
        var leap=QuarterRules.window(2024,1);check("leap year quarter includes February twenty nine",leap.start().plusDays(59).equals(LocalDate.of(2024,2,29))&&leap.end().equals(LocalDate.of(2024,4,1)));
        var historical=QuarterRules.window(1990,2);check("historical window uses actual Shanghai timezone rules",historical.utcEnd().equals(LocalDate.of(1990,7,1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant()));
        reject("quarter zero invalid",400,"INVALID_INPUT",()->QuarterRules.window(2026,0));reject("quarter five invalid",400,"INVALID_INPUT",()->QuarterRules.window(2026,5));reject("unsupported year range",400,"INVALID_INPUT",()->QuarterRules.window(10000,1));
        reject("quarter before TIMESTAMP lower bound not silently partial",400,"INVALID_INPUT",()->QuarterRules.window(1970,1));reject("quarter crossing TIMESTAMP upper bound not silently partial",400,"INVALID_INPUT",()->QuarterRules.window(2038,1));QuarterRules.window(2037,4);check("last fully supported calendar quarter accepted",true);
        var local=actor("员工",grant("财务","门店",2L),grant("财务","门店",1L),grant("财务","门店",2L));var localScope=QuarterRules.scope(local,false,null,null,20);check("finance authorized clinics numeric sorted and unique",localScope.clinicIds().equals(List.of(1L,2L))&&!localScope.allClinics());
        var selected=QuarterRules.scope(local,false,"2","8",100);check("finance clinic selection narrows before source scan",selected.clinicIds().equals(List.of(2L))&&selected.displayDentist()==8&&!selected.headquarters());
        reject("finance other clinic cannot expand query",403,"FORBIDDEN",()->QuarterRules.scope(local,false,"3",null,20));
        var financeChain=actor("员工",grant("财务","连锁",null));var financeAll=QuarterRules.scope(financeChain,false,null,null,20);check("explicit chain finance source scope all clinics but no company fund privilege",financeAll.allClinics()&&!financeAll.headquarters());
        var financeOne=QuarterRules.scope(financeChain,false,"9",null,20);check("chain finance explicit clinic remains narrower source scope",!financeOne.allClinics()&&financeOne.clinicIds().equals(List.of(9L)));
        reject("all local finance grants cannot impersonate headquarters",403,"FORBIDDEN",()->QuarterRules.scope(local,true,null,null,20));reject("chain finance not headquarters fund role",403,"FORBIDDEN",()->QuarterRules.scope(financeChain,true,null,null,20));
        var hq=actor("员工",grant("总部管理","连锁",null));var full=QuarterRules.scope(hq,true,null,null,20);check("explicit headquarters chain has full source scope",full.headquarters()&&full.allClinics());
        var filtered=QuarterRules.scope(hq,true,"99","8",1);check("headquarters display filter never narrows full source scope",filtered.headquarters()&&filtered.allClinics()&&filtered.clinicIds().isEmpty()&&filtered.displayClinic()==99&&filtered.displayDentist()==8);
        reject("headquarters local grant cannot prove chain scope",403,"FORBIDDEN",()->QuarterRules.scope(actor("员工",grant("总部管理","门店",1L)),true,null,null,20));
        reject("headquarters uses explicit endpoint instead of implicit finance grant",403,"FORBIDDEN",()->QuarterRules.scope(hq,false,null,null,20));
        var dual=actor("员工",grant("总部管理","连锁",null),grant("财务","门店",1L));check("dual role finance endpoint still has only finance source scope",QuarterRules.scope(dual,false,null,null,20).clinicIds().equals(List.of(1L)));
        for(String role:List.of("运营人员／店长","前台","牙医","账号管理"))reject(role+" no finance reporting grant",403,"FORBIDDEN",()->QuarterRules.scope(actor("员工",grant(role,"连锁",null)),false,null,null,20));
        reject("patient with forged role rejected",403,"FORBIDDEN",()->QuarterRules.scope(actor("患者",grant("财务","连锁",null)),false,null,null,20));
        reject("zero page limit rejected",400,"INVALID_INPUT",()->QuarterRules.scope(local,false,null,null,0));reject("one hundred one page limit rejected",400,"INVALID_INPUT",()->QuarterRules.scope(local,false,null,null,101));reject("bad selected dentist identity rejected",400,"INVALID_INPUT",()->QuarterRules.scope(local,false,null,"0",20));
        var regular=QuarterRules.amounts(new BigDecimal("100.00"),new BigDecimal("30.00"),new BigDecimal("20.00"));check("independently aggregated amounts subtract once",regular.profit().equals("50.00"));
        var costOnly=QuarterRules.amounts(BigDecimal.ZERO,BigDecimal.ZERO,new BigDecimal("100.00"));check("cost only aggregate retains negative contribution",costOnly.profit().equals("-100.00")&&costOnly.revenue().equals("0.00"));
        var adjustment=QuarterRules.amounts(new BigDecimal("100.00"),new BigDecimal("-30.00"),new BigDecimal("20.00"));check("signed confirmed adjustment not converted to absolute cost",adjustment.profit().equals("110.00")&&adjustment.supplies().equals("-30.00"));
        var exact=QuarterRules.amounts(new BigDecimal("0.30"),new BigDecimal("0.10"),new BigDecimal("0.20"));check("decimal cent arithmetic no binary residual",exact.profit().equals("0.00"));
        check("wide SQL aggregate not limited to individual DECIMAL source maximum",QuarterRules.amounts(new BigDecimal("99999999999999.99"),BigDecimal.ZERO,BigDecimal.ZERO).revenue().equals("99999999999999.99"));
        var empty=QuarterRules.amounts(BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO);check("empty scope explicit zero totals",empty.profit().equals("0.00")&&empty.operations().equals("0.00"));
        var complete=QuarterRules.integrity(List.of());check("empty complete anomaly aggregate only means registered sources checked",complete.knownSourcesComplete()&&complete.blockingSources()==0&&complete.status().equals("REGISTERED_SOURCES_CHECKED"));
        var incomplete=QuarterRules.integrity(List.of(count("APPOINTMENT","INVOICE_NOT_ISSUED",101),count("EXPENSE","EXPENSE_NOT_CONFIRMED",7),count("EXPENSE","EXPENSE_SOURCE_INVALID",1)));check("all anomaly count groups accumulated rather than first page",!incomplete.knownSourcesComplete()&&incomplete.blockingSources()==109&&incomplete.status().equals("INCOMPLETE"));
        var last=QuarterRules.integrity(List.of(count("EXPENSE","EXPENSE_SOURCE_INVALID",1)));check("one anomaly outside visible page still prevents completeness",!last.knownSourcesComplete());
        check("full complete company fund ten percent once",QuarterRules.fund(full,complete,new BigDecimal("100.00")).equals("10.00"));
        check("negative chain net is retained but fund lower bound zero",QuarterRules.fund(full,complete,new BigDecimal("-50.00")).equals("0.00"));
        check("zero complete chain fund zero",QuarterRules.fund(full,complete,BigDecimal.ZERO).equals("0.00"));
        check("company HALF_UP rounds half cent upwards",QuarterRules.fund(full,complete,new BigDecimal("0.05")).equals("0.01"));
        check("company below half cent rounds down",QuarterRules.fund(full,complete,new BigDecimal("0.04")).equals("0.00"));
        check("fund calculation after negative contributors offset profit",QuarterRules.fund(full,complete,new BigDecimal("100.00").add(new BigDecimal("-90.00"))).equals("1.00"));
        check("fund not individually rounded before sum",QuarterRules.fund(full,complete,new BigDecimal("0.05").add(new BigDecimal("0.05"))).equals("0.01"));
        check("known blocking source makes full fund null",QuarterRules.fund(full,incomplete,new BigDecimal("100.00"))==null);
        check("local fund null does not masquerade as zero",QuarterRules.fund(localScope,complete,new BigDecimal("100.00"))==null&&QuarterRules.fundReason(localScope,complete).equals("PARTIAL_SCOPE"));
        check("chain finance still cannot return company fund",QuarterRules.fund(financeAll,complete,new BigDecimal("100.00"))==null);
        check("HQ display empty or filtered does not change full company fund",QuarterRules.fund(filtered,complete,new BigDecimal("100.00")).equals("10.00"));
        check("known missing last source sets incomplete reason",QuarterRules.fund(full,last,new BigDecimal("100.00"))==null&&QuarterRules.fundReason(full,last).equals("KNOWN_BLOCKING_SOURCES"));
        check("complete report fund remains registered source estimate",QuarterRules.fundReason(full,complete).equals("REGISTERED_SOURCES_ESTIMATE"));
        var serialized=Store.encode(regular);check("money result fields serialize as decimal strings",serialized.contains("\"profit\":\"50.00\"")&&serialized.contains("\"revenue\":\"100.00\""));
    }
}
