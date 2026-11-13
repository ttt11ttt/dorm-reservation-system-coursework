package cn.adc.dental;

import java.util.Map;
import java.util.Set;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/staff-admin/patient-wechat")
class WechatBindingApi {
    private final WechatPatientLoginService service;
    private final WechatBindingStore store;
    WechatBindingApi(WechatPatientLoginService service,WechatBindingStore store) {this.service=service;this.store=store;}
    @GetMapping("/accounts") Object search(@RequestHeader(value="Authorization",required=false) String h,@RequestParam String q) {return store.search(h,q);}
    @GetMapping("/accounts/{id}") Object detail(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id) {return store.detail(h,Store.parseId(id));}
    @GetMapping("/patients") Object patients(@RequestHeader(value="Authorization",required=false) String h,@RequestParam String q) {return store.patients(h,q);}
    @GetMapping("/accounts/{id}/operations/{operation}") Object receipt(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id,@PathVariable String operation) {return store.receipt(h,Store.parseId(id),Store.parseId(operation));}
    @PostMapping(value="/accounts",consumes="application/json")
    Object open(@RequestHeader(value="Authorization",required=false) String h,HttpServletRequest http) {
        var input=WechatPatientLoginApi.readMap(http);keys(input,Set.of("accountId","patientId","operationId","evidenceReference","confirmedInPerson"));
        if(!Boolean.TRUE.equals(input.get("confirmedInPerson")))throw ApiError.bad();
        return store.openAccount(h,Store.parseId(string(input,"accountId")),Store.parseId(string(input,"patientId")),Store.parseId(string(input,"operationId")),string(input,"evidenceReference"));
    }
    @PostMapping(value="/requests/lookup",consumes="application/json")
    Object lookup(@RequestHeader(value="Authorization",required=false) String h,HttpServletRequest http) {
        var input=WechatPatientLoginApi.readMap(http);keys(input,Set.of("bindingCode"));
        return service.lookup(h,string(input,"bindingCode"));
    }
    @PostMapping(value="/accounts/{id}/binding",consumes="application/json")
    Object bind(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id,HttpServletRequest http) {
        var input=WechatPatientLoginApi.readMap(http);keys(input,Set.of("operationId","expectedGeneration","evidenceReference","bindingCode","confirmedInPerson"));
        if(!Boolean.TRUE.equals(input.get("confirmedInPerson"))) throw ApiError.bad();
        return service.mutate(h,Store.parseId(id),Store.parseId(string(input,"operationId")),generation(string(input,"expectedGeneration")),string(input,"evidenceReference"),true,string(input,"bindingCode"));
    }
    @PostMapping(value="/accounts/{id}/unbinding",consumes="application/json")
    Object unbind(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id,HttpServletRequest http) {
        var input=WechatPatientLoginApi.readMap(http);keys(input,Set.of("operationId","expectedGeneration","evidenceReference","confirmed"));
        if(!Boolean.TRUE.equals(input.get("confirmed"))) throw ApiError.bad();
        return service.mutate(h,Store.parseId(id),Store.parseId(string(input,"operationId")),generation(string(input,"expectedGeneration")),string(input,"evidenceReference"),false,null);
    }
    private static void keys(Map<String,Object> input,Set<String> expected) {if(!input.keySet().equals(expected))throw ApiError.bad();}
    private static String string(Map<String,Object> input,String key) {if(!(input.get(key) instanceof String text))throw ApiError.bad();return text;}
    static long generation(String input) {
        if(input==null || !input.matches("0|[1-9][0-9]{0,18}")) throw ApiError.bad();
        try{return Long.parseLong(input);}catch(NumberFormatException invalid){throw ApiError.bad();}
    }
}
