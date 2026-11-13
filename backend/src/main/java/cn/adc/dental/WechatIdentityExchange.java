package cn.adc.dental;

/** Protocol adapter installed only after the official endpoint and response contract are reviewed. */
interface WechatIdentityExchange {
    record Subject(String appId, String openId) {
        public Subject {
            if (appId == null || appId.isBlank() || openId == null || openId.isBlank()
                    || !appId.equals(appId.strip()) || !openId.equals(openId.strip())
                    || appId.chars().anyMatch(Character::isISOControl) || openId.chars().anyMatch(Character::isISOControl)
                    || appId.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 128
                    || openId.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 128) throw unavailable();
        }
        public String toString() { return "Subject[identity redacted]"; }
    }
    boolean ready();
    Subject exchange(String oneTimeCode);
    static ApiError unavailable() { return new ApiError(503,"WECHAT_LOGIN_UNAVAILABLE","微信登录暂不可用"); }
}
