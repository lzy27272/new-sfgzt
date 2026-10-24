package cn.sifangguan.hotelaios.finance;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;
import java.util.*;
@RestController @RequestMapping("/api/v1/finance/supply/asset-gains")
public class FinanceAssetGainController {
 private final FinanceAssetGainService service;private final FinanceAssetGainEvidenceService evidence;
 public FinanceAssetGainController(FinanceAssetGainService service,FinanceAssetGainEvidenceService evidence){this.service=service;this.evidence=evidence;}
 @GetMapping("/context") public Map<String,Object> context(@RequestParam UUID hotelId){return service.context(hotelId);}
 @GetMapping public Map<String,Object> list(@RequestParam UUID hotelId){return service.list(hotelId);}
 @PostMapping public Map<String,Object> create(@Valid @RequestBody FinanceAssetGainModels.Draft in){return service.create(in);}
 @GetMapping("/{id}") public Map<String,Object> detail(@PathVariable UUID id){return service.detail(id);}
 @PostMapping("/{id}/submit") public Map<String,Object> submit(@PathVariable UUID id,@Valid @RequestBody FinanceAssetGainModels.Command in){return service.submit(id,in);}
 @PostMapping("/{id}/review") public Map<String,Object> review(@PathVariable UUID id,@Valid @RequestBody FinanceAssetGainModels.Review in){if(in.approved())for(var original:FinanceV2Support.rows(service.detail(id).get("originals")))evidence.download(id,FinanceV2Support.id(original.get("id")));return service.review(id,in);}
 @PostMapping("/{id}/cancel") public Map<String,Object> cancel(@PathVariable UUID id,@Valid @RequestBody FinanceAssetGainModels.Command in){return service.cancel(id,in);}
 @PostMapping("/{id}/card") public Map<String,Object> card(@PathVariable UUID id,@Valid @RequestBody FinanceAssetGainModels.Card in){return service.card(id,in);}
 @PostMapping(value="/{id}/originals",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public Map<String,Object> upload(@PathVariable UUID id,@RequestParam UUID attachmentId,@RequestParam MultipartFile file){return evidence.upload(id,attachmentId,file);}
 @GetMapping("/{id}/originals/{attachment}") public ResponseEntity<Resource> original(@PathVariable UUID id,@PathVariable UUID attachment){var file=evidence.download(id,attachment);return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mediaType())).header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff").header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.name(),StandardCharsets.UTF_8).build().toString()).body(file.resource());}
}
