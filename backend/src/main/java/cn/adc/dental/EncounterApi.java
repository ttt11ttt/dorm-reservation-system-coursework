package cn.adc.dental;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class EncounterApi {
    private final Identity identity;
    private final EncounterService encounters;
    private final BillingService billing;
    EncounterApi(Identity identity,EncounterService encounters,BillingService billing) { this.identity=identity;this.encounters=encounters;this.billing=billing; }
    @PostMapping("/staff/appointments/{id}/arrival") Object arrive(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id) { return encounters.arrive(identity.require(h),Store.parseId(id)); }
    @GetMapping("/staff/appointments/{id}") Object administrative(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id) { return encounters.administrative(identity.require(h),Store.parseId(id)); }
    @GetMapping("/dentist/appointments") Object worklist(@RequestHeader(value="Authorization",required=false) String h,@RequestParam String clinicId,@RequestParam String from,@RequestParam String to) { return encounters.worklist(identity.require(h),Store.parseId(clinicId),from,to); }
    @GetMapping("/dentist/appointments/{id}") Object context(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id) { return encounters.context(identity.require(h),Store.parseId(id)); }
    @GetMapping("/dentist/appointments/{id}/patient-history") Object history(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id,@RequestParam(required=false) String beforeId) { return encounters.history(identity.require(h),Store.parseId(id),beforeId); }
    @GetMapping("/dentist/treatment-types") Object catalogue(@RequestHeader(value="Authorization",required=false) String h,@RequestParam String clinicId) { return encounters.catalogue(identity.require(h),Store.parseId(clinicId)); }
    @PutMapping("/staff/appointments/{id}/allergies") Object allergies(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id,@RequestBody EncounterService.AllergyChange request) { return encounters.allergies(identity.require(h),Store.parseId(id),request); }
    @PostMapping("/staff/appointments/{id}/allergy-check") Object check(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id,@RequestBody EncounterService.AllergyCheck request) { return encounters.check(identity.require(h),Store.parseId(id),request); }
    @PutMapping("/staff/appointments/{id}/treatments") Object treatments(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id,@RequestBody EncounterService.TreatmentSet request) { return encounters.treatments(identity.require(h),Store.parseId(id),request); }
    @PostMapping("/staff/appointments/{id}/treatments/{treatmentId}/void") Object voidTreatment(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id,@PathVariable String treatmentId,@RequestBody EncounterService.VoidTreatment request) { return encounters.voidTreatment(identity.require(h),Store.parseId(id),Store.parseId(treatmentId),request); }
    @PostMapping("/staff/appointments/{id}/completion") Object complete(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id,@RequestBody EncounterService.Completion request) { return encounters.complete(identity.require(h),Store.parseId(id),request); }
    @PostMapping("/staff/appointments/{id}/invoice") Object issue(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id) { return billing.issue(identity.require(h),Store.parseId(id)); }
    @PostMapping("/staff/appointments/{id}/invoice-draft") Object draft(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id) { return billing.draft(identity.require(h),Store.parseId(id)); }
    @PostMapping("/staff/invoices/{id}/status") Object changeInvoice(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id,@RequestBody BillingService.StatusChange request) { return billing.change(identity.require(h),Store.parseId(id),request); }
    @PostMapping("/staff/appointments/{id}/treatments/{treatmentId}/correction") Object correctTreatment(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id,@PathVariable String treatmentId,@RequestBody BillingService.TreatmentCorrection request) { return billing.correctTreatment(identity.require(h),Store.parseId(id),Store.parseId(treatmentId),request); }
    @GetMapping("/staff/invoices/{id}") Object staffInvoice(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id) { return billing.read(identity.require(h),Store.parseId(id),false); }
    @GetMapping("/patient/invoices/{id}") Object ownInvoice(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id) { return billing.read(identity.require(h),Store.parseId(id),true); }
    @GetMapping("/patient/appointments/{id}") Object ownEncounter(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id) { return billing.patientEncounter(identity.require(h),Store.parseId(id)); }
}
