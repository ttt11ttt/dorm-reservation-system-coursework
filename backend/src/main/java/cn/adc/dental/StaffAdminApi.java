package cn.adc.dental;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

/** Staff account governance routes. */
@RestController
@RequestMapping("/api/v1/staff-admin/accounts")
class StaffAdminApi {
    private final Identity identity;private final StaffAdminService service;
    StaffAdminApi(Identity identity,StaffAdminService service){this.identity=identity;this.service=service;}
    record InitialGrant(String roleCode,String scopeKind,String clinicId){@JsonAnySetter void unknown(String key,Object value){throw ApiError.bad();}}
    record Create(String accountId,String grantId,String loginName,String displayName,String dentistId,InitialGrant initialGrant,@JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String password){@JsonAnySetter void unknown(String key,Object value){throw ApiError.bad();}public String toString(){return "Create[credential redacted]";}}
    record AddGrant(String grantId,JsonNode expected,String roleCode,String scopeKind,String clinicId,String reason){@JsonAnySetter void unknown(String key,Object value){throw ApiError.bad();}}
    record Revoke(String operationId,JsonNode expected,String reason){@JsonAnySetter void unknown(String key,Object value){throw ApiError.bad();}}
    record State(String operationId,JsonNode expected,Boolean active,String reason){@JsonAnySetter void unknown(String key,Object value){throw ApiError.bad();}}
    record CredentialReset(String operationId,JsonNode expected,@JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String newPassword,String reason){@JsonAnySetter void unknown(String key,Object value){throw ApiError.bad();}public String toString(){return "CredentialReset[credential redacted]";}}
    @GetMapping Object list(@RequestHeader(value="Authorization",required=false) String h,@RequestParam(required=false) String clinicId,@RequestParam(required=false) String afterId,@RequestParam(required=false) Integer limit){return service.list(identity.require(h),clinicId,afterId,limit);}
    @GetMapping("/{id}") Object detail(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id,@RequestParam(required=false) String clinicId){return service.detail(identity.require(h),Store.parseId(id),clinicId);}
    @PostMapping Object create(@RequestHeader(value="Authorization",required=false) String h,@RequestBody Create input){if(input==null||input.initialGrant()==null)throw ApiError.bad();var g=input.initialGrant();return service.create(identity.require(h),new StaffAdminService.Create(input.accountId(),input.grantId(),input.loginName(),input.displayName(),input.dentistId(),new StaffAdminService.GrantInput(g.roleCode(),g.scopeKind(),g.clinicId()),input.password()));}
    @PostMapping("/{id}/grants") Object addGrant(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id,@RequestBody AddGrant input){if(input==null)throw ApiError.bad();return service.addGrant(identity.require(h),Store.parseId(id),new StaffAdminService.AddGrant(input.grantId(),input.expected(),input.roleCode(),input.scopeKind(),input.clinicId(),input.reason()));}
    @PostMapping("/{id}/grants/{grantId}/revocation") Object revoke(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id,@PathVariable String grantId,@RequestBody Revoke input){if(input==null)throw ApiError.bad();return service.revoke(identity.require(h),Store.parseId(id),Store.parseId(grantId),new StaffAdminService.Revoke(input.operationId(),input.expected(),input.reason()));}
    @PutMapping("/{id}/state") Object state(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id,@RequestBody State input){if(input==null)throw ApiError.bad();return service.state(identity.require(h),Store.parseId(id),new StaffAdminService.State(input.operationId(),input.expected(),input.active(),input.reason()));}
    @PostMapping("/{id}/credential-reset") Object credentialReset(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id,@RequestBody CredentialReset input){if(input==null)throw ApiError.bad();return service.credentialReset(identity.require(h),Store.parseId(id),new StaffAdminService.CredentialReset(input.operationId(),input.expected(),input.newPassword(),input.reason()));}
}
