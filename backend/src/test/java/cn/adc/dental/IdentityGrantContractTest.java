package cn.adc.dental;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;
class IdentityGrantContractTest {
    @Test void departmentGrantsDoNotCrossScopeOrOverrideDentistOwnership(){
        var staff=new Identity.Actor(1,"员工",null,7L,List.of(
            Map.of("role_code","前台","scope_kind","门店","clinic_id",1L),
            Map.of("role_code","牙医","scope_kind","门店","clinic_id",2L),
            Map.of("role_code","运营人员／店长","scope_kind","门店","clinic_id",3L)));
        assertDoesNotThrow(()->staff.front(1));
        assertThrows(ApiError.class,()->staff.front(2));
        assertDoesNotThrow(()->staff.dentist(2,7));
        assertThrows(ApiError.class,()->staff.dentist(2,8));
        assertThrows(ApiError.class,()->staff.dentist(1,7));
        assertDoesNotThrow(()->OrganizationRules.clinicRead(staff,3));
        assertThrows(ApiError.class,()->OrganizationRules.clinicRead(staff,1));
        assertThrows(ApiError.class,()->OrganizationRules.chainOnly(staff));
        assertThrows(ApiError.class,()->FollowupRules.dentistListScope(staff,1,"all",null,20));
    }
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
