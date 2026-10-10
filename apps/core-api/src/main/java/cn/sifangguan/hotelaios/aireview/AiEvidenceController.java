package cn.sifangguan.hotelaios.aireview;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;

@RestController
@RequestMapping("/api/v1/ai-evidence")
public class AiEvidenceController {
    private final AiEvidenceService service;
    public AiEvidenceController(AiEvidenceService service) { this.service=service; }
    @GetMapping("/options") public Object options() { return service.options(); }
    @GetMapping("/rules") public Object rules(@RequestParam UUID positionId) { return service.rules(positionId); }
    @GetMapping("/my-rules") public Object myRules() { return service.myRuleReferences(); }
    @GetMapping("/records/{id}/rules") public Object recordRules(@PathVariable UUID id) { return service.recordRuleReferences(id); }
    @GetMapping("/rules/{ruleId}/samples/{sampleId}/content")
    public ResponseEntity<org.springframework.core.io.Resource> referenceSample(@PathVariable UUID ruleId,@PathVariable UUID sampleId,
            @RequestParam(required=false) UUID recordId) {
        var download=service.referenceSample(ruleId,sampleId,recordId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(download.mediaType())).header("Cache-Control","private, no-store")
                .header("X-Content-Type-Options","nosniff").body(download.resource());
    }
    @PostMapping("/rules") public Object save(@Valid @RequestBody AiEvidenceModels.SaveRule body) { return service.saveRule(body); }
    @PostMapping(value="/samples",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Object sample(@RequestParam UUID positionId,@RequestParam String kind,@RequestPart("file") MultipartFile file) {
        return service.uploadSample(positionId,kind,file);
    }
    @GetMapping("/samples/{id}/content") public ResponseEntity<org.springframework.core.io.Resource> sampleContent(@PathVariable UUID id) {
        var download=service.sampleContent(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(download.mediaType())).header("Cache-Control","private, no-store")
                .header("X-Content-Type-Options","nosniff").body(download.resource());
    }
    @GetMapping("/records/{id}") public Object context(@PathVariable UUID id) { return service.context(id); }
    @PostMapping(value="/records/{id}/photos",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Object photo(@PathVariable UUID id,@RequestParam(defaultValue="FILE_PICKER") String captureSource,@RequestPart("file") MultipartFile file) {
        return service.uploadPhoto(id,captureSource,file);
    }
    @GetMapping("/photos/{id}/content") public ResponseEntity<org.springframework.core.io.Resource> photoContent(@PathVariable UUID id) {
        var download=service.photoContent(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(download.mediaType())).header("Cache-Control","private, no-store")
                .header("X-Content-Type-Options","nosniff").body(download.resource());
    }
    @PostMapping("/records/{id}/checks") public Object request(@PathVariable UUID id,@Valid @RequestBody AiEvidenceModels.CheckRequest body) {
        return service.request(id,body);
    }
    @GetMapping("/team") public Object team(@RequestParam(required=false) UUID hotelId,@RequestParam(required=false) String state) { return service.team(hotelId,state); }
    @GetMapping("/checks/{id}/history") public Object history(@PathVariable UUID id) { return service.history(id); }
    @PostMapping("/checks/{id}/decisions") public Object decision(@PathVariable UUID id,@Valid @RequestBody AiEvidenceModels.Decision body) { return service.decide(id,body); }
}
