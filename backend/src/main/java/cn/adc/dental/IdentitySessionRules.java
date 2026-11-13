package cn.adc.dental;

import java.util.Objects;

/** Pure comparison of the generations bound to an authenticated session. */
final class IdentitySessionRules {
    private IdentitySessionRules() {}
    record Version(String credentialFingerprint,long stateGeneration) {}
    static Version version(String verifiedCredentialRef,long stateGeneration){
        return new Version(Identity.digest(verifiedCredentialRef),stateGeneration);
    }
    static boolean matches(Version issued,Version current){return Objects.equals(issued,current);}
}
