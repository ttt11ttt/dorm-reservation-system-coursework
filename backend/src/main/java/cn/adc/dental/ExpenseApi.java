package cn.adc.dental;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/finance")
class ExpenseApi {
    private final Identity identity;private final ExpenseService service;
    ExpenseApi(Identity identity,ExpenseService service){this.identity=identity;this.service=service;}
    @PostMapping("/clinics/{clinicId}/expenses") Object create(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String clinicId,@RequestBody ExpenseRules.Creation request){return service.create(identity.require(h),Store.parseId(clinicId),request);}
    @GetMapping("/clinics/{clinicId}/expenses") Object list(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String clinicId,@RequestParam(required=false) String status,@RequestParam(required=false) String afterId,@RequestParam(defaultValue="20") int limit){return service.list(identity.require(h),Store.parseId(clinicId),status,afterId,limit);}
    @GetMapping("/expenses/{id}") Object read(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id){return service.read(identity.require(h),Store.parseId(id));}
    @PostMapping("/expenses/{id}/confirmation") Object confirm(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id,@RequestBody ExpenseRules.Confirmation request){return service.confirm(identity.require(h),Store.parseId(id),request);}
    @PostMapping("/expenses/{id}/reversal") Object reverse(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id,@RequestBody ExpenseService.Reversal request){return service.reverse(identity.require(h),Store.parseId(id),request);}
    record VoidExpense(String reason) {}
    @PostMapping("/expenses/{id}/void") Object voidExpense(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id,@RequestBody VoidExpense request){return service.voidExpense(identity.require(h),Store.parseId(id),request.reason());}
}
