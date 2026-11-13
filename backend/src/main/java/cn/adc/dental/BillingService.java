package cn.adc.dental;

import java.math.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class BillingService {
    private final Store db;
    private final EncounterAccess access;
    BillingService(Store db,EncounterAccess access) { this.db=db;this.access=access; }
    private Map<String,Object> invoice(long id) {
        var invoice=db.one("SELECT invoice_id,invoice_no,appointment_id,created_at,status,total_amount,zero_amount_reason,issued_by,issued_at FROM invoices WHERE invoice_id=?",id);
        invoice.put("lines",db.jdbc.queryForList("SELECT invoice_line_id,line_no,treatment_id,name_snapshot,unit_snapshot,units_snapshot,unit_price_snapshot,subtotal FROM invoice_lines WHERE invoice_id=? ORDER BY line_no",id));return invoice;
    }
    private void consistent(Map<String,Object> invoice,List<Map<String,Object>> confirmed,BigDecimal total,String reason) {
        @SuppressWarnings("unchecked") var lines=(List<Map<String,Object>>)invoice.get("lines");
        if(lines.size()!=confirmed.size() || ((BigDecimal)invoice.get("total_amount")).compareTo(total)!=0 || !Objects.equals(invoice.get("zero_amount_reason"),reason))throw ApiError.conflict("INVOICE_CONTENT_CONFLICT");
        var source=new HashMap<Long,Map<String,Object>>();confirmed.forEach(x->source.put(Store.number(x,"treatment_id"),x));
        for(var line:lines) {
            var treatment=source.remove(Store.number(line,"treatment_id"));
            if(treatment==null || !Objects.equals(treatment.get("name_snapshot"),line.get("name_snapshot")) || !Objects.equals(treatment.get("unit_snapshot"),line.get("unit_snapshot"))
                || ((BigDecimal)treatment.get("units")).compareTo((BigDecimal)line.get("units_snapshot"))!=0 || ((BigDecimal)treatment.get("unit_price")).compareTo((BigDecimal)line.get("unit_price_snapshot"))!=0
                || ((BigDecimal)line.get("subtotal")).compareTo(((BigDecimal)treatment.get("units")).multiply((BigDecimal)treatment.get("unit_price")).setScale(2,RoundingMode.HALF_UP))!=0)throw ApiError.conflict("INVOICE_CONTENT_CONFLICT");
        }
    }
    @Transactional
    public Object issue(Identity.Actor actor,long appointmentId) {
        return create(actor,appointmentId,true);
    }
    @Transactional
    public Object draft(Identity.Actor actor,long appointmentId) {
        return create(actor,appointmentId,false);
    }
    private Object create(Identity.Actor actor,long appointmentId,boolean issue) {
        var c=access.lock(actor,appointmentId,EncounterAccess.Mode.FRONT);var a=c.appointment();access.arrived(a);
        if(!"已完成".equals(a.get("treatment_status")) || a.get("checked_by")==null || a.get("completed_at")==null)throw ApiError.conflict("INVALID_STATE");
        var existing=db.jdbc.queryForList("SELECT invoice_id,status FROM invoices WHERE appointment_id=? FOR UPDATE",appointmentId);
        if(issue&&!existing.isEmpty()&&!"发出".equals(existing.getFirst().get("status")))throw ApiError.conflict("INVOICE_REVIEW_REQUIRED");
        var treatments=access.treatments(appointmentId,true);
        BigDecimal total=EncounterService.total(treatments,Store.number(a,"dentist_id"),Store.number(a,"checked_by"));
        String reason=total.signum()==0?Store.required((String)a.get("completion_note"),8000):null;
        var confirmed=treatments.stream().filter(t->"确认".equals(t.get("status"))).toList();
        if(!existing.isEmpty()) {
            var result=invoice(Store.number(existing.getFirst(),"invoice_id"));consistent(result,confirmed,total,reason);
            return Store.json(Map.of("replayed",true,"invoice",withVersion(result)));
        }
        long id=db.id();String no="INV-"+id;
        db.jdbc.update("INSERT INTO invoices(invoice_id,invoice_no,appointment_id,created_at,status,total_amount,zero_amount_reason,issued_by,issued_at) VALUES(?,?,?,?,?,?,?,?,?)",id,no,appointmentId,db.now(),issue?"发出":"草稿",total,reason,issue?actor.id():null,issue?db.now():null);
        int index=0;
        for(var treatment:confirmed)db.jdbc.update("INSERT INTO invoice_lines(invoice_line_id,invoice_id,line_no,treatment_id,name_snapshot,unit_snapshot,units_snapshot,unit_price_snapshot,subtotal) VALUES(?,?,?,?,?,?,?,?,?)",db.id(),id,++index,treatment.get("treatment_id"),treatment.get("name_snapshot"),treatment.get("unit_snapshot"),treatment.get("units"),treatment.get("unit_price"),((BigDecimal)treatment.get("units")).multiply((BigDecimal)treatment.get("unit_price")).setScale(2,RoundingMode.HALF_UP));
        var result=invoice(id);consistent(result,confirmed,total,reason);db.audit(actor.id(),issue?"发出":"创建草稿","invoice",id,null,null,result);
        return Store.json(Map.of("replayed",false,"invoice",withVersion(result)));
    }

    record StatusChange(String expectedVersion,String action,String reason) {}
    record TreatmentCorrection(Integer expectedRowVersion,String units,String unitPrice,String reason) {}

    @Transactional
    public Object correctTreatment(Identity.Actor actor,long appointmentId,long treatmentId,TreatmentCorrection request) {
        if(request.expectedRowVersion()==null||request.expectedRowVersion()<0)throw ApiError.bad();
        var units=EncounterService.decimal(request.units(),10,true);
        var price=EncounterService.decimal(request.unitPrice(),12,false);
        String reason=OrganizationRules.label(request.reason(),500);
        var context=access.lock(actor,appointmentId,EncounterAccess.Mode.DENTIST);
        var appointment=context.appointment();
        access.arrived(appointment);
        access.version(appointment,request.expectedRowVersion());
        if(!"已完成".equals(appointment.get("treatment_status"))||appointment.get("checked_by")==null) {
            throw ApiError.conflict("INVALID_STATE");
        }
        var current=db.jdbc.queryForList("SELECT invoice_id FROM invoices WHERE appointment_id=? FOR UPDATE",appointmentId);
        var bill=current.isEmpty()?null:invoice(Store.number(current.getFirst(),"invoice_id"));
        var lines=access.treatments(appointmentId,true);
        var confirmed=lines.stream().filter(row->"确认".equals(row.get("status"))).toList();
        var original=confirmed.stream().filter(row->Store.number(row,"treatment_id")==treatmentId)
            .findFirst().orElseThrow(()->ApiError.conflict("INVALID_STATE"));
        long dentist=Store.number(appointment,"dentist_id"), signer=Store.number(appointment,"checked_by");
        var oldTotal=EncounterService.total(lines,dentist,signer);
        if(bill!=null)consistent(bill,confirmed,oldTotal,zeroReason(appointment,oldTotal));
        var type=db.one("SELECT allows_fraction FROM treatment_types WHERE treatment_type_id=?",original.get("treatment_type_id"));
        if(!Store.enabled(type,"allows_fraction")&&units.stripTrailingZeros().scale()>0)throw ApiError.bad();
        if(units.equals(original.get("units"))&&price.equals(original.get("unit_price")))return access.reply(appointmentId,true,true);

        db.jdbc.update("UPDATE treatments SET units=?,unit_price=? WHERE treatment_id=?",units,price,treatmentId);
        var updatedLines=access.treatments(appointmentId,true);
        var total=EncounterService.total(updatedLines,dentist,signer);
        var updated=updatedLines.stream().filter(row->Store.number(row,"treatment_id")==treatmentId).findFirst().orElseThrow();
        if(bill!=null){
            long invoiceId=Store.number(bill,"invoice_id");
            var subtotal=units.multiply(price).setScale(2,RoundingMode.HALF_UP);
            db.jdbc.update("UPDATE invoice_lines SET units_snapshot=?,unit_price_snapshot=?,subtotal=? WHERE invoice_id=? AND treatment_id=?",
                units,price,subtotal,invoiceId,treatmentId);
            db.jdbc.update("UPDATE invoices SET total_amount=?,zero_amount_reason=? WHERE invoice_id=?",total,zeroReason(appointment,total),invoiceId);
            var correctedBill=invoice(invoiceId);
            consistent(correctedBill,updatedLines.stream().filter(row->"确认".equals(row.get("status"))).toList(),total,zeroReason(appointment,total));
            db.audit(actor.id(),"治疗更正同步","invoice",invoiceId,reason,bill,correctedBill);
        }
        db.jdbc.update("UPDATE appointments SET row_version=row_version+1 WHERE appointment_id=?",appointmentId);
        db.audit(actor.id(),"完成后更正价量","treatment",treatmentId,reason,original,updated);
        return access.reply(appointmentId,false,true);
    }

    private String zeroReason(Map<String,Object> appointment,BigDecimal total){
        return total.signum()==0?Store.required((String)appointment.get("completion_note"),8000):null;
    }

    @Transactional
    public Object change(Identity.Actor actor,long invoiceId,StatusChange request) {
        var candidate=db.one("SELECT appointment_id FROM invoices WHERE invoice_id=?",invoiceId);
        long appointmentId=Store.number(candidate,"appointment_id");
        var context=access.lock(actor,appointmentId,EncounterAccess.Mode.FRONT);
        var before=withVersion(invoice(invoiceId));
        if(!Objects.equals(before.get("state_version"),request.expectedVersion()))throw ApiError.conflict("ROW_VERSION_CONFLICT");
        String from=(String)before.get("status");
        String action=Store.required(request.action(),12);
        String to=switch(action){
            case "发出" -> "草稿".equals(from)?"发出":null;
            case "作废" -> !"作废".equals(from)?"作废":null;
            case "恢复草稿" -> "作废".equals(from)?"草稿":null;
            default -> throw ApiError.bad();
        };
        if(to==null)throw ApiError.conflict("INVALID_STATE");
        String reason="发出".equals(action)?null:OrganizationRules.label(request.reason(),500);
        if("发出".equals(to)){
            var a=context.appointment();
            access.arrived(a);
            if(!"已完成".equals(a.get("treatment_status")))throw ApiError.conflict("INVALID_STATE");
            var treatments=access.treatments(appointmentId,true);
            var total=EncounterService.total(treatments,Store.number(a,"dentist_id"),Store.number(a,"checked_by"));
            var confirmed=treatments.stream().filter(t->"确认".equals(t.get("status"))).toList();
            consistent(before,confirmed,total,total.signum()==0?Store.required((String)a.get("completion_note"),8000):null);
            db.jdbc.update("UPDATE invoices SET status=?,issued_by=?,issued_at=? WHERE invoice_id=?",to,actor.id(),db.now(),invoiceId);
        }else{
            db.jdbc.update("UPDATE invoices SET status=? WHERE invoice_id=?",to,invoiceId);
        }
        var result=invoice(invoiceId);
        db.audit(actor.id(),action,"invoice",invoiceId,reason,before,result);
        return Store.json(withVersion(result));
    }

    private Map<String,Object> withVersion(Map<String,Object> invoice){
        long version=db.jdbc.queryForObject("""
            SELECT COUNT(*) FROM audit_logs WHERE object_type='invoice' AND object_id=?
              AND action IN ('创建草稿','发出','作废','恢复草稿','治疗更正同步')
            """,Long.class,invoice.get("invoice_id").toString());
        invoice.put("state_version",Long.toString(version));
        return invoice;
    }
    @Transactional
    public Object read(Identity.Actor actor,long invoiceId,boolean patient) {
        var candidate=db.one("SELECT appointment_id FROM invoices WHERE invoice_id=?",invoiceId);
        access.lock(actor,Store.number(candidate,"appointment_id"),patient?EncounterAccess.Mode.PATIENT:EncounterAccess.Mode.STAFF_INVOICE);
        var result=invoice(invoiceId);
        if(patient&&!"发出".equals(result.get("status")))throw ApiError.conflict("INVOICE_NOT_ISSUED");
        db.audit(actor.id(),"读取","invoice",invoiceId);return Store.json(patient?result:withVersion(result));
    }
    @Transactional
    public Object patientEncounter(Identity.Actor actor,long id) {
        var c=access.lock(actor,id,EncounterAccess.Mode.PATIENT);var result=new LinkedHashMap<String,Object>();
        result.put("appointment",c.appointment());result.put("treatments",access.treatments(id,false).stream().filter(t->"确认".equals(t.get("status"))).toList());
        var summary=access.invoiceSummary(id);result.put("invoice",summary!=null&&"发出".equals(summary.get("status"))?invoice(Store.number(summary,"invoice_id")):null);
        db.audit(actor.id(),"读取","own_encounter",id);return Store.json(result);
    }
}
