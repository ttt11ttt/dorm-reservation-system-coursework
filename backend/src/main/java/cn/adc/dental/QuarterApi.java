package cn.adc.dental;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class QuarterApi {
    private final Identity identity;private final QuarterReadService service;
    QuarterApi(Identity identity,QuarterReadService service){this.identity=identity;this.service=service;}
    @GetMapping("/finance/quarter") Object finance(@RequestHeader(value="Authorization",required=false) String h,@RequestParam int year,@RequestParam int quarter,@RequestParam(required=false) String clinicId,@RequestParam(required=false) String dentistId,@RequestParam(defaultValue="20") int limit){return service.read(identity.require(h),false,year,quarter,clinicId,dentistId,limit);}
    @GetMapping("/headquarters/quarter") Object headquarters(@RequestHeader(value="Authorization",required=false) String h,@RequestParam int year,@RequestParam int quarter,@RequestParam(required=false) String clinicId,@RequestParam(required=false) String dentistId,@RequestParam(defaultValue="20") int limit){return service.read(identity.require(h),true,year,quarter,clinicId,dentistId,limit);}
    @PostMapping("/headquarters/quarter/confirmation") Object confirm(@RequestHeader(value="Authorization",required=false) String h,@RequestBody QuarterReadService.Confirmation request){return service.confirm(identity.require(h),request);}
}
