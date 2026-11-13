package cn.adc.dental;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import tools.jackson.databind.json.JsonMapper;

/** Original expense policy only; no adjustments, quarter close or payment. */
final class ExpenseRules {
    static final int ALLOCATION_LIMIT=100,AUDIT_BYTES=48*1024;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    record Creation(String expenseId,String documentNo,Integer lineNo,String kind,String occurredOn,String amount,String reason){@JsonAnySetter void unknown(String k,Object v){throw ApiError.bad();}}
    record Allocation(String allocationId,String dentistId,String treatmentId,String amount,String allocationBasis){@JsonAnySetter void unknown(String k,Object v){throw ApiError.bad();}}
    record Source(String expenseId,String documentNo,Integer lineNo,String kind,String clinicId,String occurredOn,String amount,String status,String adjustsExpenseId,String reason,String recordedBy,String recordedAt,String confirmedBy,String confirmedAt){@JsonAnySetter void unknown(String k,Object v){throw ApiError.bad();}}
    record Snapshot(Source source,List<Allocation> allocations,String mutationCount){@JsonAnySetter void unknown(String k,Object v){throw ApiError.bad();}}
    record Confirmation(Snapshot expected,List<Allocation> allocations,String reason){@JsonAnySetter void unknown(String k,Object v){throw ApiError.bad();}}
    private ExpenseRules() {}
    static void authorize(Identity.Actor actor,long clinic,boolean write){boolean headquarters=actor.kind().equals("员工")&&actor.grants().stream().anyMatch(g->"总部管理".equals(g.get("role_code"))&&"连锁".equals(g.get("scope_kind")));if(!actor.hasRole("财务",clinic)&&(write||!headquarters))throw ApiError.denied();}
    static BigDecimal money(String value){if(value==null||!value.matches("(?:0|[1-9][0-9]{0,11})\\.[0-9]{2}"))throw ApiError.bad();var amount=new BigDecimal(value);if(amount.signum()<=0)throw ApiError.bad();return amount;}
    static LocalDate date(String value){try{if(value==null||!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw ApiError.bad();var result=LocalDate.parse(value);if(result.getYear()<1000||result.getYear()>9999)throw ApiError.bad();return result;}catch(RuntimeException e){throw ApiError.bad();}}
    static void reason(String value,boolean required){if(required||value!=null)Store.required(value,1000);}
    static void creation(Creation c){Store.parseId(c.expenseId());Store.required(c.documentNo(),64);if(!c.documentNo().equals(c.documentNo().strip()))throw ApiError.bad();if(c.lineNo()==null||c.lineNo()<1||!Set.of("医疗用品","运营费").contains(Objects.toString(c.kind(),"")))throw ApiError.bad();date(c.occurredOn());money(c.amount());reason(c.reason(),false);}
    static void limit(int count){if(count>ALLOCATION_LIMIT)throw new ApiError(413,"EXPENSE_ALLOCATION_LIMIT","单来源归属超过100项，未截断办理");}
    static List<Allocation> allocations(List<Allocation> input){if(input==null)throw ApiError.bad();limit(input.size());var ids=new HashSet<String>();for(var a:input){if(a==null)throw ApiError.bad();Store.parseId(a.allocationId());Store.parseId(a.dentistId());if(a.treatmentId()!=null)Store.parseId(a.treatmentId());money(a.amount());Store.required(a.allocationBasis(),255);if(!ids.add(a.allocationId()))throw ApiError.bad();}return input.stream().sorted(Comparator.comparingLong(a->Store.parseId(a.allocationId()))).toList();}
    static void expected(Snapshot s){
        if(s==null||s.source()==null)throw ApiError.bad();var v=s.source();creation(new Creation(v.expenseId(),v.documentNo(),v.lineNo(),v.kind(),v.occurredOn(),v.amount(),v.reason()));Store.parseId(v.clinicId());Store.parseId(v.recordedBy());Store.offset(v.recordedAt());
        if(v.adjustsExpenseId()!=null)Store.parseId(v.adjustsExpenseId());if(!Set.of("待分摊","待确认","确认","作废").contains(Objects.toString(v.status(),"")))throw ApiError.bad();if(v.confirmedBy()!=null)Store.parseId(v.confirmedBy());if(v.confirmedAt()!=null)Store.offset(v.confirmedAt());allocations(s.allocations());
        try{if(s.mutationCount()==null||!s.mutationCount().matches("0|[1-9][0-9]{0,18}")||Long.parseLong(s.mutationCount())<0)throw ApiError.bad();}catch(NumberFormatException e){throw ApiError.bad();}
    }
    static List<Allocation> confirmation(Confirmation c){if(c==null||c.expected()==null||c.expected().source()==null)throw ApiError.bad();original(c.expected().source());expected(c.expected());reason(c.reason(),true);return allocations(c.allocations());}
    static void same(Object saved,Object request,String code){if(!JSON.valueToTree(Store.json(saved)).equals(JSON.valueToTree(Store.json(request))))throw ApiError.conflict(code);}
    static void compare(Snapshot expected,Snapshot actual){same(expected,actual,"EXPENSE_SNAPSHOT_CONFLICT");}
    static void original(Source s){if(s.adjustsExpenseId()!=null)throw ApiError.conflict("EXPENSE_ADJUSTMENT_NOT_SUPPORTED");}
    static void ready(Source s,List<Allocation> existing,List<Allocation> requested){
        original(s);if(!Set.of("待分摊","待确认").contains(s.status())||s.confirmedBy()!=null||s.confirmedAt()!=null)throw ApiError.conflict("EXPENSE_STATE_INVALID");
        if(s.status().equals("待确认")||!existing.isEmpty())same(existing,requested,"EXPENSE_ALLOCATION_SET_CONFLICT");
        var total=requested.stream().map(a->money(a.amount())).reduce(BigDecimal.ZERO,BigDecimal::add);if(total.compareTo(money(s.amount()))!=0)throw ApiError.conflict("EXPENSE_ALLOCATION_TOTAL_MISMATCH");
    }
    static void direct(long expenseClinic,long allocationDentist,long treatmentDentist,long appointmentClinic,long appointmentDentist){if(expenseClinic!=appointmentClinic||allocationDentist!=treatmentDentist||treatmentDentist!=appointmentDentist)throw ApiError.conflict("EXPENSE_DIRECT_RELATION_INVALID");}
    static void creationReplay(long clinic,long actor,Creation request,Source stored){
        if(!Long.toString(clinic).equals(stored.clinicId())||!Long.toString(actor).equals(stored.recordedBy())||stored.adjustsExpenseId()!=null)throw ApiError.conflict("IDEMPOTENCY_CONFLICT");
        same(request,new Creation(stored.expenseId(),stored.documentNo(),stored.lineNo(),stored.kind(),stored.occurredOn(),stored.amount(),stored.reason()),"IDEMPOTENCY_CONFLICT");
    }
    static void auditSize(Object value){if(value!=null&&Store.encode(value).getBytes(StandardCharsets.UTF_8).length>AUDIT_BYTES)throw new ApiError(413,"EXPENSE_AUDIT_TOO_LARGE","费用审计超过48KiB，未截断保存");}
    static void page(String status,String afterId,int limit){if(status!=null&&!Set.of("待分摊","待确认","确认","作废").contains(status))throw ApiError.bad();if(afterId!=null)Store.parseId(afterId);if(limit<1||limit>100)throw ApiError.bad();}
}
