package cn.adc.dental;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.web.bind.annotation.*;

/** Basic organization discovery and explicit creation only. */
@RestController
@RequestMapping("/api/v1/org")
class OrganizationApi {
    private final Identity identity;private final OrganizationService service;
    OrganizationApi(Identity identity,OrganizationService service){this.identity=identity;this.service=service;}
    record ClinicCreate(String clinicId,String clinicCode,String name,String postalAddress,String managerName,String phone,String email,String areaM2){@JsonAnySetter void unknown(String key,Object value){throw ApiError.bad();}}
    record DentistCreate(String dentistId,String employeeNo,String name,@JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String birthDate,@JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String homeAddress,@JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String phone,@JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String email){@JsonAnySetter void unknown(String key,Object value){throw ApiError.bad();}public String toString(){return "DentistCreate[personal fields redacted]";}}
    record OpeningCreate(String openingId,String startAt,String endAt,String note){@JsonAnySetter void unknown(String key,Object value){throw ApiError.bad();}}
    record ScheduleCreate(String scheduleId,String dentistId,String openingId,String startAt,String endAt){@JsonAnySetter void unknown(String key,Object value){throw ApiError.bad();}}
    @GetMapping("/clinics") Object clinics(@RequestHeader(value="Authorization",required=false) String h,@RequestParam(required=false) String afterId,@RequestParam(required=false) Integer limit){return service.clinics(identity.require(h),afterId,limit);}
    @GetMapping("/clinics/{id}") Object clinic(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id){return service.clinic(identity.require(h),Store.parseId(id));}
    @PostMapping("/clinics") Object createClinic(@RequestHeader(value="Authorization",required=false) String h,@RequestBody ClinicCreate body){if(body==null)throw ApiError.bad();return service.createClinic(identity.require(h),body);}
    @GetMapping("/dentists") Object dentists(@RequestHeader(value="Authorization",required=false) String h,@RequestParam(required=false) String afterId,@RequestParam(required=false) Integer limit){return service.dentists(identity.require(h),afterId,limit);}
    @GetMapping("/dentists/{id}") Object dentist(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id){return service.dentist(identity.require(h),Store.parseId(id));}
    @PostMapping("/dentists") Object createDentist(@RequestHeader(value="Authorization",required=false) String h,@RequestBody DentistCreate body){if(body==null)throw ApiError.bad();return service.createDentist(identity.require(h),body);}
    @GetMapping("/clinics/{clinicId}/openings") Object openings(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String clinicId,@RequestParam(required=false) String afterId,@RequestParam(required=false) Integer limit){return service.openings(identity.require(h),Store.parseId(clinicId),afterId,limit);}
    @GetMapping("/openings/{id}") Object opening(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id){return service.opening(identity.require(h),Store.parseId(id));}
    @PostMapping("/clinics/{clinicId}/openings") Object createOpening(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String clinicId,@RequestBody OpeningCreate body){if(body==null)throw ApiError.bad();return service.createOpening(identity.require(h),Store.parseId(clinicId),body);}
    @GetMapping("/clinics/{clinicId}/schedules") Object schedules(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String clinicId,@RequestParam(required=false) String afterId,@RequestParam(required=false) Integer limit){return service.schedules(identity.require(h),Store.parseId(clinicId),afterId,limit);}
    @GetMapping("/schedules/{id}") Object schedule(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id){return service.schedule(identity.require(h),Store.parseId(id));}
    @PostMapping("/clinics/{clinicId}/schedules") Object createSchedule(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String clinicId,@RequestBody ScheduleCreate body){if(body==null)throw ApiError.bad();return service.createSchedule(identity.require(h),Store.parseId(clinicId),body);}
}
