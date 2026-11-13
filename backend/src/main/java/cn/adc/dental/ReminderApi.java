package cn.adc.dental;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class ReminderApi {
    private final Identity identity;private final ReminderService service;
    ReminderApi(Identity identity,ReminderService service){this.identity=identity;this.service=service;}
    @GetMapping("/staff/clinics/{clinicId}/reminder-rules") Object read(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String clinicId){return service.readConfig(identity.require(h),Store.parseId(clinicId));}
    @PutMapping("/operations/clinics/{clinicId}/reminder-rules") Object save(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String clinicId,@RequestBody ReminderRules.Save request){return service.saveConfig(identity.require(h),Store.parseId(clinicId),request);}
    @GetMapping("/staff/appointments/{id}/reminders/preview") Object appointmentPreview(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id){return service.plan(identity.require(h),Store.parseId(id),false,false);}
    @PostMapping("/staff/appointments/{id}/reminders/rebuild") Object appointmentRebuild(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id){return service.plan(identity.require(h),Store.parseId(id),false,true);}
    @GetMapping("/staff/followup-plans/{id}/reminders/preview") Object followupPreview(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id){return service.plan(identity.require(h),Store.parseId(id),true,false);}
    @PostMapping("/staff/followup-plans/{id}/reminders/rebuild") Object followupRebuild(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id){return service.plan(identity.require(h),Store.parseId(id),true,true);}
}
