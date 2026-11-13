package cn.adc.dental;

import java.util.Map;
import java.io.IOException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.DeserializationFeature;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/** Strict production login and in-person binding handoff; never exposes development password login. */
@RestController
@RequestMapping("/api/v1/patient")
class WechatPatientLoginApi {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final WechatPatientLoginGateway gateway;
    private final WechatPatientLoginService service;
    @Autowired
    WechatPatientLoginApi(WechatPatientLoginService service) {
        this.service=service; this.gateway=null;
    }
    WechatPatientLoginApi(@Value("${dental.wechat.enabled:false}") boolean enabled,
                         @Value("${dental.wechat.app-id:}") String appId,
                         @Value("${DENTAL_WECHAT_APP_SECRET:}") String secret) {
        // A config flag or a secret alone cannot enable an unreviewed identity path.
        // No SQL schema query, code2Session HTTP call or session issuance exists until the reviewed flow is installed.
        service=null;
        gateway = new WechatPatientLoginGateway(enabled, appId != null && !appId.isBlank(),
                secret != null && !secret.isBlank(), false, null);
    }
    @PostMapping(value="/wechat-sessions", consumes="application/json")
    Object login(HttpServletRequest http) {
        var request=readMap(http);
        if(service!=null) return service.login(WechatPatientLoginRules.code(request,0));
        return gateway.login(request,0);
    }
    @PostMapping(value="/wechat-binding-requests", consumes="application/json")
    Object binding(HttpServletRequest http) {
        String code=WechatPatientLoginRules.code(readMap(http),0);
        if(service==null) throw WechatIdentityExchange.unavailable();
        return service.requestBinding(code);
    }
    @SuppressWarnings("unchecked")
    static Map<String,Object> readMap(HttpServletRequest http) {
        // Credentials belong only in the JSON body, never in URLs/access logs.
        if (http.getQueryString() != null) throw ApiError.bad();
        // Bound the read itself instead of buffering an arbitrarily large byte[] first.
        byte[] body;
        try { body = http.getInputStream().readNBytes(WechatPatientLoginRules.MAX_REQUEST_BYTES + 1); }
        catch (IOException unreadable) { throw ApiError.bad(); }
        if (body.length == 0 || body.length > WechatPatientLoginRules.MAX_REQUEST_BYTES) throw ApiError.bad();
        Object decoded;
        try { decoded = JSON.readValue(body, Object.class); }
        catch (RuntimeException malformed) { throw ApiError.bad(); }
        if (!(decoded instanceof Map<?, ?> request)) throw ApiError.bad();
        return (Map<String,Object>)request;
    }
}
