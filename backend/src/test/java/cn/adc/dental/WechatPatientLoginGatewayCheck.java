package cn.adc.dental;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class WechatPatientLoginGatewayCheck {
    public static void main(String[] args) {
        var calls = new AtomicInteger();
        WechatPatientLoginGateway.VerifiedFlow fake = code -> { calls.incrementAndGet(); return Map.of(); };
        for (int missing = 0; missing < 5; missing++) {
            boolean[] ready = {true, true, true, true, true}; ready[missing] = false;
            var gateway = new WechatPatientLoginGateway(ready[0], ready[1], ready[2], ready[3], ready[4] ? fake : null);
            try { gateway.login(Map.of("code", "opaque-test"), 30); throw new AssertionError("must fail closed"); }
            catch (ApiError error) { if (error.status != 503 || !error.code.equals("WECHAT_LOGIN_UNAVAILABLE")) throw new AssertionError("wrong error"); }
        }
        if (calls.get() != 0) throw new AssertionError("unavailable configuration called upstream");
        var configured = new WechatPatientLoginGateway(true, true, true, true, fake);
        try { configured.login(Map.of("code", "x", "patientId", "1"), 40); throw new AssertionError("identity injection accepted"); }
        catch (ApiError error) { if (error.status != 400) throw new AssertionError("wrong DTO error"); }
        if (calls.get() != 0) throw new AssertionError("bad DTO called upstream");
        System.out.println("WechatPatientLoginGatewayCheck: 7 passed (zero upstream/SQL/session calls)");
    }
}
