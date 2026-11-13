package cn.adc.dental;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
class IdentityGrantContractTest {
    @Test void chainGrantIncludesExplicitNullClinicInJson(){
        for(String role:java.util.List.of("账号管理","运营人员／店长","总部管理","财务","只读访客")){
            var grant=(Map<?,?>)Store.json(Identity.chainGrant(role));
            assertEquals(role,grant.get("role_code"));
            assertEquals("连锁",grant.get("scope_kind"));
            assertTrue(grant.containsKey("clinic_id"));assertNull(grant.get("clinic_id"));
            assertTrue(Store.encode(grant).contains("\"clinic_id\":null"));
        }
    }
}
