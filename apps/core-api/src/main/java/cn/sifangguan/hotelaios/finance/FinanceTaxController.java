package cn.sifangguan.hotelaios.finance;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static cn.sifangguan.hotelaios.finance.FinanceTaxModels.*;
@RestController @RequestMapping("/api/v1/finance/tax")
public class FinanceTaxController {
 private final FinanceInputTaxService service;private final FinanceTaxEvidenceService evidence;
 public FinanceTaxController(FinanceInputTaxService service,FinanceTaxEvidenceService evidence){this.service=service;this.evidence=evidence;}
 @GetMapping("/context") public Map<String,Object> context(){return service.context();}
 @GetMapping("/input-sources") public Map<String,Object> sources(@RequestParam UUID hotelId){return service.sources(hotelId);}
 @GetMapping("/input-bases") public Map<String,Object> bases(){return service.bases();}
 @GetMapping("/input-bases/{id}/original") public ResponseEntity<Resource> original(@PathVariable UUID id){var file=evidence.download(id);return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mediaType())).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.name(),StandardCharsets.UTF_8).build().toString()).header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff").body(file.resource());}
 @PostMapping("/input-bases") public Map<String,Object> basis(@Valid @RequestBody Basis in){return service.requestBasis(in);}
 @PostMapping("/input-bases/{id}/review") public Map<String,Object> review(@PathVariable UUID id,@Valid @RequestBody Review in){return service.reviewBasis(id,in);}
 @PostMapping("/input-decisions") public Map<String,Object> decision(@Valid @RequestBody Decision in){return service.decision(in);}
 @GetMapping("/returns") public Map<String,Object> returns(){return service.returns();}
 @GetMapping("/accruals") public Map<String,Object> accruals(){return service.accruals();}
 @PostMapping("/accruals") public Map<String,Object> accrual(@Valid @RequestBody Accrual in){return service.requestAccrual(in);}
 @PostMapping("/accruals/{id}/review") public Map<String,Object> reviewAccrual(@PathVariable UUID id,@Valid @RequestBody Review in){return service.reviewAccrual(id,in);}
 @PostMapping("/accruals/{id}/payments") public Map<String,Object> payment(@PathVariable UUID id,@Valid @RequestBody Payment in){return service.payment(id,in);}
}
