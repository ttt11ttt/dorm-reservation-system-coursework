package cn.adc.dental;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/staff/dentists")
class PublicProfileApi {
    private final PublicProfileService service;
    PublicProfileApi(PublicProfileService service) {this.service=service;}
    @GetMapping("/{id}/public-profile") Object read(@RequestHeader(value="Authorization",required=false) String header,@PathVariable String id) {return service.read(header,Store.parseId(id));}
    @PutMapping("/{id}/public-profile") Object edit(@RequestHeader(value="Authorization",required=false) String header,@PathVariable String id,@RequestBody PublicProfileService.Edit body) {return service.edit(header,Store.parseId(id),body);}
}
