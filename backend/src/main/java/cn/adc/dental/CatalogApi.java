package cn.adc.dental;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/catalog")
class CatalogApi {
    private final CatalogService service;
    private final Clock clock;
    private final Deque<Instant> requests=new ArrayDeque<>();
    CatalogApi(CatalogService service,Clock clock) {this.service=service;this.clock=clock;}
    private synchronized void admit() {
        Instant now=clock.instant();while(!requests.isEmpty()&&!requests.peekFirst().isAfter(now.minusSeconds(60)))requests.removeFirst();
        if(requests.size()>=600)throw new ApiError(429,"CATALOG_THROTTLED","查询请求较多，请稍后重试");requests.addLast(now);
    }
    @GetMapping("/clinics") Object clinics(@RequestParam(required=false) String q,@RequestParam(required=false) String afterId,@RequestParam(required=false) Integer limit) {admit();return service.clinics(q,afterId,limit);}
    @GetMapping("/clinics/{clinicId}/dentists") Object clinicDentists(@PathVariable String clinicId,@RequestParam String date,@RequestParam(required=false) String q) {admit();return service.clinicDentists(Store.parseId(clinicId),date,q);}
    @GetMapping("/experts") Object experts(@RequestParam(required=false) String afterId,@RequestParam(required=false) Integer limit) {admit();return service.experts(afterId,limit);}
    @GetMapping("/dentists/{dentistId}") Object detail(@PathVariable String dentistId,@RequestParam(required=false) String clinicId) {admit();return service.detail(Store.parseId(dentistId),clinicId==null?null:Store.parseId(clinicId));}
    @GetMapping("/availability") Object availability(@RequestParam String clinicId,@RequestParam String dentistId,@RequestParam String date) {admit();return service.availability(Store.parseId(clinicId),Store.parseId(dentistId),date);}
}
