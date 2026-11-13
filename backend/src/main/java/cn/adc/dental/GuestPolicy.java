package cn.adc.dental;

import java.sql.Timestamp;
import java.time.Duration;

final class GuestPolicy {
    private GuestPolicy() {}
    static boolean late(Timestamp now,Timestamp scheduledStart) { return Duration.between(now.toInstant(),scheduledStart.toInstant()).getSeconds()<10800; }
    static boolean guestEligible(boolean active,boolean formallyMapped,boolean formalBindingHistory) {
        return active&&!formallyMapped&&!formalBindingHistory;
    }
}
