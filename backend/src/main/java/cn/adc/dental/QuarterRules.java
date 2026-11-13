package cn.adc.dental;

import java.math.*;
import java.time.*;
import java.util.*;

/** Actual report scope, time and amount policies; never models database isolation. */
final class QuarterRules {
    static final int QUERY_SECONDS=15,READ_SECONDS=30,AUDIT_SECONDS=5;
    record Window(int year,int quarter,LocalDate start,LocalDate end,Instant utcStart,Instant utcEnd) {}
    record Scope(boolean headquarters,boolean allClinics,List<Long> clinicIds,Long displayClinic,Long displayDentist) {}
    record Amounts(String revenue,String supplies,String operations,String profit) {}
    record Integrity(long blockingSources,boolean knownSourcesComplete,String status) {}
    private QuarterRules() {}
    static Window window(int year,int quarter){
        try{if(year<1970||year>2038||quarter<1||quarter>4)throw ApiError.bad();var start=LocalDate.of(year,(quarter-1)*3+1,1);var end=start.plusMonths(3);var zone=ZoneId.of("Asia/Shanghai");var a=start.atStartOfDay(zone).toInstant();var b=end.atStartOfDay(zone).toInstant();if(a.getEpochSecond()<1||b.getEpochSecond()>2147483647L)throw ApiError.bad();return new Window(year,quarter,start,end,a,b);}catch(DateTimeException e){throw ApiError.bad();}
    }
    static boolean headquarters(Identity.Actor actor){return actor.kind().equals("员工")&&actor.grants().stream().anyMatch(g->"总部管理".equals(g.get("role_code"))&&"连锁".equals(g.get("scope_kind")));}
    static Scope scope(Identity.Actor actor,boolean headquarters,String clinicId,String dentistId,int limit){
        if(limit<1||limit>100)throw ApiError.bad();Long clinic=clinicId==null?null:Store.parseId(clinicId),dentist=dentistId==null?null:Store.parseId(dentistId);
        if(headquarters){if(!headquarters(actor))throw ApiError.denied();return new Scope(true,true,List.of(),clinic,dentist);}
        if(!actor.kind().equals("员工"))throw ApiError.denied();boolean chain=actor.grants().stream().anyMatch(g->"财务".equals(g.get("role_code"))&&"连锁".equals(g.get("scope_kind")));var ids=new TreeSet<Long>();actor.grants().stream().filter(g->"财务".equals(g.get("role_code"))&&"门店".equals(g.get("scope_kind"))&&g.get("clinic_id") instanceof Number).forEach(g->ids.add(((Number)g.get("clinic_id")).longValue()));
        if(clinic!=null){if(!actor.hasRole("财务",clinic))throw ApiError.denied();return new Scope(false,false,List.of(clinic),clinic,dentist);}if(!chain&&ids.isEmpty())throw ApiError.denied();return new Scope(false,chain,List.copyOf(ids),null,dentist);
    }
    static Amounts amounts(BigDecimal revenue,BigDecimal supplies,BigDecimal operations){return new Amounts(decimal(revenue),decimal(supplies),decimal(operations),decimal(revenue.subtract(supplies).subtract(operations)));}
    static String decimal(BigDecimal value){return value.setScale(2,RoundingMode.UNNECESSARY).toPlainString();}
    static Integrity integrity(List<Map<String,Object>> grouped){long count=0;for(var row:grouped){long n=((Number)row.get("source_count")).longValue();if(n<0)throw new IllegalArgumentException();count=Math.addExact(count,n);}return new Integrity(count,count==0,count==0?"REGISTERED_SOURCES_CHECKED":"INCOMPLETE");}
    static String fund(Scope scope,Integrity integrity,BigDecimal chainProfit){if(!scope.headquarters()||!integrity.knownSourcesComplete())return null;return chainProfit.max(BigDecimal.ZERO).multiply(new BigDecimal("0.10")).setScale(2,RoundingMode.HALF_UP).toPlainString();}
    static String fundReason(Scope scope,Integrity integrity){return !scope.headquarters()?"PARTIAL_SCOPE":!integrity.knownSourcesComplete()?"KNOWN_BLOCKING_SOURCES":"REGISTERED_SOURCES_ESTIMATE";}
}
