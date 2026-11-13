package cn.adc.dental;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/operations/clinics/{clinicId}/reminder-rule-migrations")
class ReminderMigrationApi {
    private final Identity identity;private final ReminderMigrationService service;
    ReminderMigrationApi(Identity identity,ReminderMigrationService service){this.identity=identity;this.service=service;}
    @PostMapping("/preview") Object preview(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String clinicId,@RequestBody ReminderMigrationRules.Request request){return service.preview(identity.require(h),Store.parseId(clinicId),request);}
    @PostMapping("/{id}/commit") Object commit(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String clinicId,@PathVariable String id,@RequestBody ReminderMigrationRules.Commit request){return service.commit(identity.require(h),Store.parseId(clinicId),id,request);}
    @GetMapping("/{id}") Object read(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String clinicId,@PathVariable String id){return service.read(identity.require(h),Store.parseId(clinicId),id);}
}
