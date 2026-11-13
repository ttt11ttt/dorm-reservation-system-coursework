package cn.adc.dental;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/staff/business-audit")
class BusinessAuditApi {
    private final Store db;
    private final Identity identity;

    BusinessAuditApi(Store db,Identity identity) {
        this.db=db;
        this.identity=identity;
    }

    @GetMapping
    Object read(@RequestHeader(value="Authorization",required=false) String header,
                @RequestParam String type,@RequestParam String objectId) {
        var actor=identity.require(header);
        OrganizationRules.chainOnly(actor);
        long id=Store.parseId(objectId);
        // Explicit allowlist of business objects. Account and identity records use separate flows.
        switch(type){
            case "org_schedule", "org_opening", "org_clinic" -> {}
            case "appointment" -> actor.front(clinic("SELECT clinic_id FROM appointments WHERE appointment_id=?",id));
            case "guest_reservation" -> actor.front(clinic("SELECT clinic_id FROM guest_reservations WHERE guest_reservation_id=?",id));
            case "treatment" -> actor.front(clinic("""
                SELECT a.clinic_id FROM appointments a JOIN treatments t ON t.appointment_id=a.appointment_id
                WHERE t.treatment_id=?
                """,id));
            case "invoice" -> actor.front(clinic("""
                SELECT a.clinic_id FROM appointments a JOIN invoices i ON i.appointment_id=a.appointment_id
                WHERE i.invoice_id=?
                """,id));
            case "expense" -> ExpenseRules.authorize(actor,clinic("SELECT clinic_id FROM expenses WHERE expense_id=?",id),false);
            default -> throw ApiError.bad();
        }
        var rows=db.jdbc.queryForList("""
            SELECT audit_id,actor_account_id,action,reason,before_value,after_value,happened_at
            FROM audit_logs WHERE object_type=? AND object_id=?
              AND (before_value IS NOT NULL OR after_value IS NOT NULL)
              AND action NOT IN ('读取','门店读取','实际开放读取','既定出诊读取')
            ORDER BY happened_at DESC,audit_id DESC LIMIT 30
            """,type,Long.toString(id));
        db.audit(actor.id(),"读取业务变更","business_audit",id);
        return Store.json(rows);
    }

    private long clinic(String sql,long id){
        return Store.number(db.one(sql,id),"clinic_id");
    }
}
