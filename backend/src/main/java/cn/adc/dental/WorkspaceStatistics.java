package cn.adc.dental;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Aggregates current business records over Shanghai calendar dates. */
final class WorkspaceStatistics {
    private final Store db;

    WorkspaceStatistics(Store db) {
        this.db = db;
    }

    record Result(String asOf, long appointments, long cancellations, long lateCancellations,
                  long noShows, long unverified, long clinicCancellations, long followups,
                  long onTimeFollowups, long completedVisits, int operatingDays) {}

    Result read(List<Long> clinics, String from, String to) {
        var start = ExpenseRules.date(from);
        var last = ExpenseRules.date(to);
        var now = db.clock.instant();
        var today = now.atZone(CatalogPolicy.ZONE).toLocalDate();
        if (last.isBefore(start) || last.isAfter(today) || last.isAfter(start.plusDays(365))) {
            throw ApiError.bad();
        }
        var startTime = midnight(start);
        var endTime = midnight(last.plusDays(1));
        var cutoff = now.isBefore(endTime) ? now : endTime;
        var begin = Timestamp.from(startTime);
        var end = Timestamp.from(endTime);
        var through = Timestamp.from(cutoff);
        var operatingDates = new HashSet<LocalDate>();
        long appointments = 0, cancellations = 0, lateCancellations = 0;
        long noShows = 0, unverified = 0, clinicCancellations = 0;
        long followups = 0, onTimeFollowups = 0, completedVisits = 0;
        for (long clinic : clinics) {
            var visits = db.jdbc.queryForMap("""
                SELECT COUNT(CASE WHEN appointment_status NOT IN ('误录作废','诊所撤销','已改约') THEN 1 END) AS total,
                       COUNT(CASE WHEN appointment_status = '患者取消' THEN 1 END) AS cancellations,
                       COUNT(CASE WHEN appointment_status = '患者取消'
                                  AND cancelled_at > TIMESTAMPADD(HOUR, -3, scheduled_start) THEN 1 END) AS late_cancellations,
                       COUNT(CASE WHEN appointment_status = '未到诊' THEN 1 END) AS no_shows,
                       COUNT(CASE WHEN appointment_status IN ('待到诊','待核实') THEN 1 END) AS unverified,
                       COUNT(CASE WHEN appointment_status = '诊所撤销' THEN 1 END) AS clinic_cancellations
                FROM appointments
                WHERE clinic_id = ? AND scheduled_start >= ? AND scheduled_start < ? AND scheduled_end <= ?
                """, clinic, begin, end, through);
            appointments += Store.number(visits, "total");
            cancellations += Store.number(visits, "cancellations");
            lateCancellations += Store.number(visits, "late_cancellations");
            noShows += Store.number(visits, "no_shows");
            unverified += Store.number(visits, "unverified");
            clinicCancellations += Store.number(visits, "clinic_cancellations");

            // MySQL sessions use UTC. Convert completion time to Shanghai before comparing dates.
            var plans = db.jdbc.queryForMap("""
                SELECT COUNT(*) AS total,
                       COUNT(CASE WHEN status = '已完成' AND completed_at <= ?
                                  AND DATE(CONVERT_TZ(completed_at, '+00:00', '+08:00')) <= suggested_date
                                  THEN 1 END) AS on_time
                FROM followup_plans
                WHERE clinic_id = ? AND suggested_date BETWEEN ? AND ? AND status <> '已取消'
                """, through, clinic, Date.valueOf(start), Date.valueOf(last));
            followups += Store.number(plans, "total");
            onTimeFollowups += Store.number(plans, "on_time");
            completedVisits += db.jdbc.queryForObject("""
                SELECT COUNT(*) FROM appointments
                WHERE clinic_id = ? AND treatment_status = '已完成'
                  AND completed_at >= ? AND completed_at < ? AND completed_at <= ?
                """, Long.class, clinic, begin, end, through);

            for (Map<String,Object> opening : db.jdbc.queryForList("""
                SELECT start_at, end_at FROM clinic_openings
                WHERE clinic_id = ? AND status = '有效' AND start_at < ? AND end_at > ?
                """, clinic, through, begin)) {
                var open = ((Timestamp) opening.get("start_at")).toInstant();
                var close = ((Timestamp) opening.get("end_at")).toInstant();
                var firstDay = (open.isAfter(startTime) ? open : startTime)
                    .atZone(CatalogPolicy.ZONE).toLocalDate();
                var endOfOpening = close.isBefore(cutoff) ? close : cutoff;
                for (var day = firstDay; midnight(day).isBefore(endOfOpening); day = day.plusDays(1)) {
                    operatingDates.add(day);
                }
            }
        }
        return new Result(now.toString(), appointments, cancellations, lateCancellations, noShows,
            unverified, clinicCancellations, followups, onTimeFollowups, completedVisits, operatingDates.size());
    }

    private static Instant midnight(LocalDate date) {
        return date.atStartOfDay(CatalogPolicy.ZONE).toInstant();
    }
}
