package cn.adc.dental;

import java.util.Map;
import java.util.ArrayDeque;
import java.util.Deque;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
class WechatPatientLoginService {
    private final WechatRuntime runtime;
    private final WechatBindingStore bindings;
    private final ObjectProvider<Identity> identity;
    private final WechatBindingTickets tickets;
    private final Clock clock;
    private final Deque<Instant> requests=new ArrayDeque<>();
    WechatPatientLoginService(WechatRuntime runtime,WechatBindingStore bindings,ObjectProvider<Identity> identity,Store db) {
        this.runtime=runtime;this.bindings=bindings;this.identity=identity;this.tickets=new WechatBindingTickets(db.clock,db.random);this.clock=db.clock;
    }
    private synchronized void admit() {
        Instant now=clock.instant();
        while(!requests.isEmpty()&&!requests.peekFirst().isAfter(now.minusSeconds(60)))requests.removeFirst();
        if(requests.size()>=120)throw new ApiError(429,"WECHAT_LOGIN_THROTTLED","登录请求较多，请稍后重新登录");
        requests.addLast(now);
    }
    Object login(String code) {
        admit();
        // Network first, then a separate proxied transaction. Memory session installed only after commit returns.
        var subject=runtime.exchange(code);
        var snapshot=bindings.resolve(subject);
        return identity.getObject().issueWechat(snapshot);
    }
    Object requestBinding(String code) { admit();return tickets.create(runtime.exchange(code)); }
    Object lookup(String header,String code) {
        bindings.authorize(header);runtime.requireReady();return Map.of("expiresAt",tickets.lookup(code).toString());
    }
    Object mutate(String header,long account,long operation,long generation,String evidence,boolean bind,String code) {
        var result=bindings.mutate(header,account,operation,generation,evidence,bind,code,tickets);
        // The proxied transaction has committed. A retry uses only the redacted DB receipt.
        if(bind) tickets.complete(code);
        return result;
    }
}
