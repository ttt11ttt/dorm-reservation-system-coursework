package cn.adc.dental;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WechatPatientLoginApiTest {
    /** Offline runner uses the same assertions without downloading a Surefire provider. */
    public static void main(String[] args) throws Exception {
        var tests = new WechatPatientLoginApiTest();
        tests.unavailableEvenIfFlagAndSecretArePresentWithoutReviewedFlow();
        tests.rejectsInjectedIdentityDuplicateCodeAndOversizedBody();
        tests.rejectsQueryCredentialsAndTrailingJson();
        System.out.println("WechatPatientLoginApiTest: 19 MockMvc requests passed (no server/network/SQL)");
    }
    @Test void unavailableEvenIfFlagAndSecretArePresentWithoutReviewedFlow() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new WechatPatientLoginApi(true, "test-app", "test-only-not-a-real-secret"))
                .setControllerAdvice(new Errors()).build();
        mvc.perform(post("/api/v1/patient/wechat-sessions").contentType("application/json").content("{\"code\":\"opaque-test\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("WECHAT_LOGIN_UNAVAILABLE"))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("opaque-test"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("test-only-not-a-real-secret"))));
    }
    @Test void rejectsInjectedIdentityDuplicateCodeAndOversizedBody() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new WechatPatientLoginApi(false, "", ""))
                .setControllerAdvice(new Errors()).build();
        for (String body : new String[]{"{\"code\":\"x\",\"patientId\":\"1\"}",
                "{\"code\":\"x\",\"code\":\"y\"}", "[]", "{\"code\":\"" + "x".repeat(9000) + "\"}",
                "", "null", "{}", "{\"code\":null}", "{\"code\":42}", "{\"code\":\" \"}",
                "{\"code\":\"x\\ny\"}", "{\"code\":\"" + "x".repeat(2049) + "\"}"}) {
            mvc.perform(post("/api/v1/patient/wechat-sessions").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
    }
    @Test void rejectsQueryCredentialsAndTrailingJson() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new WechatPatientLoginApi(true, "test-app", "test-only-not-a-real-secret"))
                .setControllerAdvice(new Errors()).build();
        for (String suffix : new String[]{"?code=not-a-real-code", "?patientId=1"}) {
            mvc.perform(post("/api/v1/patient/wechat-sessions" + suffix).contentType("application/json").content("{\"code\":\"x\"}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        for (String suffix : new String[]{" {}", " []", " null", " true"}) {
            mvc.perform(post("/api/v1/patient/wechat-sessions").contentType("application/json").content("{\"code\":\"x\"}" + suffix))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
    }
}
