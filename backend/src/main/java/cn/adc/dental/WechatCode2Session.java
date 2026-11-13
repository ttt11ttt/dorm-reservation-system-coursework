package cn.adc.dental;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Official contract supplied by the user, 2026-10-24. No configurable upstream URL. */
@Component
class WechatCode2Session implements WechatIdentityExchange {
    private static final URI ENDPOINT=URI.create("https://api.weixin.qq.com/sns/jscode2session");
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final String appId,secret;
    private final WechatHttpsTransport transport;
    private final Clock clock;
    private final Map<String,Instant> used=new HashMap<>();
    private final Deque<Instant> recent=new ArrayDeque<>();
    private final Semaphore concurrent=new Semaphore(8);
    @Autowired
    WechatCode2Session(@Value("${dental.wechat.app-id:}") String appId,
                      @Value("${DENTAL_WECHAT_APP_SECRET:}") String secret,Clock clock) {
        this(appId,secret,clock,new WechatHttpsTransport());
    }
    WechatCode2Session(String appId,String secret,Clock clock,WechatHttpsTransport transport) {
        this.appId=appId;this.secret=secret;this.clock=clock;this.transport=transport;
    }
    public boolean ready() {return appId!=null&&!appId.isBlank()&&secret!=null&&!secret.isBlank();}
    private synchronized void reserve(String code) {
        Instant now=clock.instant();
        used.entrySet().removeIf(e->!e.getValue().isAfter(now));
        while(!recent.isEmpty()&&!recent.peekFirst().isAfter(now.minusSeconds(60)))recent.removeFirst();
        String digest=Identity.digest(code);
        if(used.containsKey(digest))throw invalidCode();
        if(used.size()>=4096||recent.size()>=120)throw new ApiError(429,"WECHAT_LOGIN_THROTTLED","登录请求较多，请稍后重新登录");
        used.put(digest,now.plusSeconds(600));recent.addLast(now);
    }
    public Subject exchange(String code) {
        if(!ready())throw WechatIdentityExchange.unavailable();
        WechatPatientLoginRules.code(Map.of("code",code),0);
        if(!concurrent.tryAcquire())throw new ApiError(429,"WECHAT_LOGIN_THROTTLED","登录请求较多，请稍后重新登录");
        try {
            reserve(code);
            byte[] body=transport.exchange(ENDPOINT,Map.of("appid",appId,"secret",secret,"js_code",code,"grant_type","authorization_code"));
            return decode(appId,body);
        } finally {concurrent.release();}
    }
    static Subject decode(String appId,byte[] body) {
        if(body==null||body.length==0||body.length>WechatHttpsTransport.MAX_RESPONSE_BYTES)throw WechatIdentityExchange.unavailable();
        Object decoded;
        try{decoded=JSON.readValue(body,Object.class);}catch(RuntimeException invalid){throw WechatIdentityExchange.unavailable();}
        if(!(decoded instanceof Map<?,?> values))throw WechatIdentityExchange.unavailable();
        if(values.containsKey("errcode")) {
            Object raw=values.get("errcode");
            if(!(raw instanceof Integer)||((Integer)raw)!=0) {
                if(raw instanceof Integer error&&error==40029)throw invalidCode();
                // Busy, risk-control, throttling and unknown platform errors do not reveal upstream details.
                throw WechatIdentityExchange.unavailable();
            }
        }
        if(!(values.get("openid") instanceof String openId)||!(values.get("session_key") instanceof String key)
                ||key.isBlank()||key.length()>2048)throw WechatIdentityExchange.unavailable();
        // session_key and optional unionid are deliberately discarded, never persisted or returned.
        return new Subject(appId,openId);
    }
    private static ApiError invalidCode(){return new ApiError(401,"WECHAT_CODE_INVALID","微信登录凭证已失效，请重新登录");}
}
