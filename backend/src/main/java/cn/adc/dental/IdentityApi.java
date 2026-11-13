package cn.adc.dental;

import java.util.Map;
import org.springframework.web.bind.annotation.*;
import org.springframework.context.annotation.Profile;

@RestController
@RequestMapping("/api/v1")
class IdentityApi {
    record Login(String loginName,String password) {}
    private final Identity identity;
    IdentityApi(Identity identity) { this.identity=identity; }
    @PostMapping("/staff/sessions") Object login(@RequestBody Login login) { return identity.login(login.loginName,login.password,"员工"); }
    @GetMapping("/me") Object me(@RequestHeader(value="Authorization",required=false) String h) {
        var a=identity.require(h);
        var result=new java.util.LinkedHashMap<String,Object>();
        result.put("accountId",Long.toString(a.id())); result.put("kind",a.kind()); result.put("patientId",a.patientId()); result.put("dentistId",a.dentistId()); result.put("grants",a.grants()); result.put("readOnly",a.readOnly());
        return Store.json(result);
    }
    @DeleteMapping("/sessions/current") Object logout(@RequestHeader(value="Authorization",required=false) String h) { identity.logout(h); return Map.of("loggedOut",true); }
}

@Profile({"dev","test"})
@RestController
@RequestMapping("/api/v1/dev")
class DevelopmentIdentityApi {
    private final Identity identity;
    DevelopmentIdentityApi(Identity identity) { this.identity=identity; }
    @PostMapping("/patient/sessions") Object login(@RequestBody IdentityApi.Login login) { return identity.login(login.loginName(),login.password(),"患者"); }
}
