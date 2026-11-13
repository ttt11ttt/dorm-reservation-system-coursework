package cn.adc.dental;

import java.util.*;
import java.math.BigDecimal;
import java.time.*;
import tools.jackson.databind.json.JsonMapper;

/** Actual pure expense policy/DTO only; no DB fixtures, JDBC mock, HTTP or transaction model. */
public class ExpenseRulesCheck {
    private static final List<Map<String,Object>> checks=new ArrayList<>();
    private static void check(String name,boolean ok){checks.add(Map.of("name",name,"passed",ok));if(!ok)throw new AssertionError(name);}
    private static void reject(String name,int status,String code,Runnable run){try{run.run();throw new AssertionError(name+" accepted");}catch(ApiError e){check(name,e.status==status&&e.code.equals(code));}}
    private static Identity.Actor actor(String kind,String role,String scope,Long clinic){var grant=new HashMap<String,Object>();grant.put("role_code",role);grant.put("scope_kind",scope);grant.put("clinic_id",clinic);return new Identity.Actor(7,kind,kind.equals("患者")?8L:null,null,List.of(grant));}
    private static ExpenseRules.Creation creation(String amount){return new ExpenseRules.Creation("11","VOUCHER-1",1,"医疗用品","2026-10-22",amount,null);}
    private static ExpenseRules.Source source(String status,String confirmedBy,String confirmedAt){return new ExpenseRules.Source("11","VOUCHER-1",1,"医疗用品","1","2026-10-22","0.30",status,null,null,"7","2026-10-22T03:00Z",confirmedBy,confirmedAt);}
    private static ExpenseRules.Allocation allocation(String id,String dentist,String treatment,String amount,String basis){return new ExpenseRules.Allocation(id,dentist,treatment,amount,basis);}
    private static List<ExpenseRules.Allocation> rows(){return List.of(allocation("21","1","31","0.10","实际耗用凭据甲"),allocation("22","2",null,"0.20","门店核定分配乙"));}
    private static ExpenseRules.Snapshot snapshot(String count){return new ExpenseRules.Snapshot(source("待分摊",null,null),List.of(),count);}
    private static void jsonReject(String name,JsonMapper mapper,String value,Class<?> type){try{mapper.readValue(value,type);throw new AssertionError(name+" accepted");}catch(RuntimeException e){check(name,true);}}
    public static void main(String[] args){var report=new LinkedHashMap<String,Object>();report.put("mode","no-db-no-http");report.put("startedAt",Instant.now().toString());boolean passed=false;try{run();passed=true;}catch(Throwable e){report.put("error",e.getClass().getSimpleName()+": "+e.getMessage());}report.put("status",passed?"passed":"failed");report.put("checks",checks);report.put("finishedAt",Instant.now().toString());System.out.println(Store.encode(report));if(!passed)System.exit(1);}
    private static void run(){
        var finance=actor("员工","财务","门店",1L);ExpenseRules.authorize(finance,1,true);check("finance may record and confirm authorized clinic",true);
        ExpenseRules.authorize(finance,1,false);check("finance may read own clinic",true);
        reject("finance other clinic denied",403,"FORBIDDEN",()->ExpenseRules.authorize(finance,2,true));
        ExpenseRules.authorize(actor("员工","财务","连锁",null),2,true);check("explicit chain finance grant covers clinics",true);
        var hq=actor("员工","总部管理","连锁",null);ExpenseRules.authorize(hq,1,false);check("chain headquarters read only",true);
        reject("headquarters no implicit write",403,"FORBIDDEN",()->ExpenseRules.authorize(hq,1,true));
        reject("clinic-only headquarters not chain reader",403,"FORBIDDEN",()->ExpenseRules.authorize(actor("员工","总部管理","门店",1L),1,false));
        for(String role:List.of("运营人员／店长","前台","牙医","账号管理")){reject(role+" cannot read finance by proximity",403,"FORBIDDEN",()->ExpenseRules.authorize(actor("员工",role,"门店",1L),1,false));reject(role+" cannot write finance",403,"FORBIDDEN",()->ExpenseRules.authorize(actor("员工",role,"门店",1L),1,true));}
        reject("patient with fake finance grant denied",403,"FORBIDDEN",()->ExpenseRules.authorize(actor("患者","财务","门店",1L),1,false));
        check("two-decimal exact money and cent value",ExpenseRules.money("0.01").equals(new BigDecimal("0.01")));
        check("DECIMAL fourteen-two maximum accepted",ExpenseRules.money("999999999999.99").precision()==14);
        for(String bad:List.of("0.00","-0.01","1","1.0","1.001","01.00","+1.00","1e2"," 1.00","1000000000000.00"))reject("invalid original money "+bad,400,"INVALID_INPUT",()->ExpenseRules.money(bad));
        check("valid leap DATE preserved without timezone",ExpenseRules.date("2024-02-29").equals(LocalDate.of(2024,2,29)));
        for(String bad:List.of("2026-02-29","2026-2-01","2026-10-22T00:00Z","0001-01-01"))reject("invalid DATE "+bad,400,"INVALID_INPUT",()->ExpenseRules.date(bad));
        ExpenseRules.creation(creation("0.30"));check("original nullable reason accepted and no default dates",true);
        reject("new expense category rejected",400,"INVALID_INPUT",()->ExpenseRules.creation(new ExpenseRules.Creation("11","VOUCHER-1",1,"房租","2026-10-22","0.30",null)));
        reject("global voucher identity no surrounding whitespace",400,"INVALID_INPUT",()->ExpenseRules.creation(new ExpenseRules.Creation("11"," VOUCHER-1",1,"运营费","2026-10-22","0.30",null)));
        reject("line number positive",400,"INVALID_INPUT",()->ExpenseRules.creation(new ExpenseRules.Creation("11","VOUCHER-1",0,"运营费","2026-10-22","0.30",null)));
        reject("creation reason blank not silently normalized",400,"INVALID_INPUT",()->ExpenseRules.reason(" ",false));
        reject("required confirmation reason",400,"INVALID_INPUT",()->ExpenseRules.reason(null,true));
        ExpenseRules.reason("中".repeat(1000),true);check("reason exact one thousand characters accepted",true);
        reject("reason over bound",400,"INVALID_INPUT",()->ExpenseRules.reason("中".repeat(1001),true));
        var sorted=ExpenseRules.allocations(List.of(rows().getLast(),rows().getFirst()));check("complete allocation set normalized by stable numeric ID",sorted.equals(rows()));
        ExpenseRules.ready(source("待分摊",null,null),List.of(),rows());check("exact decimal ten cents plus twenty cents confirms thirty",true);
        ExpenseRules.ready(source("待确认",null,null),rows(),rows());check("existing pending-confirmation complete identical set accepted",true);
        ExpenseRules.ready(source("待分摊",null,null),rows(),rows());check("existing complete pending allocation cannot be silently edited",true);
        reject("existing pending-confirmation omitted row rejected",409,"EXPENSE_ALLOCATION_SET_CONFLICT",()->ExpenseRules.ready(source("待确认",null,null),rows(),List.of(rows().getFirst())));
        reject("existing pending-confirmation reallocation rejected",409,"EXPENSE_ALLOCATION_SET_CONFLICT",()->ExpenseRules.ready(source("待确认",null,null),rows(),List.of(allocation("21","2","31","0.30","另一个人"))));
        reject("pending-confirmation empty legacy set cannot be filled implicitly",409,"EXPENSE_ALLOCATION_SET_CONFLICT",()->ExpenseRules.ready(source("待确认",null,null),List.of(),rows()));
        reject("incomplete total rejected",409,"EXPENSE_ALLOCATION_TOTAL_MISMATCH",()->ExpenseRules.ready(source("待分摊",null,null),List.of(),List.of(rows().getFirst())));
        reject("zero allocation list cannot confirm positive source",409,"EXPENSE_ALLOCATION_TOTAL_MISMATCH",()->ExpenseRules.ready(source("待分摊",null,null),List.of(),List.of()));
        for(String state:List.of("确认","作废"))reject(state+" not reopened or overwritten",409,"EXPENSE_STATE_INVALID",()->ExpenseRules.ready(source(state,state.equals("确认")?"7":null,state.equals("确认")?"2026-10-22T03:01Z":null),List.of(),rows()));
        reject("nonterminal with historical confirmer blocked",409,"EXPENSE_STATE_INVALID",()->ExpenseRules.ready(source("待分摊","7","2026-10-22T03:01Z"),List.of(),rows()));
        var duplicate=List.of(rows().getFirst(),rows().getFirst());reject("duplicate allocation IDs rejected",400,"INVALID_INPUT",()->ExpenseRules.allocations(duplicate));
        reject("negative original allocation rejected",400,"INVALID_INPUT",()->ExpenseRules.allocations(List.of(allocation("21","1",null,"-0.30","依据"))));
        reject("zero original allocation rejected",400,"INVALID_INPUT",()->ExpenseRules.allocations(List.of(allocation("21","1",null,"0.00","依据"))));
        reject("blank allocation basis rejected",400,"INVALID_INPUT",()->ExpenseRules.allocations(List.of(allocation("21","1",null,"0.30"," "))));
        var hundred=new ArrayList<ExpenseRules.Allocation>();for(int n=1;n<=100;n++)hundred.add(allocation(Integer.toString(n),"1",null,"0.01","分配依据"));check("one hundred complete rows accepted",ExpenseRules.allocations(hundred).size()==100);hundred.add(allocation("101","1",null,"0.01","末项"));reject("last one hundred first row rejected no truncation",413,"EXPENSE_ALLOCATION_LIMIT",()->ExpenseRules.allocations(hundred));
        ExpenseRules.direct(1,2,2,1,2);check("direct treatment same clinic and actual performer accepted",true);
        reject("direct treatment other clinic",409,"EXPENSE_DIRECT_RELATION_INVALID",()->ExpenseRules.direct(1,2,2,3,2));
        reject("direct treatment other dentist",409,"EXPENSE_DIRECT_RELATION_INVALID",()->ExpenseRules.direct(1,3,2,1,2));
        reject("inconsistent treatment appointment dentist",409,"EXPENSE_DIRECT_RELATION_INVALID",()->ExpenseRules.direct(1,2,2,1,3));
        ExpenseRules.creationReplay(1,7,creation("0.30"),source("确认","7","2026-10-22T03:01Z"));check("creation replay compares original immutable fields after confirmation",true);
        reject("natural-key collision other clinic generic conflict",409,"IDEMPOTENCY_CONFLICT",()->ExpenseRules.creationReplay(2,7,creation("0.30"),source("待分摊",null,null)));
        reject("original recorder must match replay",409,"IDEMPOTENCY_CONFLICT",()->ExpenseRules.creationReplay(1,8,creation("0.30"),source("待分摊",null,null)));
        reject("same voucher another proposed ID is not successful retry",409,"IDEMPOTENCY_CONFLICT",()->ExpenseRules.creationReplay(1,7,new ExpenseRules.Creation("12","VOUCHER-1",1,"医疗用品","2026-10-22","0.30",null),source("待分摊",null,null)));
        reject("same identity changed amount conflict",409,"IDEMPOTENCY_CONFLICT",()->ExpenseRules.creationReplay(1,7,creation("0.31"),source("待分摊",null,null)));
        reject("full snapshot audit count catches same-second change back",409,"EXPENSE_SNAPSHOT_CONFLICT",()->ExpenseRules.compare(snapshot("1"),snapshot("3")));
        ExpenseRules.compare(snapshot("1"),snapshot("1"));check("full unchanged snapshot accepted",true);
        var adjusted=new ExpenseRules.Source("12","VOUCHER-2",1,"医疗用品","1","2026-10-22","0.30","待分摊","11","更正","7","2026-10-22T03:00Z",null,null);reject("adjustment source not in original confirmation slice",409,"EXPENSE_ADJUSTMENT_NOT_SUPPORTED",()->ExpenseRules.confirmation(new ExpenseRules.Confirmation(new ExpenseRules.Snapshot(adjusted,List.of(),"0"),rows(),"核对")));
        ExpenseRules.auditSize("x".repeat(49150));check("encoded audit exact forty-eight KiB accepted",true);
        reject("audit JSON overhead counted",413,"EXPENSE_AUDIT_TOO_LARGE",()->ExpenseRules.auditSize("x".repeat(49151)));
        reject("audit UTF8 Chinese bytes counted",413,"EXPENSE_AUDIT_TOO_LARGE",()->ExpenseRules.auditSize("中".repeat(17000)));
        reject("audit JSON escaping counted",413,"EXPENSE_AUDIT_TOO_LARGE",()->ExpenseRules.auditSize("\n".repeat(25000)));
        ExpenseRules.page("待分摊",null,100);check("bounded first page accepted",true);
        reject("unknown status filter rejected",400,"INVALID_INPUT",()->ExpenseRules.page("完成",null,20));reject("zero limit rejected",400,"INVALID_INPUT",()->ExpenseRules.page(null,null,0));reject("one hundred one page limit rejected",400,"INVALID_INPUT",()->ExpenseRules.page(null,null,101));reject("zero cursor not business ID",400,"INVALID_INPUT",()->ExpenseRules.page(null,"0",20));
        var builder=JsonMapper.builder();new JsonConfiguration().strictJson().customize(builder);var mapper=builder.build();String json=Store.encode(creation("0.30"));var decoded=mapper.readValue(json,ExpenseRules.Creation.class);check("strict creation DTO roundtrip preserves exact decimal string",decoded.equals(creation("0.30")));
        jsonReject("numeric amount rejected",mapper,json.replace("\"amount\":\"0.30\"","\"amount\":0.30"),ExpenseRules.Creation.class);jsonReject("numeric source ID rejected",mapper,json.replace("\"expenseId\":\"11\"","\"expenseId\":11"),ExpenseRules.Creation.class);jsonReject("fractional line number rejected",mapper,json.replace("\"lineNo\":1","\"lineNo\":1.5"),ExpenseRules.Creation.class);jsonReject("caller-specified status rejected",mapper,json.substring(0,json.length()-1)+",\"status\":\"确认\"}",ExpenseRules.Creation.class);jsonReject("adjustment input not exposed",mapper,json.substring(0,json.length()-1)+",\"adjustsExpenseId\":\"9\"}",ExpenseRules.Creation.class);
        var confirm=new ExpenseRules.Confirmation(snapshot("1"),rows(),"原录入财务核对确认");var parsed=mapper.readValue(Store.encode(confirm),ExpenseRules.Confirmation.class);check("confirmation snapshot and all rows survive strict DTO roundtrip",ExpenseRules.confirmation(parsed).equals(rows()));jsonReject("caller confirmer not accepted",mapper,Store.encode(confirm).replaceFirst("\\{","{\"confirmedBy\":\"9\","),ExpenseRules.Confirmation.class);
        var canonical=Map.of("actorId","7","expected",snapshot("1"),"allocations",rows(),"reason","核对");ExpenseRules.same(mapper.readTree(Store.encode(canonical)),canonical,"IDEMPOTENCY_CONFLICT");check("actual stored JSON node matches original canonical request",true);reject("different confirming actor cannot replay original request",409,"IDEMPOTENCY_CONFLICT",()->ExpenseRules.same(canonical,Map.of("actorId","8","expected",snapshot("1"),"allocations",rows(),"reason","核对"),"IDEMPOTENCY_CONFLICT"));
    }
}
