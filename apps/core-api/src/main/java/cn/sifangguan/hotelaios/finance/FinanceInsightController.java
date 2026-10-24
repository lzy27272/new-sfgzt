package cn.sifangguan.hotelaios.finance;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
@RestController @RequestMapping("/api/v1/finance/insights")
public class FinanceInsightController {
 private final FinanceInsightService service;public FinanceInsightController(FinanceInsightService service){this.service=service;}
 @GetMapping("/context") public Map<String,Object> context(){return service.context();}
 @GetMapping("/dashboard") public Map<String,Object> dashboard(@RequestParam String month,@RequestParam(required=false) UUID hotelId){return service.dashboard(month,hotelId);}
 @PostMapping("/metrics") public Map<String,Object> metric(@Valid @RequestBody FinanceInsightModels.Metric in){return service.saveMetric(in);}
 @PostMapping("/snapshots") public Map<String,Object> freeze(@Valid @RequestBody FinanceInsightModels.Generate in){return service.freeze(in);}
 @GetMapping("/snapshots") public Map<String,Object> snapshots(){return service.snapshots();}
 @GetMapping("/snapshots/{id}") public Map<String,Object> snapshot(@PathVariable UUID id){return service.snapshot(id);}
 @GetMapping("/compare") public Map<String,Object> compare(@RequestParam UUID first,@RequestParam UUID second){return service.compare(first,second);}
 @GetMapping("/export") public ResponseEntity<byte[]> export(@RequestParam(required=false)String month,@RequestParam(required=false)UUID hotelId,@RequestParam(required=false)UUID snapshotId,@RequestParam String expectedReportVersion,@RequestParam String format){var file=service.export(month,hotelId,snapshotId,expectedReportVersion,format);return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mediaType())).header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Report-Version",file.reportVersion()).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.name(),StandardCharsets.UTF_8).build().toString()).body(file.content());}
 @GetMapping("/reports") public Map<String,Object> list(){return service.reports();}
 @PostMapping("/reports") public Map<String,Object> generate(@Valid @RequestBody FinanceInsightModels.Generate in){return service.generate(in);}
 @GetMapping("/reports/{id}") public Map<String,Object> detail(@PathVariable UUID id){return service.detail(id);}
 @GetMapping("/reports/{id}/export") public ResponseEntity<byte[]> exportAnalysis(@PathVariable UUID id,@RequestParam int expectedRevision,@RequestParam String expectedStatus,@RequestParam String format){var file=service.exportAnalysis(id,expectedRevision,expectedStatus,format);return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mediaType())).header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Report-Version",file.reportVersion()).header("X-Analysis-Revision",String.valueOf(expectedRevision)).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.name(),StandardCharsets.UTF_8).build().toString()).body(file.content());}
 @PutMapping("/reports/{id}") public Map<String,Object> edit(@PathVariable UUID id,@Valid @RequestBody FinanceInsightModels.Edit in){return service.edit(id,in);}
 @PostMapping("/reports/{id}/review") public Map<String,Object> review(@PathVariable UUID id,@Valid @RequestBody FinanceInsightModels.Version in){return service.transition(id,in.expectedVersion(),false);}
 @PostMapping("/reports/{id}/publish") public Map<String,Object> publish(@PathVariable UUID id,@Valid @RequestBody FinanceInsightModels.Version in){return service.transition(id,in.expectedVersion(),true);}
 @PostMapping("/reports/{id}/followups") public Map<String,Object> followup(@PathVariable UUID id,@Valid @RequestBody FinanceInsightModels.Recommendation in){return service.followup(id,in);}
 @PostMapping("/questions") public Map<String,Object> ask(@Valid @RequestBody FinanceInsightModels.Question in){return service.ask(in);}
 @GetMapping("/questions") public Map<String,Object> questions(){return service.questions();}
 @GetMapping("/questions/{id}") public Map<String,Object> answer(@PathVariable UUID id){return service.answer(id);}
}
