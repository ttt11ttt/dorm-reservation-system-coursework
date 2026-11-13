package cn.adc.dental;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
class VisitorAccessGateTest {
    @Test void visitorCannotWriteBusinessRoutes(){
        var identity=mock(Identity.class);
        when(identity.visitorSession("visitor-token")).thenReturn(true);
        when(identity.require("visitor-token")).thenReturn(new Identity.Actor(9,"员工",null,null,List.of(Map.of("role_code","只读访客","scope_kind","连锁"))));
        var gate=new VisitorAccessGate(identity);
        for(String path:List.of("/api/v1/clinics","/api/v1/staff/workspace","/api/v1/org/clinics","/api/v1/staff/appointments","/api/v1/staff/accounts"))
            for(String method:List.of("POST","PUT","PATCH","DELETE")){
                var request=new MockHttpServletRequest(method,path);request.addHeader("Authorization","visitor-token");
                assertThrows(ApiError.class,()->gate.preHandle(request,new MockHttpServletResponse(),new Object()));
            }
    }
    @Test void visitorCanIdentifyAndLogout(){
        var identity=mock(Identity.class);
        when(identity.visitorSession("visitor-token")).thenReturn(true);
        when(identity.require("visitor-token")).thenReturn(new Identity.Actor(9,"员工",null,null,List.of(Map.of("role_code","只读访客","scope_kind","连锁"))));
        var gate=new VisitorAccessGate(identity);
        for(String path:List.of("/api/v1/me","/api/v1/clinics","/api/v1/staff/workspace","/api/v1/staff/appointments")){
            var request=new MockHttpServletRequest("GET",path);request.addHeader("Authorization","visitor-token");
            assertTrue(gate.preHandle(request,new MockHttpServletResponse(),new Object()));
        }
    }
    @Test void readOnlyLogoutIsAllowed(){
        var identity=mock(Identity.class);when(identity.visitorSession("visitor-token")).thenReturn(true);when(identity.require("visitor-token")).thenReturn(new Identity.Actor(9,"员工",null,null,List.of(Map.of("role_code","只读访客","scope_kind","连锁"))));var request=new MockHttpServletRequest("DELETE","/api/v1/sessions/current");request.addHeader("Authorization","visitor-token");assertTrue(new VisitorAccessGate(identity).preHandle(request,new MockHttpServletResponse(),new Object()));
    }
    @Test void unrelatedSessionRemainsWithItsOwnAuthenticator(){
        var identity=mock(Identity.class);var gate=new VisitorAccessGate(identity);
        var request=new MockHttpServletRequest("GET","/api/v1/guest/reservations");request.addHeader("Authorization","native-guest-token");
        assertTrue(gate.preHandle(request,new MockHttpServletResponse(),new Object()));
        verify(identity,never()).require(anyString());
    }
}
