package cn.sifangguan.hotelaios.dailyreports;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.*;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class DailyActivityController {
    private final DailyActivityService service;
    private final DailyActivityPhotoService photos;
    public DailyActivityController(DailyActivityService service,DailyActivityPhotoService photos){this.service=service;this.photos=photos;}
    @GetMapping("/daily-reports/{report}/activity-recipients")
    public List<Map<String,Object>> recipients(@PathVariable UUID report){return service.recipients(report);}
    @PutMapping("/daily-reports/{report}/activity-photos/{id}")
    public Map<String,Object> uploadReport(@PathVariable UUID report,@PathVariable UUID id,@RequestPart("file") MultipartFile file){return photos.upload(report,null,id,file);}
    @GetMapping("/daily-reports/{report}/activity-photos/{id}")
    public ResponseEntity<Resource> reportPhoto(@PathVariable UUID report,@PathVariable UUID id){return download(report,null,id);}
    @GetMapping("/lost-properties")
    public List<Map<String,Object>> list(@RequestParam(required=false) String state,@RequestParam(defaultValue="") String search,
            @RequestParam(defaultValue="0") int page){return service.list(state,search,page);}
    @GetMapping("/lost-properties/{id}")
    public Map<String,Object> detail(@PathVariable UUID id){return service.detail(id);}
    @GetMapping("/lost-properties/{id}/entry-assignment")
    public Map<String,Object> entryAssignment(@PathVariable UUID id){return service.entryAssignment(id);}
    @PostMapping("/lost-properties/{id}/review")
    public Map<String,Object> review(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody JsonNode request){return service.review(id,key,request);}
    @PostMapping("/lost-properties/{id}/release")
    public Map<String,Object> release(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody JsonNode request){return service.release(id,key,request);}
    @PutMapping("/lost-properties/{caseId}/photos/{id}")
    public Map<String,Object> uploadRelease(@PathVariable UUID caseId,@PathVariable UUID id,@RequestPart("file") MultipartFile file){return photos.upload(null,caseId,id,file);}
    @GetMapping("/lost-properties/{caseId}/photos/{id}")
    public ResponseEntity<Resource> casePhoto(@PathVariable UUID caseId,@PathVariable UUID id){return download(null,caseId,id);}
    private ResponseEntity<Resource> download(UUID report,UUID caseId,UUID id) {
        var result=photos.download(report,caseId,id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(result.mediaType())).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options","nosniff").contentLength(result.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.inline().filename(result.originalName(),java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .body(result.resource());
    }
}
