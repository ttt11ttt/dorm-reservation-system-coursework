package cn.adc.dental;

import java.security.SecureRandom;
import java.time.*;

/** Ephemeral in-memory fixtures only, never real patient identities. */
public final class WechatBindingTicketsCheck {
    private static int checks;
    private static void check(boolean condition) {checks++;if(!condition)throw new AssertionError("check "+checks);}
    private static void rejects(int status,Runnable action) {
        try{action.run();throw new AssertionError("expected rejection");}catch(ApiError error){check(error.status==status);}
    }
    private static final class MutableClock extends Clock {
        Instant now=Instant.parse("2026-10-24T12:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId ignored){return this;}
        public Instant instant(){return now;}
    }
    public static void main(String[] args) {
        var clock=new MutableClock();var tickets=new WechatBindingTickets(clock,new SecureRandom());
        var subject=new WechatIdentityExchange.Subject("fixture-app","fixture-open-id");
        var result=tickets.create(subject);String code=result.get("bindingCode").toString();
        check(code.matches("[A-Za-z0-9_-]{22}"));
        check(!result.containsKey("openId") && !result.containsKey("patientId") && !result.containsKey("token"));
        check(!subject.toString().contains("fixture-open-id"));
        check(tickets.lookup(code).equals(clock.now.plusSeconds(600)));
        check(tickets.reserve(code,101,201).equals(subject));
        check(tickets.reserve(code,101,201).equals(subject));
        rejects(409,()->tickets.reserve(code,102,201));
        rejects(409,()->tickets.reserve(code,101,202));
        tickets.complete(code);rejects(409,()->tickets.lookup(code));
        String expired=tickets.create(subject).get("bindingCode").toString();clock.now=clock.now.plusSeconds(600);
        rejects(409,()->tickets.reserve(expired,103,201));
        rejects(400,()->tickets.lookup("not-a-valid-code"));
        check(WechatBindingApi.generation("0")==0);
        rejects(400,()->WechatBindingApi.generation("-1"));
        rejects(400,()->WechatBindingApi.generation("9223372036854775808"));
        check(!WechatBindingStore.requestDigest("bind",201,0,"digest","R-1").equals(WechatBindingStore.requestDigest("bind",202,0,"digest","R-1")));
        check(!WechatBindingStore.requestDigest("bind",201,0,"digest","R-1").equals(WechatBindingStore.requestDigest("bind",201,0,"digest","R-2")));
        System.out.println("WechatBindingTicketsCheck: "+checks+" passed (no SQL/network/patient writes)");
    }
}
