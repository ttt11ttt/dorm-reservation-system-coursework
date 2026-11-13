package cn.adc.dental;

/** Ordinary consistent SELECTs only. No business LIMIT in source validation or aggregates. */
final class QuarterSql {
    private QuarterSql() {}
    static final String COMMON="""
        WITH selected_clinics AS (
          SELECT clinic_id FROM clinics WHERE %s
        ), completed AS (
          SELECT a.appointment_id,a.clinic_id,a.dentist_id,a.completed_at,a.attended,a.arrived_at,
                 a.appointment_status,a.treatment_status,a.completion_note
          FROM appointments a JOIN selected_clinics s ON s.clinic_id=a.clinic_id
          WHERE (a.completed_at>=:utcStart AND a.completed_at<:utcEnd)
             OR (a.treatment_status='已完成' AND a.completed_at IS NULL)
        ), treatment_stats AS (
          SELECT t.appointment_id,
            SUM(CASE WHEN t.status='确认' THEN 1 ELSE 0 END) confirmed_count,
            SUM(CASE WHEN t.status='草稿' THEN 1 ELSE 0 END) draft_count,
            SUM(CASE WHEN t.status='确认' AND NOT EXISTS (
                SELECT 1 FROM invoice_lines l JOIN invoices i ON i.invoice_id=l.invoice_id
                WHERE i.appointment_id=t.appointment_id AND l.treatment_id=t.treatment_id
              ) THEN 1 ELSE 0 END) missing_count
          FROM treatments t JOIN completed c ON c.appointment_id=t.appointment_id GROUP BY t.appointment_id
        ), line_stats AS (
          SELECT i.invoice_id,COUNT(l.invoice_line_id) line_count,COALESCE(SUM(l.subtotal),0) line_total,
            SUM(CASE WHEN l.invoice_line_id IS NOT NULL AND (
              t.treatment_id IS NULL OR t.appointment_id<>i.appointment_id OR t.status<>'确认'
              OR t.performer_dentist_id<>c.dentist_id OR t.confirmed_by IS NULL OR t.confirmed_at IS NULL
              OR NOT(CAST(l.name_snapshot AS BINARY)<=>CAST(t.name_snapshot AS BINARY))
              OR NOT(CAST(l.unit_snapshot AS BINARY)<=>CAST(t.unit_snapshot AS BINARY))
              OR NOT(l.units_snapshot<=>t.units) OR NOT(l.unit_price_snapshot<=>t.unit_price)
              OR l.units_snapshot<=0 OR l.unit_price_snapshot<0
              OR l.subtotal<>ROUND(l.units_snapshot*l.unit_price_snapshot,2)
            ) THEN 1 ELSE 0 END) bad_count
          FROM completed c JOIN invoices i ON i.appointment_id=c.appointment_id
          LEFT JOIN invoice_lines l ON l.invoice_id=i.invoice_id
          LEFT JOIN treatments t ON t.treatment_id=l.treatment_id GROUP BY i.invoice_id
        ), invoice_check AS (
          SELECT c.appointment_id,c.clinic_id,c.dentist_id,c.completed_at,i.invoice_id,
            CASE
              WHEN c.completed_at IS NULL THEN 'COMPLETION_DATE_MISSING'
              WHEN c.treatment_status<>'已完成' OR c.appointment_status<>'已到诊' OR c.attended<>1
                OR c.arrived_at IS NULL OR c.completed_at<c.arrived_at
                OR c.completion_note IS NULL OR CHAR_LENGTH(TRIM(c.completion_note))=0 THEN 'COMPLETION_INVALID'
              WHEN i.invoice_id IS NULL OR i.status<>'发出' THEN 'INVOICE_NOT_ISSUED'
              WHEN i.issued_by IS NULL OR i.issued_at IS NULL OR i.total_amount<0
                OR COALESCE(t.draft_count,0)<>0 OR COALESCE(t.missing_count,0)<>0
                OR COALESCE(l.bad_count,0)<>0 OR COALESCE(l.line_count,0)<>COALESCE(t.confirmed_count,0)
                OR COALESCE(l.line_total,0)<>i.total_amount
                OR (i.total_amount=0 AND (i.zero_amount_reason IS NULL
                    OR CHAR_LENGTH(TRIM(i.zero_amount_reason))=0
                    OR NOT(CAST(i.zero_amount_reason AS BINARY)<=>CAST(c.completion_note AS BINARY))))
              THEN 'INVOICE_SOURCE_INVALID'
              ELSE NULL END reason
          FROM completed c LEFT JOIN invoices i ON i.appointment_id=c.appointment_id
          LEFT JOIN treatment_stats t ON t.appointment_id=c.appointment_id
          LEFT JOIN line_stats l ON l.invoice_id=i.invoice_id
        ), valid_revenue AS (
          SELECT v.clinic_id,COALESCE(t.performer_dentist_id,v.dentist_id) dentist_id,
                 COALESCE(SUM(l.subtotal),0) revenue
          FROM invoice_check v LEFT JOIN invoice_lines l ON l.invoice_id=v.invoice_id
          LEFT JOIN treatments t ON t.treatment_id=l.treatment_id
          WHERE v.reason IS NULL GROUP BY v.clinic_id,COALESCE(t.performer_dentist_id,v.dentist_id)
        ), expense_scope AS (
          SELECT e.expense_id,e.clinic_id,e.occurred_on,e.kind,e.amount,e.status,e.adjusts_expense_id,
                 e.reason adjustment_reason,e.confirmed_by,e.confirmed_at
          FROM expenses e JOIN selected_clinics s ON s.clinic_id=e.clinic_id
          WHERE (e.occurred_on>=:dateStart AND e.occurred_on<:dateEnd) OR e.occurred_on IS NULL
        ), allocation_stats AS (
          SELECT e.expense_id,COUNT(x.allocation_id) allocation_count,COALESCE(SUM(x.amount),0) allocated,
            SUM(CASE WHEN x.allocation_id IS NOT NULL AND (
              x.amount=0 OR SIGN(x.amount)<>SIGN(e.amount)
              OR x.allocation_basis IS NULL OR CHAR_LENGTH(TRIM(x.allocation_basis))=0
              OR d.dentist_id IS NULL
              OR (x.treatment_id IS NOT NULL AND (t.treatment_id IS NULL OR a.appointment_id IS NULL
                  OR a.clinic_id<>e.clinic_id OR t.performer_dentist_id<>x.dentist_id
                  OR a.dentist_id<>t.performer_dentist_id))
            ) THEN 1 ELSE 0 END) bad_count
          FROM expense_scope e LEFT JOIN expense_allocations x ON x.expense_id=e.expense_id
          LEFT JOIN dentists d ON d.dentist_id=x.dentist_id
          LEFT JOIN treatments t ON t.treatment_id=x.treatment_id
          LEFT JOIN appointments a ON a.appointment_id=t.appointment_id GROUP BY e.expense_id
        ), expense_check AS (
          SELECT e.expense_id,e.clinic_id,e.occurred_on,e.kind,
            CASE
              WHEN e.occurred_on IS NULL THEN 'EXPENSE_DATE_MISSING'
              WHEN e.status<>'确认' THEN 'EXPENSE_NOT_CONFIRMED'
              WHEN e.confirmed_by IS NULL OR e.confirmed_at IS NULL
                OR e.kind NOT IN ('医疗用品','运营费') OR e.amount=0
                OR (e.adjusts_expense_id IS NULL AND e.amount<=0)
                OR (e.adjusts_expense_id IS NOT NULL AND (p.expense_id IS NULL
                    OR p.expense_id=e.expense_id OR p.status<>'确认'
                    OR e.adjustment_reason IS NULL OR CHAR_LENGTH(TRIM(e.adjustment_reason))=0))
                OR COALESCE(x.allocation_count,0)=0 OR COALESCE(x.bad_count,0)<>0
                OR COALESCE(x.allocated,0)<>e.amount THEN 'EXPENSE_SOURCE_INVALID'
              ELSE NULL END reason
          FROM expense_scope e LEFT JOIN allocation_stats x ON x.expense_id=e.expense_id
          LEFT JOIN expenses p ON p.expense_id=e.adjusts_expense_id WHERE e.status<>'作废'
        ), valid_cost AS (
          SELECT v.clinic_id,x.dentist_id,
            SUM(CASE WHEN v.kind='医疗用品' THEN x.amount ELSE 0 END) supplies,
            SUM(CASE WHEN v.kind='运营费' THEN x.amount ELSE 0 END) operations
          FROM expense_check v JOIN expense_allocations x ON x.expense_id=v.expense_id
          WHERE v.reason IS NULL GROUP BY v.clinic_id,x.dentist_id
        ), keys_union AS (
          SELECT clinic_id,dentist_id FROM valid_revenue UNION SELECT clinic_id,dentist_id FROM valid_cost
        ), contribution AS (
          SELECT k.clinic_id,k.dentist_id,COALESCE(r.revenue,0) revenue,
                 COALESCE(c.supplies,0) supplies,COALESCE(c.operations,0) operations
          FROM keys_union k LEFT JOIN valid_revenue r ON r.clinic_id=k.clinic_id AND r.dentist_id=k.dentist_id
          LEFT JOIN valid_cost c ON c.clinic_id=k.clinic_id AND c.dentist_id=k.dentist_id
        ), anomalies AS (
          SELECT 'APPOINTMENT' source_type,appointment_id source_id,clinic_id,completed_at,
                 CAST(NULL AS DATE) occurred_on,reason FROM invoice_check WHERE reason IS NOT NULL
          UNION ALL
          SELECT 'EXPENSE' source_type,expense_id source_id,clinic_id,CAST(NULL AS DATETIME) completed_at,
                 occurred_on,reason FROM expense_check WHERE reason IS NOT NULL
        )
        """;
    static final String DISPLAY="(:displayClinic IS NULL OR clinic_id=:displayClinic) AND (:displayDentist IS NULL OR dentist_id=:displayDentist)";
    static final String TOTALS="SELECT COALESCE(SUM(revenue),0) revenue,COALESCE(SUM(supplies),0) supplies,COALESCE(SUM(operations),0) operations,COUNT(*) contribution_count FROM contribution";
}
