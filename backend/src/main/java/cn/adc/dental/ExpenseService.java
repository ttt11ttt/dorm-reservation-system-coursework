package cn.adc.dental;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
class ExpenseService {
    private static final String SOURCE="expense_id,document_no,line_no,kind,clinic_id,occurred_on,amount,status,adjusts_expense_id,reason,recorded_by,recorded_at,confirmed_by,confirmed_at";
    private static final String ALLOCATION="allocation_id,expense_id,dentist_id,treatment_id,amount,allocation_basis";
    private static final String TREATMENT="treatment_id,appointment_id,performer_dentist_id";
    private static final String APPOINTMENT="appointment_id,clinic_id,dentist_id,patient_id";
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final Store db;
    private final TransactionTemplate tx;
    ExpenseService(Store db,PlatformTransactionManager manager){this.db=db;this.tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);}
    private static String string(Object v){return v==null?null:Store.json(v).toString();}
    private Map<String,Object> source(long id,boolean lock){return db.one("SELECT "+SOURCE+" FROM expenses WHERE expense_id=?"+(lock?" FOR UPDATE":""),id);}
    private ExpenseRules.Source value(Map<String,Object> e){return new ExpenseRules.Source(string(e.get("expense_id")),(String)e.get("document_no"),((Number)e.get("line_no")).intValue(),(String)e.get("kind"),string(e.get("clinic_id")),string(e.get("occurred_on")),string(e.get("amount")),(String)e.get("status"),string(e.get("adjusts_expense_id")),(String)e.get("reason"),string(e.get("recorded_by")),string(e.get("recorded_at")),string(e.get("confirmed_by")),string(e.get("confirmed_at")));}
    private List<Map<String,Object>> allocations(long id,boolean lock){var rows=db.jdbc.queryForList("SELECT "+ALLOCATION+" FROM expense_allocations WHERE expense_id=? ORDER BY allocation_id LIMIT 101"+(lock?" FOR UPDATE":""),id);ExpenseRules.limit(rows.size());return rows;}
    private List<ExpenseRules.Allocation> values(List<Map<String,Object>> rows){return rows.stream().map(a->new ExpenseRules.Allocation(string(a.get("allocation_id")),string(a.get("dentist_id")),string(a.get("treatment_id")),string(a.get("amount")),(String)a.get("allocation_basis"))).toList();}
    private ExpenseRules.Snapshot snapshot(Map<String,Object> e,List<Map<String,Object>> rows){
        var args=new ArrayList<Object>();args.add(string(e.get("expense_id")));var clause="object_type='expense' AND object_id=?";
        if(!rows.isEmpty()){clause+=" OR (object_type='expense_allocation' AND object_id IN ("+String.join(",",Collections.nCopies(rows.size(),"?"))+"))";rows.forEach(a->args.add(string(a.get("allocation_id"))));}
        Long count=db.jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE ("+clause+") AND action NOT IN ('读取','导出')",Long.class,args.toArray());return new ExpenseRules.Snapshot(value(e),values(rows),Long.toString(count));
    }
    private Object response(long id,boolean replayed){var e=source(id,false);var rows=allocations(id,false);return Map.of("expense",value(e),"allocations",values(rows),"snapshot",snapshot(e,rows),"replayed",replayed,"quarterReport","NOT_IMPLEMENTED");}
    private void audit(Identity.Actor actor,String action,String type,long id,String reason,Object before,Object after){ExpenseRules.auditSize(before);ExpenseRules.auditSize(after);db.audit(actor.id(),action,type,id,reason,before,after);}
    private void clinic(long clinic){db.one("SELECT clinic_id FROM clinics WHERE clinic_id=? FOR UPDATE",clinic);}

    record ReversalLine(String allocationId,String amount) {}
    record Reversal(String adjustmentId,String occurredOn,String reason,List<ReversalLine> lines) {}

    public Object reverse(Identity.Actor actor,long id,Reversal request){
        long adjustmentId=Store.parseId(request.adjustmentId());
        var occurred=Date.valueOf(ExpenseRules.date(request.occurredOn()));
        String reason=OrganizationRules.label(request.reason(),500);
        if(request.lines()==null||request.lines().isEmpty())throw ApiError.bad();
        ExpenseRules.limit(request.lines().size());
        var amounts=new TreeMap<Long,BigDecimal>();
        for(var line:request.lines()){
            if(line==null||amounts.put(Store.parseId(line.allocationId()),ExpenseRules.money(line.amount()))!=null)throw ApiError.bad();
        }
        var fingerprint=OrganizationService.fingerprint("expense.reversal",new TreeMap<>(Map.of(
            "parent",id,"actor",actor.id(),"date",occurred.toString(),"reason",reason,"amounts",amounts)));
        return tx.execute(status->{
            long clinic=Store.number(source(id,false),"clinic_id");
            ExpenseRules.authorize(actor,clinic,true);
            clinic(clinic);
            var original=source(id,true);
            var prior=db.jdbc.queryForList("""
                SELECT after_value FROM audit_logs WHERE object_type='expense' AND object_id=? AND action='费用冲回'
                """,Long.toString(adjustmentId));
            if(!prior.isEmpty()){
                if(prior.size()!=1||!fingerprint.equals(JSON.readTree(prior.getFirst().get("after_value").toString()).path("fingerprint").asText()))throw ApiError.conflict("IDEMPOTENCY_CONFLICT");
                return response(adjustmentId,true);
            }
            if(original.get("adjusts_expense_id")!=null||!"确认".equals(original.get("status"))
                ||occurred.before(Date.valueOf(original.get("occurred_on").toString())))throw ApiError.conflict("EXPENSE_STATE_INVALID");
            var allocations=allocations(id,true);
            var selected=allocations.stream().filter(row->amounts.containsKey(Store.number(row,"allocation_id"))).toList();
            if(selected.size()!=amounts.size())throw ApiError.bad();
            var total=amounts.values().stream().reduce(BigDecimal.ZERO,BigDecimal::add);
            var balance=db.jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount),0) FROM expenses WHERE adjusts_expense_id=? AND status='确认'
                """,BigDecimal.class,id).add((BigDecimal)original.get("amount"));
            if(total.compareTo(balance)>0)throw new ApiError(409,"EXPENSE_REVERSAL_EXCEEDS_BALANCE","冲回金额超过原费用剩余金额。");
            for(var line:selected){
                long allocation=Store.number(line,"allocation_id");
                var already=db.jdbc.queryForObject("""
                    SELECT COALESCE(SUM(x.amount),0) FROM expense_allocations x JOIN expenses e ON e.expense_id=x.expense_id
                    WHERE e.adjusts_expense_id=? AND e.status='确认' AND x.allocation_basis=?
                    """,BigDecimal.class,id,"冲回原归属 #"+allocation);
                if(amounts.get(allocation).compareTo(((BigDecimal)line.get("amount")).add(already))>0){
                    throw new ApiError(409,"EXPENSE_REVERSAL_EXCEEDS_BALANCE","冲回金额超过该牙医归属的剩余金额。");
                }
            }
            var now=db.now();
            db.jdbc.update("""
                INSERT INTO expenses(expense_id,document_no,line_no,kind,clinic_id,occurred_on,amount,status,
                    adjusts_expense_id,reason,recorded_by,recorded_at,confirmed_by,confirmed_at)
                VALUES(?,?,1,?,?,?,?,'确认',?,?,?,?,?,?)
                """,adjustmentId,"ADJ-"+adjustmentId,original.get("kind"),clinic,occurred,total.negate(),id,reason,actor.id(),now,actor.id(),now);
            for(var line:selected){
                long allocation=Store.number(line,"allocation_id");
                db.jdbc.update("""
                    INSERT INTO expense_allocations(allocation_id,expense_id,dentist_id,treatment_id,amount,allocation_basis)
                    VALUES(?,?,?,?,?,?)
                    """,db.id(),adjustmentId,line.get("dentist_id"),line.get("treatment_id"),amounts.get(allocation).negate(),"冲回原归属 #"+allocation);
            }
            audit(actor,"费用冲回","expense",adjustmentId,reason,null,Map.of("fingerprint",fingerprint,
                "source",value(source(adjustmentId,false)),"allocations",values(allocations(adjustmentId,false))));
            return response(adjustmentId,false);
        });
    }

    public Object voidExpense(Identity.Actor actor,long id,String input){
        String reason=OrganizationRules.label(input,500);
        return tx.execute(status->{
            long clinic=Store.number(source(id,false),"clinic_id");
            ExpenseRules.authorize(actor,clinic,true);
            clinic(clinic);
            var original=source(id,true);
            if("作废".equals(original.get("status")))return response(id,true);
            if(db.jdbc.queryForObject("SELECT COUNT(*) FROM expenses WHERE adjusts_expense_id=? AND status<>'作废'",Long.class,id)>0){
                throw new ApiError(409,"EXPENSE_HAS_ADJUSTMENTS","请先处理该费用的有效调整记录。");
            }
            db.jdbc.update("UPDATE expenses SET status='作废' WHERE expense_id=?",id);
            audit(actor,"费用作废","expense",id,reason,value(original),value(source(id,false)));
            return response(id,false);
        });
    }
    private Object existingCreation(Identity.Actor actor,long clinic,ExpenseRules.Creation request,List<Map<String,Object>> found){
        if(found.size()!=1)throw ApiError.conflict("IDEMPOTENCY_CONFLICT");var e=found.getFirst();ExpenseRules.creationReplay(clinic,actor.id(),request,value(e));var locked=source(Store.number(e,"expense_id"),true);ExpenseRules.creationReplay(clinic,actor.id(),request,value(locked));allocations(Store.number(e,"expense_id"),true);return response(Store.number(e,"expense_id"),true);
    }
    private List<Map<String,Object>> identity(ExpenseRules.Creation c){return db.jdbc.queryForList("SELECT "+SOURCE+" FROM expenses WHERE expense_id=? OR (document_no=? AND line_no=?)",Store.parseId(c.expenseId()),c.documentNo(),c.lineNo());}
    public Object create(Identity.Actor actor,long clinic,ExpenseRules.Creation request){
        ExpenseRules.authorize(actor,clinic,true);ExpenseRules.creation(request);
        try{return tx.execute(status->{clinic(clinic);var found=identity(request);if(!found.isEmpty())return existingCreation(actor,clinic,request,found);long id=Store.parseId(request.expenseId());
            db.jdbc.update("INSERT INTO expenses(expense_id,document_no,line_no,kind,clinic_id,occurred_on,amount,status,adjusts_expense_id,reason,recorded_by,recorded_at) VALUES(?,?,?,?,?,?,?,'待分摊',NULL,?,?,?)",id,request.documentNo(),request.lineNo(),request.kind(),clinic,java.sql.Date.valueOf(ExpenseRules.date(request.occurredOn())),ExpenseRules.money(request.amount()),request.reason(),actor.id(),db.now());
            audit(actor,"费用登记","expense",id,request.reason(),null,Map.of("request",Map.of("clinicId",Long.toString(clinic),"actorId",Long.toString(actor.id()),"body",request),"source",value(source(id,false))));return response(id,false);
        });}catch(DuplicateKeyException collision){
            // execute() has already rolled back and released the failed transaction. Never query inside its catch callback.
            return tx.execute(status->{ExpenseRules.authorize(actor,clinic,true);clinic(clinic);return existingCreation(actor,clinic,request,identity(request));});
        }
    }
    public Object read(Identity.Actor actor,long id){return tx.execute(status->{var candidate=source(id,false);long clinic=Store.number(candidate,"clinic_id");ExpenseRules.authorize(actor,clinic,false);clinic(clinic);var locked=source(id,true);if(Store.number(locked,"clinic_id")!=clinic)throw ApiError.conflict("RETRY_TRANSACTION");ExpenseRules.authorize(actor,clinic,false);allocations(id,true);db.audit(actor.id(),"读取","expense",id);return response(id,false);});}
    public Object list(Identity.Actor actor,long clinic,String filter,String afterId,int limit){ExpenseRules.authorize(actor,clinic,false);ExpenseRules.page(filter,afterId,limit);return tx.execute(status->{db.one("SELECT clinic_id FROM clinics WHERE clinic_id=?",clinic);var args=new ArrayList<Object>();args.add(clinic);args.add(afterId==null?0L:Store.parseId(afterId));String where="clinic_id=? AND expense_id>?";if(filter!=null){where+=" AND status=?";args.add(filter);}args.add(limit+1);var rows=db.jdbc.queryForList("SELECT "+SOURCE+" FROM expenses WHERE "+where+" ORDER BY expense_id LIMIT ?",args.toArray());var items=rows.stream().limit(limit).map(this::value).toList();db.audit(actor.id(),"读取","expense_list",clinic);var result=new LinkedHashMap<String,Object>();result.put("items",items);result.put("hasMore",rows.size()>limit);result.put("nextAfterId",rows.size()>limit?items.getLast().expenseId():null);return result;});}
    private Object canonical(Identity.Actor actor,ExpenseRules.Confirmation request,List<ExpenseRules.Allocation> rows){return Map.of("actorId",Long.toString(actor.id()),"expected",request.expected(),"allocations",rows,"reason",request.reason());}
    private boolean replay(Identity.Actor actor,long id,ExpenseRules.Confirmation request,List<ExpenseRules.Allocation> rows){
        var audits=db.jdbc.queryForList("SELECT after_value FROM audit_logs WHERE object_type='expense' AND object_id=? AND action='费用确认' AND JSON_UNQUOTE(JSON_EXTRACT(before_value,'$.mutationCount'))=?",Long.toString(id),request.expected().mutationCount());
        if(audits.isEmpty())return false;if(audits.size()!=1||audits.getFirst().get("after_value")==null)throw ApiError.conflict("IDEMPOTENCY_CONFLICT");var saved=JSON.readTree(audits.getFirst().get("after_value").toString()).get("request");if(saved==null)throw ApiError.conflict("IDEMPOTENCY_CONFLICT");ExpenseRules.same(saved,canonical(actor,request,rows),"IDEMPOTENCY_CONFLICT");return true;
    }
    private record Direct(Map<String,Object> treatment,Map<String,Object> appointment) {}
    private SortedMap<Long,Direct> directCandidates(long clinic,List<ExpenseRules.Allocation> rows){
        var result=new TreeMap<Long,Direct>();for(var a:rows)if(a.treatmentId()!=null){long id=Store.parseId(a.treatmentId());var known=result.get(id);if(known==null){var t=db.one("SELECT "+TREATMENT+" FROM treatments WHERE treatment_id=?",id);var appointment=db.one("SELECT "+APPOINTMENT+" FROM appointments WHERE appointment_id=?",t.get("appointment_id"));known=new Direct(t,appointment);result.put(id,known);}ExpenseRules.direct(clinic,Store.parseId(a.dentistId()),Store.number(known.treatment(),"performer_dentist_id"),Store.number(known.appointment(),"clinic_id"),Store.number(known.appointment(),"dentist_id"));}return result;
    }
    private Object confirmTx(Identity.Actor actor,long id,ExpenseRules.Confirmation request,List<ExpenseRules.Allocation> requested){
        var candidate=source(id,false);long clinic=Store.number(candidate,"clinic_id");ExpenseRules.authorize(actor,clinic,true);ExpenseRules.original(value(candidate));
        if("确认".equals(candidate.get("status"))){clinic(clinic);var locked=source(id,true);if(Store.number(locked,"clinic_id")!=clinic)throw ApiError.conflict("RETRY_TRANSACTION");allocations(id,true);if(!replay(actor,id,request,requested))throw ApiError.conflict("EXPENSE_STATE_INVALID");return response(id,true);}
        var existing=allocations(id,false);var all=new ArrayList<>(values(existing));all.addAll(requested);var direct=directCandidates(clinic,all);
        var dentists=new TreeSet<Long>();all.forEach(a->dentists.add(Store.parseId(a.dentistId())));var patients=new TreeSet<Long>();var appointments=new TreeSet<Long>();for(var d:direct.values()){patients.add(Store.number(d.appointment(),"patient_id"));appointments.add(Store.number(d.appointment(),"appointment_id"));}
        clinic(clinic);for(long dentist:dentists)db.one("SELECT dentist_id FROM dentists WHERE dentist_id=? FOR UPDATE",dentist);for(long patient:patients)db.one("SELECT patient_id FROM patients WHERE patient_id=? FOR UPDATE",patient);var lockedAppointments=new TreeMap<Long,Map<String,Object>>();for(long appointment:appointments)lockedAppointments.put(appointment,db.one("SELECT "+APPOINTMENT+" FROM appointments WHERE appointment_id=? FOR UPDATE",appointment));
        var locked=source(id,true);if(Store.number(locked,"clinic_id")!=clinic)throw ApiError.conflict("RETRY_TRANSACTION");var lockedAllocations=allocations(id,true);
        if(replay(actor,id,request,requested)){if(!"确认".equals(locked.get("status")))throw ApiError.conflict("EXPENSE_STATE_INVALID");return response(id,true);}ExpenseRules.same(values(existing),values(lockedAllocations),"RETRY_TRANSACTION");
        for(var entry:direct.entrySet()){var t=db.one("SELECT "+TREATMENT+" FROM treatments WHERE treatment_id=? FOR UPDATE",entry.getKey());ExpenseRules.same(entry.getValue().treatment(),t,"RETRY_TRANSACTION");ExpenseRules.same(entry.getValue().appointment(),lockedAppointments.get(Store.number(t,"appointment_id")),"RETRY_TRANSACTION");}
        var before=snapshot(locked,lockedAllocations);ExpenseRules.compare(request.expected(),before);ExpenseRules.ready(value(locked),values(lockedAllocations),requested);
        if(lockedAllocations.isEmpty())for(var a:requested){long allocation=Store.parseId(a.allocationId());db.jdbc.update("INSERT INTO expense_allocations(allocation_id,expense_id,dentist_id,treatment_id,amount,allocation_basis) VALUES(?,?,?,?,?,?)",allocation,id,Store.parseId(a.dentistId()),a.treatmentId()==null?null:Store.parseId(a.treatmentId()),ExpenseRules.money(a.amount()),a.allocationBasis());audit(actor,"费用归属登记","expense_allocation",allocation,request.reason(),null,a);}
        var now=db.now();int updated=db.jdbc.update("UPDATE expenses SET status='确认',confirmed_by=?,confirmed_at=? WHERE expense_id=? AND status=? AND confirmed_by IS NULL AND confirmed_at IS NULL",actor.id(),now,id,locked.get("status"));if(updated!=1)throw ApiError.conflict("RETRY_TRANSACTION");
        audit(actor,"费用确认","expense",id,request.reason(),before,Map.of("request",canonical(actor,request,requested),"source",value(source(id,false)),"allocations",values(allocations(id,false))));return response(id,false);
    }
    public Object confirm(Identity.Actor actor,long id,ExpenseRules.Confirmation request){var requested=ExpenseRules.confirmation(request);try{return tx.execute(status->confirmTx(actor,id,request,requested));}catch(DuplicateKeyException collision){throw ApiError.conflict("IDEMPOTENCY_CONFLICT");}}
}
