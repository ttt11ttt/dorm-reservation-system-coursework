package cn.adc.dental;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
class WorkspaceApiTest {
 @Test void patientCannotReadStaffDashboard(){var jdbc=mock(JdbcTemplate.class);var identity=mock(Identity.class);when(identity.require("patient")).thenReturn(new Identity.Actor(3,"患者",1L,null,List.of()));assertThrows(ApiError.class,()->new WorkspaceApi(new Store(jdbc,Clock.systemUTC()),identity).read("patient",null));verifyNoInteractions(jdbc);}
 @Test void clinicScopeCannotBeChangedByQueryParameter(){var jdbc=mock(JdbcTemplate.class);var identity=mock(Identity.class);when(identity.require("staff")).thenReturn(new Identity.Actor(1,"员工",null,null,List.of(Map.of("role_code","前台","scope_kind","门店","clinic_id",1L))));when(jdbc.queryForList("SELECT clinic_id,name,phone,postal_address FROM clinics WHERE active=1 ORDER BY clinic_id LIMIT 65")).thenReturn(new ArrayList<>(List.of(Map.of("clinic_id",2L,"name","门店二"))));assertThrows(ApiError.class,()->new WorkspaceApi(new Store(jdbc,Clock.systemUTC()),identity).read("staff","2"));}
 @Test void accountManagerGetsNoClinicalPatientList(){var jdbc=mock(JdbcTemplate.class);var identity=mock(Identity.class);when(identity.require("admin")).thenReturn(new Identity.Actor(1,"员工",null,null,List.of(Map.of("role_code","账号管理","scope_kind","连锁"))));when(jdbc.queryForList("SELECT clinic_id,name,phone,postal_address FROM clinics WHERE active=1 ORDER BY clinic_id LIMIT 65")).thenReturn(new ArrayList<>(List.of(Map.of("clinic_id",1L,"name","门店一"))));var result=(Map<?,?>)new WorkspaceApi(new Store(jdbc,Clock.systemUTC()),identity).read("admin",null);assertTrue(((List<?>)result.get("visits")).isEmpty());assertTrue(((List<?>)result.get("patients")).isEmpty());assertTrue(((List<?>)result.get("followups")).isEmpty());assertFalse(mockingDetails(jdbc).getInvocations().stream().anyMatch(i->i.getArguments().length>0&&String.valueOf((Object)i.getArgument(0)).contains("FROM appointments")));}
}
