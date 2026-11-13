-- Run after seed. UTC storage; income belongs to the Shanghai completion date.
SET SESSION time_zone = '+00:00';
SET @quarter_start = '2026-10-01';
SET @quarter_end = '2027-01-01';
SET @utc_start = CONVERT_TZ(CAST(@quarter_start AS DATETIME),'+08:00','+00:00');
SET @utc_end = CONVERT_TZ(CAST(@quarter_end AS DATETIME),'+08:00','+00:00');

-- Independent revenue/cost aggregation, then union keys to retain cost-only dentists.
WITH revenue AS (
  SELECT t.performer_dentist_id dentist_id,a.clinic_id,SUM(l.subtotal) revenue
  FROM appointments a JOIN invoices i ON i.appointment_id=a.appointment_id AND i.status='发出'
  JOIN invoice_lines l ON l.invoice_id=i.invoice_id
  JOIN treatments t ON t.treatment_id=l.treatment_id AND t.appointment_id=a.appointment_id AND t.status='确认'
  WHERE a.treatment_status='已完成' AND a.completed_at>=@utc_start AND a.completed_at<@utc_end
  GROUP BY t.performer_dentist_id,a.clinic_id
), costs AS (
  SELECT x.dentist_id,e.clinic_id,
    SUM(CASE WHEN e.kind='医疗用品' THEN x.amount ELSE 0 END) supplies,
    SUM(CASE WHEN e.kind='运营费' THEN x.amount ELSE 0 END) operations
  FROM expenses e JOIN expense_allocations x ON x.expense_id=e.expense_id
  WHERE e.status='确认' AND e.occurred_on>=@quarter_start AND e.occurred_on<@quarter_end
  GROUP BY x.dentist_id,e.clinic_id
), keys_union AS (
  SELECT dentist_id,clinic_id FROM revenue UNION SELECT dentist_id,clinic_id FROM costs
), contribution AS (
  SELECT k.dentist_id,k.clinic_id,COALESCE(r.revenue,0) revenue,
    COALESCE(c.supplies,0) supplies,COALESCE(c.operations,0) operations,
    COALESCE(r.revenue,0)-COALESCE(c.supplies,0)-COALESCE(c.operations,0) profit
  FROM keys_union k LEFT JOIN revenue r USING(dentist_id,clinic_id) LEFT JOIN costs c USING(dentist_id,clinic_id)
)
SELECT *,SUM(profit) OVER () chain_profit,
  ROUND(GREATEST(SUM(profit) OVER (),0)*0.10,2) chain_fund
FROM contribution ORDER BY dentist_id,clinic_id;
-- Seed expected: (1,1,100,30,20,50,-50,0); (2,1,0,0,100,-100,-50,0).
-- Fund applies to the full chain only; filter display AFTER full-chain calculation.

-- Missing sources must be visible, never infer completeness from numeric totals.
SELECT a.appointment_id,a.completed_at,COALESCE(i.status,'无单') invoice_state
FROM appointments a LEFT JOIN invoices i ON i.appointment_id=a.appointment_id
WHERE a.treatment_status='已完成' AND a.completed_at>=@utc_start AND a.completed_at<@utc_end
  AND (i.invoice_id IS NULL OR i.status<>'发出');
SELECT e.expense_id,e.status,e.amount,COALESCE(SUM(x.amount),0) allocated
FROM expenses e LEFT JOIN expense_allocations x ON x.expense_id=e.expense_id
WHERE e.occurred_on>=@quarter_start AND e.occurred_on<@quarter_end
GROUP BY e.expense_id,e.status,e.amount
HAVING e.status IN ('待分摊','待确认') OR (e.status='确认' AND e.amount<>COALESCE(SUM(x.amount),0));

-- Fixed date for reproducible demonstration; production uses Shanghai's current DATE.
SELECT p.followup_plan_id,p.suggested_date,a.appointment_id,a.appointment_status
FROM followup_plans p LEFT JOIN appointments a ON a.followup_plan_id=p.followup_plan_id
 AND a.appointment_status IN ('待到诊','已到诊','待核实')
WHERE p.status='待复诊' AND DATE('2026-10-05')>DATE_ADD(p.suggested_date,INTERVAL 3 DAY);

-- Outstanding self-service restriction: time test governs expiry, not scheduler timing.
SELECT restriction_id FROM booking_restrictions
WHERE patient_id=1 AND status='active' AND starts_at<=UTC_TIMESTAMP() AND UTC_TIMESTAMP()<ends_at;
