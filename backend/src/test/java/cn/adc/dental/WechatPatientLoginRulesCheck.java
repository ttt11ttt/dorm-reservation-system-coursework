package cn.adc.dental;

import java.util.Map;

/** Pure memory checks: no Spring, MySQL, HTTP, real identities or patient writes. */
public final class WechatPatientLoginRulesCheck {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    private static void rejects(int status, Runnable action) {
        try { action.run(); throw new AssertionError("expected rejection"); }
        catch (ApiError error) { check(error.status == status); }
    }
    public static void main(String[] args) {
        check(WechatPatientLoginRules.code(Map.of("code", "opaque-test-code"), 32).equals("opaque-test-code"));
        rejects(400, () -> WechatPatientLoginRules.code(Map.of("code", "x", "patientId", "1"), 40));
        rejects(400, () -> WechatPatientLoginRules.code(Map.of("openid", "x"), 20));
        rejects(400, () -> WechatPatientLoginRules.code(Map.of("code", " "), 20));
        rejects(400, () -> WechatPatientLoginRules.code(Map.of("code", " x"), 20));
        rejects(400, () -> WechatPatientLoginRules.code(Map.of("code", "a\nb"), 20));
        rejects(400, () -> WechatPatientLoginRules.code(Map.of("code", 42), 20));
        rejects(400, () -> WechatPatientLoginRules.code(Map.of("code", "x"), 8193));
        rejects(400, () -> WechatPatientLoginRules.code(Map.of("code", "x".repeat(2049)), 4096));
        rejects(400, () -> WechatPatientLoginRules.code(null, 0));
        for (int missing = 0; missing < 5; missing++) {
            boolean[] ready = {true, true, true, true, true}; ready[missing] = false;
            rejects(503, () -> WechatPatientLoginRules.available(ready[0], ready[1], ready[2], ready[3], ready[4]));
        }
        var valid = new WechatPatientLoginRules.Binding(101, 201, "患者", true, true, 7, true);
        var issued = WechatPatientLoginRules.bound(valid, 1);
        check(WechatPatientLoginRules.current(issued, valid, 1));
        rejects(403, () -> WechatPatientLoginRules.bound(null, 0));
        rejects(503, () -> WechatPatientLoginRules.bound(valid, 2));
        check(!WechatPatientLoginRules.current(issued, valid, 0));
        check(!WechatPatientLoginRules.current(issued, valid, 2));
        check(!WechatPatientLoginRules.current(issued, new WechatPatientLoginRules.Binding(102, 201, "患者", true, true, 7, true), 1));
        check(!WechatPatientLoginRules.current(issued, new WechatPatientLoginRules.Binding(101, 202, "患者", true, true, 7, true), 1));
        check(!WechatPatientLoginRules.current(issued, new WechatPatientLoginRules.Binding(101, 201, "患者", true, true, 8, true), 1));
        check(!WechatPatientLoginRules.current(issued, new WechatPatientLoginRules.Binding(101, 201, "患者", false, true, 7, true), 1));
        check(!WechatPatientLoginRules.current(issued, new WechatPatientLoginRules.Binding(101, 201, "患者", true, false, 7, true), 1));
        check(!WechatPatientLoginRules.current(issued, new WechatPatientLoginRules.Binding(101, 201, "员工", true, true, 7, true), 1));
        check(!WechatPatientLoginRules.current(issued, new WechatPatientLoginRules.Binding(101, 201, "患者", true, true, 7, false), 1));
        check(WechatPatientLoginRules.nextGeneration(7) == 8);
        rejects(503, () -> WechatPatientLoginRules.nextGeneration(Long.MAX_VALUE));
        rejects(503, () -> WechatPatientLoginRules.nextGeneration(-1));
        System.out.println("WechatPatientLoginRulesCheck: " + checks + " passed (pure memory only)");
    }
}
