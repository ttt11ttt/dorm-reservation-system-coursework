package cn.adc.dental;

import java.sql.Timestamp;
import java.time.Instant;

/** Boundary checks only; no Spring, database, HTTP or real identities. */
public final class GuestPolicyCheck {
    private static int checks;
    private static void check(boolean actual,boolean expected) {
        checks++;
        if(actual!=expected)throw new AssertionError("guest policy check "+checks);
    }
    public static void main(String[] args) {
        var start=Timestamp.from(Instant.parse("2026-10-25T06:00:00Z"));
        check(GuestPolicy.late(Timestamp.from(start.toInstant().minusSeconds(10801)),start),false);
        check(GuestPolicy.late(Timestamp.from(start.toInstant().minusSeconds(10800)),start),false);
        check(GuestPolicy.late(Timestamp.from(start.toInstant().minusSeconds(10799)),start),true);
        check(GuestPolicy.late(start,start),true);
        // In-person conversion alone does not revoke guest booking; a formal WeChat bind does.
        check(GuestPolicy.guestEligible(true,false,false),true);
        check(GuestPolicy.guestEligible(true,true,false),false);
        check(GuestPolicy.guestEligible(true,false,true),false);
        check(GuestPolicy.guestEligible(false,false,false),false);
        System.out.println("GuestPolicyCheck: "+checks+" passed (pure memory only)");
    }
}
