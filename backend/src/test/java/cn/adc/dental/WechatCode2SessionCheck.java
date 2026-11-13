package cn.adc.dental;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLSession;

public final class WechatCode2SessionCheck {
    private static int checks;
    private static void check(boolean c){checks++;if(!c)throw new AssertionError("check "+checks);}
    private static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    private static void rejects(int status,String body){try{WechatCode2Session.decode("fixture-app",bytes(body));throw new AssertionError();}catch(ApiError e){check(e.status==status&&e.getCause()==null&&!e.getMessage().contains("private"));}}
    private record Response(HttpRequest request,byte[] body) implements HttpResponse<byte[]> {
        public int statusCode(){return 200;}
        public Optional<HttpResponse<byte[]>> previousResponse(){return Optional.empty();}
        public HttpHeaders headers(){return HttpHeaders.of(Map.of(),(a,b)->true);}
        public Optional<SSLSession> sslSession(){return Optional.empty();}
        public URI uri(){return request.uri();}
        public HttpClient.Version version(){return HttpClient.Version.HTTP_1_1;}
    }
    public static void main(String[] args) {
        var request=new AtomicReference<HttpRequest>();var calls=new AtomicInteger();
        var transport=new WechatHttpsTransport((http,handler)->{request.set(http);calls.incrementAndGet();return CompletableFuture.completedFuture(new Response(http,bytes("{\"openid\":\"fixture-open\",\"session_key\":\"fixture-key\",\"unionid\":\"ignored\"}")));});
        var adapter=new WechatCode2Session("fixture-app","fixture-secret&=",Clock.systemUTC(),transport);
        check(adapter.ready());var subject=adapter.exchange("fixture-code&=");
        check(subject.appId().equals("fixture-app")&&subject.openId().equals("fixture-open"));
        check(request.get().uri().getHost().equals("api.weixin.qq.com")&&request.get().uri().getPath().equals("/sns/jscode2session"));
        check(request.get().method().equals("GET")&&request.get().bodyPublisher().isEmpty());
        check(request.get().uri().getRawQuery().contains("grant_type=authorization_code"));
        check(request.get().uri().getRawQuery().contains("js_code=fixture-code%26%3D"));
        check(request.get().uri().getRawQuery().contains("secret=fixture-secret%26%3D"));
        try{adapter.exchange("fixture-code&=");throw new AssertionError();}catch(ApiError e){check(e.status==401);}
        check(calls.get()==1);
        for(String body:new String[]{"{}","[]","null","{\"openid\":\"a\"}","{\"openid\":\"a\",\"session_key\":\" \"}","{\"openid\":\"a\",\"openid\":\"b\",\"session_key\":\"k\"}","{\"openid\":\"a\",\"session_key\":\"k\"} {}","{\"errcode\":\"0\"}","{\"errcode\":-1,\"errmsg\":\"private\"}","{\"errcode\":40226}","{\"errcode\":45011}","{\"errcode\":99999}"})rejects(503,body);
        rejects(401,"{\"errcode\":40029,\"errmsg\":\"private\"}");
        check(WechatCode2Session.decode("fixture-app",bytes("{\"errcode\":0,\"openid\":\"a\",\"session_key\":\"k\"}")).openId().equals("a"));
        System.out.println("WechatCode2SessionCheck: "+checks+" passed (fake HTTP only; no real credentials)");
    }
}
