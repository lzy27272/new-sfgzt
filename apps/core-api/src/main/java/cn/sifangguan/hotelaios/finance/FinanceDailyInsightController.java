package cn.sifangguan.hotelaios.finance;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
@RestController @RequestMapping("/api/v1/finance/insights/daily")
public class FinanceDailyInsightController {
 private final FinanceDailyInsightService service;
 public FinanceDailyInsightController(FinanceDailyInsightService service){this.service=service;}
 @GetMapping public Map<String,Object> dashboard(@RequestParam LocalDate date,@RequestParam(required=false)UUID hotelId){return service.dashboard(date,hotelId);}
 @PostMapping("/snapshots")public Map<String,Object> freeze(@Valid @RequestBody FinanceDailyInsightModels.Freeze in){return service.freeze(in);}
 @GetMapping("/snapshots")public Map<String,Object> list(@RequestParam LocalDate date){return service.snapshots(date);}
 @GetMapping("/snapshots/{id}")public Map<String,Object> snapshot(@PathVariable UUID id){return service.snapshot(id);}
 @GetMapping("/export")public ResponseEntity<byte[]> export(@RequestParam(required=false)LocalDate date,@RequestParam(required=false)UUID hotelId,@RequestParam(required=false)UUID snapshotId,@RequestParam String expectedReportVersion,@RequestParam String format){var file=service.export(date,hotelId,snapshotId,expectedReportVersion,format);return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mediaType())).header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Report-Version",file.reportVersion()).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.name(),StandardCharsets.UTF_8).build().toString()).body(file.content());}
 @PostMapping("/questions")public Map<String,Object> ask(@Valid @RequestBody FinanceDailyInsightModels.Question in){return service.ask(in);}
}
