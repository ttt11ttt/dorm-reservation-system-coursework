package cn.adc.dental;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Requires private upstream configuration and separately reviewed additive schema before any exchange. */
@Component
class WechatRuntime {
    private final boolean enabled, schemaReviewed;
    final String appId;
    private final ObjectProvider<WechatIdentityExchange> exchange;
    private final Store db;
    WechatRuntime(@Value("${dental.wechat.enabled:false}") boolean enabled,
                  @Value("${dental.wechat.binding-schema-reviewed:false}") boolean schemaReviewed,
                  @Value("${dental.wechat.app-id:}") String appId,
                  ObjectProvider<WechatIdentityExchange> exchange, Store db) {
        this.enabled=enabled; this.schemaReviewed=schemaReviewed; this.appId=appId; this.exchange=exchange; this.db=db;
    }
    void requireReady() {
        var adapter=exchange.getIfAvailable();
        if (!enabled || !schemaReviewed || appId==null || appId.isBlank() || adapter==null || !adapter.ready()) {
            throw WechatIdentityExchange.unavailable();
        }
        // Fail before business SQL if the reviewed migration is absent/partial.
        try {
            int columns=db.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='accounts' AND column_name IN ('wechat_app_id','wechat_open_id','wechat_binding_generation')",Integer.class);
            int indexes=db.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='accounts' AND index_name='uq_accounts_wechat_identity' AND non_unique=0",Integer.class);
            int checks=db.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='accounts' AND constraint_type='CHECK' AND constraint_name IN ('ck_accounts_wechat_pair','ck_accounts_wechat_generation') AND enforced='YES'",Integer.class);
            if(columns!=3 || indexes!=2 || checks!=2) throw WechatIdentityExchange.unavailable();
        } catch (org.springframework.dao.DataAccessException unavailable) { throw WechatIdentityExchange.unavailable(); }
    }
    WechatIdentityExchange.Subject exchange(String code) {
        requireReady();
        var subject=exchange.getObject().exchange(code);
        if(subject==null || !appId.equals(subject.appId())) throw WechatIdentityExchange.unavailable();
        return subject;
    }
}
