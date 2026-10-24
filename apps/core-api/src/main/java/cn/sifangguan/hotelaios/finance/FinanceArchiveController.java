package cn.sifangguan.hotelaios.finance;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.core.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static cn.sifangguan.hotelaios.finance.FinanceV2Support.*;

@RestController @RequestMapping("/api/v1/finance/archive")
public class FinanceArchiveController {
 private FinanceArchiveExtendedSources extended;
 @org.springframework.beans.factory.annotation.Autowired public void setExtended(FinanceArchiveExtendedSources extended){this.extended=extended;}
 private final FinanceArchiveService service;private final FinanceEvidenceService claims;private final FinanceOperationsEvidenceService operations;private final FinanceBusinessEvidenceService business;private final FinanceMigrationService migration;private final FinanceMigrationFileGuard guard;
 public FinanceArchiveController(FinanceArchiveService service,FinanceEvidenceService claims,FinanceOperationsEvidenceService operations,FinanceBusinessEvidenceService business,FinanceMigrationService migration,FinanceMigrationFileGuard guard){this.service=service;this.claims=claims;this.operations=operations;this.business=business;this.migration=migration;this.guard=guard;}
 @GetMapping("/context") public Map<String,Object> context(){return service.context();}
 @GetMapping("/sources") public Map<String,Object> sources(@RequestParam String sourceType,@RequestParam(required=false)UUID hotelId){return extended.available(sourceType,hotelId);}
 @GetMapping("/report-sources")public Map<String,Object> reportSources(){return extended.reportChoices();}
 @PostMapping("/report-sources")public Map<String,Object> reportSource(@Valid @RequestBody FinanceArchiveExtendedSources.ReportSource in){return extended.createReportSource(in);}
 @GetMapping("/preview") public Map<String,Object> preview(@RequestParam String sourceType,@RequestParam UUID sourceId){return service.preview(sourceType,sourceId);}
 @GetMapping public Map<String,Object> list(@RequestParam(required=false)String sourceType,@RequestParam(required=false)UUID sourceId){return service.list(sourceType,sourceId);}
 @PostMapping public Map<String,Object> freeze(@Valid @RequestBody FinanceArchiveModels.Freeze in){return service.freeze(in);}
 @GetMapping("/{id}") public Map<String,Object> detail(@PathVariable UUID id){return service.detail(id);}
 @PostMapping("/{id}/notes") public Map<String,Object> note(@PathVariable UUID id,@Valid @RequestBody FinanceArchiveModels.Note in){return service.note(id,in);}
 @PostMapping("/{id}/requests") public Map<String,Object> request(@PathVariable UUID id,@Valid @RequestBody FinanceArchiveModels.Request in){return service.request(id,in);}
 @GetMapping("/requests") public Map<String,Object> requests(){return service.requests();}
 @PostMapping("/requests/{id}/decide") public Map<String,Object> decide(@PathVariable UUID id,@Valid @RequestBody FinanceArchiveModels.Decision in){return service.decide(id,in,false);}
 @PostMapping("/requests/{id}/revoke") public Map<String,Object> revoke(@PathVariable UUID id,@Valid @RequestBody FinanceArchiveModels.Decision in){return service.decide(id,in,true);}
 @GetMapping("/{id}/export") public ResponseEntity<byte[]> export(@PathVariable UUID id){byte[] bytes=json(service.export(id)).getBytes(StandardCharsets.UTF_8);return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.CACHE_CONTROL,"no-store").header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=finance-archive-"+id+".json").body(bytes);}
 @GetMapping("/{id}/reports/export")public ResponseEntity<byte[]> report(@PathVariable UUID id,@RequestParam String expectedReportVersion,@RequestParam String reportType,@RequestParam String format){var file=service.exportReport(id,expectedReportVersion,reportType,format);return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mediaType())).header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Report-Version",file.reportVersion()).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.name(),StandardCharsets.UTF_8).build().toString()).body(file.content());}
 @GetMapping("/{id}/originals/{attachment}") public ResponseEntity<Resource> original(@PathVariable UUID id,@PathVariable UUID attachment){var ref=service.recordOriginal(id,attachment);String type=ref.get("sourceType").toString();UUID source=FinanceV2Support.id(ref.get("sourceId"));FinanceEvidenceService.Download file=switch(type){case "CLAIM"->claims.download(source,attachment);case "OPERATION"->operations.download(source,attachment);case "ASSET_GAIN","INTAKE","PROCUREMENT"->extended.download(type,source,attachment);case "REVENUE","PAYROLL","INTERCOMPANY"->business.download(type,source,attachment);case "TAX_RETURN"->business.download("TAX",source,attachment);case "MIGRATION"->{var original=migration.original(source);guard.inspect(original.content());yield new FinanceEvidenceService.Download(original.name(),original.mediaType(),new ByteArrayResource(original.content()));}default->throw bad("该来源没有可直接调阅的原件，请沿已授权来源关联查询");};service.originalRead(id,attachment);return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mediaType())).header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff").header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.name(),StandardCharsets.UTF_8).build().toString()).body(file.resource());}
 @GetMapping("/policies") public Map<String,Object> policies(){return service.policies();}
 @PostMapping("/policies") public Map<String,Object> policy(@Valid @RequestBody FinanceArchiveModels.Retention in){return service.policy(in);}
 @PostMapping("/policies/{id}/publish") public Map<String,Object> publish(@PathVariable UUID id){return service.publishPolicy(id);}
 @GetMapping("/manifest") public ResponseEntity<byte[]> manifest(){return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.CACHE_CONTROL,"no-store").header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=finance-archive-manifest.json").body(json(service.manifest()).getBytes(StandardCharsets.UTF_8));}
}
