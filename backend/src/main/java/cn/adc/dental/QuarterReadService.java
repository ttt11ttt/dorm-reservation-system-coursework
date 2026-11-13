package cn.adc.dental;

import java.math.BigDecimal;
import java.sql.Types;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class QuarterReadService {
    private final Store db;
    private final TransactionTemplate readTx,auditTx;
    private final NamedParameterJdbcTemplate query;
    QuarterReadService(Store db,PlatformTransactionManager manager){
        this.db=db;readTx=new TransactionTemplate(manager);readTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);readTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);readTx.setReadOnly(true);readTx.setTimeout(QuarterRules.READ_SECONDS);
        auditTx=new TransactionTemplate(manager);auditTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);auditTx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);auditTx.setTimeout(QuarterRules.AUDIT_SECONDS);
        var jdbc=new JdbcTemplate(Objects.requireNonNull(db.jdbc.getDataSource()));jdbc.setQueryTimeout(QuarterRules.QUERY_SECONDS);query=new NamedParameterJdbcTemplate(jdbc);
    }
    private static BigDecimal decimal(Map<String,Object> row,String key){Object value=row.get(key);return value instanceof BigDecimal d?d:new BigDecimal(value.toString());}
    private static QuarterRules.Amounts amounts(Map<String,Object> row){return QuarterRules.amounts(decimal(row,"revenue"),decimal(row,"supplies"),decimal(row,"operations"));}
    private Map<String,Object> build(QuarterRules.Scope scope,QuarterRules.Window w,int limit){
        var started=db.now();var params=new MapSqlParameterSource().addValue("utcStart",Timestamp.from(w.utcStart())).addValue("utcEnd",Timestamp.from(w.utcEnd())).addValue("dateStart",java.sql.Date.valueOf(w.start())).addValue("dateEnd",java.sql.Date.valueOf(w.end())).addValue("displayClinic",scope.displayClinic(),Types.BIGINT).addValue("displayDentist",scope.displayDentist(),Types.BIGINT).addValue("pageSize",limit+1);
        String allowed=scope.allClinics()?"1=1":"clinic_id IN (:scopeClinics)";if(!scope.allClinics())params.addValue("scopeClinics",scope.clinicIds());
        // This first ordinary SELECT on an InnoDB business table establishes the read view, even when empty.
        var clinics=query.queryForList("SELECT clinic_id FROM clinics WHERE "+allowed+" ORDER BY clinic_id",params);if(!scope.headquarters()&&scope.displayClinic()!=null&&clinics.isEmpty())throw new ApiError(404,"NOT_FOUND","门店不存在");
        String sql=QuarterSql.COMMON.formatted(allowed);
        var full=query.queryForMap(sql+QuarterSql.TOTALS,params);var display=query.queryForMap(sql+QuarterSql.TOTALS+" WHERE "+QuarterSql.DISPLAY,params);
        var grouped=query.queryForList(sql+"SELECT source_type,reason,COUNT(*) source_count FROM anomalies GROUP BY source_type,reason ORDER BY source_type,reason",params);var integrity=QuarterRules.integrity(grouped);
        var sources=query.queryForMap(sql+"SELECT (SELECT COUNT(*) FROM invoice_check) completion_sources,(SELECT COUNT(*) FROM invoice_check WHERE reason IS NULL) valid_invoice_sources,(SELECT COUNT(*) FROM expense_check) expense_sources,(SELECT COUNT(*) FROM expense_check WHERE reason IS NULL) valid_expense_sources",params);
        var contribution=query.queryForList(sql+"SELECT clinic_id,dentist_id,revenue,supplies,operations FROM contribution WHERE "+QuarterSql.DISPLAY+" ORDER BY clinic_id,dentist_id LIMIT :pageSize",params);
        var exceptions=query.queryForList(sql+"SELECT source_type,source_id,clinic_id,completed_at,occurred_on,reason FROM anomalies ORDER BY source_type,clinic_id,source_id LIMIT :pageSize",params);
        var items=new ArrayList<Map<String,Object>>();for(var row:contribution.stream().limit(limit).toList())items.add(Map.of("clinicId",Long.toString(Store.number(row,"clinic_id")),"dentistId",Long.toString(Store.number(row,"dentist_id")),"amounts",amounts(row)));
        var exceptionsOut=new ArrayList<Map<String,Object>>();for(var row:exceptions.stream().limit(limit).toList()){var out=new LinkedHashMap<String,Object>();out.put("sourceType",row.get("source_type"));out.put("sourceId",Long.toString(Store.number(row,"source_id")));out.put("clinicId",Long.toString(Store.number(row,"clinic_id")));out.put("completedAt",row.get("completed_at"));out.put("occurredOn",row.get("occurred_on"));out.put("reason",row.get("reason"));exceptionsOut.add(out);}
        var output=new LinkedHashMap<String,Object>();output.put("year",w.year());output.put("quarter",w.quarter());output.put("startDate",w.start().toString());output.put("endDateExclusive",w.end().toString());output.put("utcStart",w.utcStart().toString());output.put("utcEndExclusive",w.utcEnd().toString());output.put("readStartedAt",started);output.put("computedAt",db.now());
        var authorizedClinics=clinics.stream().map(row->Long.toString(Store.number(row,"clinic_id"))).toList();var displayScope=new LinkedHashMap<String,Object>();displayScope.put("clinicId",scope.displayClinic()==null?null:Long.toString(scope.displayClinic()));displayScope.put("dentistId",scope.displayDentist()==null?null:Long.toString(scope.displayDentist()));output.put("displayScope",displayScope);output.put("integrityClinicIds",authorizedClinics);output.put("scopeMode",scope.headquarters()?"HEADQUARTERS_CHAIN":scope.allClinics()?"FINANCE_CHAIN_PARTIAL_FUND":"FINANCE_AUTHORIZED_CLINICS");
        output.put("chainTotals",scope.headquarters()?amounts(full):null);output.put("scopeTotals",amounts(display));output.put("contributionCount",display.get("contribution_count"));output.put("integrity",integrity);output.put("anomalyCounts",grouped);output.put("sourceCounts",sources);output.put("companyFund",QuarterRules.fund(scope,integrity,decimal(full,"revenue").subtract(decimal(full,"supplies")).subtract(decimal(full,"operations"))));output.put("fundReason",QuarterRules.fundReason(scope,integrity));
        output.put("contributions",Map.of("items",items,"hasMore",contribution.size()>limit,"limit",limit));output.put("anomalies",Map.of("items",exceptionsOut,"hasMore",exceptions.size()>limit,"limit",limit));output.put("pagination","FIRST_PAGE_ONLY");output.put("laterPageConsistency","NEW_REALTIME_VIEW");output.put("basis","REGISTERED_VALID_SOURCES_REALTIME_ONLY");return output;
    }
    public Object read(Identity.Actor actor,boolean headquarters,int year,int quarter,String clinicId,String dentistId,int limit){
        var scope=QuarterRules.scope(actor,headquarters,clinicId,dentistId,limit);var window=QuarterRules.window(year,quarter);Map<String,Object> result;
        try{result=Objects.requireNonNull(readTx.execute(status->build(scope,window,limit)));}catch(QueryTimeoutException|TransactionTimedOutException error){throw new ApiError(503,"QUARTER_READ_TIMEOUT","季度读取超时，未返回部分结果");}
        // Read view has ended before the separate audit write starts. Audit failure prevents returning this result.
        auditTx.executeWithoutResult(status->{var evidence=new LinkedHashMap<String,Object>();evidence.put("year",year);evidence.put("quarter",quarter);evidence.put("scopeMode",result.get("scopeMode"));evidence.put("displayScope",result.get("displayScope"));evidence.put("clinicCount",((List<?>)result.get("integrityClinicIds")).size());evidence.put("readStartedAt",result.get("readStartedAt"));evidence.put("computedAt",result.get("computedAt"));evidence.put("integrity",result.get("integrity"));evidence.put("anomalyCounts",result.get("anomalyCounts"));db.audit(actor.id(),"读取","quarter_report",actor.id(),"已登记来源实时核算，未封账",null,evidence);});
        if(headquarters&&clinicId==null&&dentistId==null){
            String token=confirmationToken(result,window);
            result.put("confirmationToken",token);
            var previous=lastConfirmation(periodId(year,quarter));
            if(!previous.isEmpty()){
                var row=previous.getFirst();
                result.put("confirmation",Map.of("at",row.get("happened_at"),"actor",row.get("actor_account_id"),
                    "matches",Objects.equals(token,row.get("snapshot_token"))));
            }
        }
        return Store.json(result);
    }

    record Confirmation(int year,int quarter,String token,String reason) {}

    Object confirm(Identity.Actor actor,Confirmation request){
        if(actor.readOnly()||!QuarterRules.headquarters(actor))throw ApiError.denied();
        String reason=OrganizationRules.label(request.reason(),500);
        var window=QuarterRules.window(request.year(),request.quarter());
        var scope=QuarterRules.scope(actor,true,null,null,20);
        var result=Objects.requireNonNull(readTx.execute(tx->build(scope,window,20)));
        String token=confirmationToken(result,window);
        if(token==null)throw new ApiError(409,"QUARTER_NOT_READY","仅可确认已经结束、且来源核对齐全的全连锁季度。");
        if(!token.equals(request.token()))throw new ApiError(409,"QUARTER_CHANGED","季度总额或来源计数已变化，请重新查询后核对。");
        long period=periodId(request.year(),request.quarter());
        return auditTx.execute(tx->{
            db.jdbc.queryForList("SELECT clinic_id FROM clinics ORDER BY clinic_id FOR UPDATE");
            var previous=lastConfirmation(period);
            if(!previous.isEmpty()&&token.equals(previous.getFirst().get("snapshot_token")))return Map.of("confirmed",true);
            db.audit(actor.id(),"确认季度汇总","quarter_confirmation",period,reason,null,Map.of(
                "year",request.year(),"quarter",request.quarter(),"token",token,"chainTotals",result.get("chainTotals"),
                "companyFund",result.get("companyFund"),"sourceCounts",result.get("sourceCounts"),"computedAt",result.get("computedAt")));
            return Map.of("confirmed",true);
        });
    }

    private String confirmationToken(Map<String,Object> report,QuarterRules.Window window){
        var integrity=(QuarterRules.Integrity)report.get("integrity");
        if(!integrity.knownSourcesComplete()||window.utcEnd().isAfter(db.clock.instant()))return null;
        return OrganizationService.fingerprint("quarter.confirmation",new TreeMap<>(Map.of(
            "year",window.year(),"quarter",window.quarter(),"clinics",report.get("integrityClinicIds"),
            "chainTotals",report.get("chainTotals"),"companyFund",report.get("companyFund"),"sourceCounts",report.get("sourceCounts"))));
    }

    private long periodId(int year,int quarter){return year*10L+quarter;}
    private List<Map<String,Object>> lastConfirmation(long period){
        return db.jdbc.queryForList("""
            SELECT happened_at,actor_account_id,JSON_UNQUOTE(JSON_EXTRACT(after_value,'$.token')) AS snapshot_token
            FROM audit_logs WHERE object_type='quarter_confirmation' AND object_id=? AND action='确认季度汇总'
            ORDER BY happened_at DESC,audit_id DESC LIMIT 1
            """,Long.toString(period));
    }
}
