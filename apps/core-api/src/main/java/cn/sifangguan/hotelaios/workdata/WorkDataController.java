package cn.sifangguan.hotelaios.workdata;

import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/work-data")
public class WorkDataController {
    private final WorkDataService service;
    private final AttachmentService attachmentService;

    public WorkDataController(WorkDataService service, AttachmentService attachmentService) {
        this.service = service;
        this.attachmentService = attachmentService;
    }

    @GetMapping("/forms")
    public List<Map<String, Object>> forms() {
        return service.forms();
    }

    @PostMapping("/forms")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createForm(@Valid @RequestBody WorkDataModels.CreateForm request) {
        return service.createForm(request);
    }

    @PostMapping("/forms/{formId}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createFormVersion(
            @PathVariable UUID formId,
            @Valid @RequestBody WorkDataModels.CreateFormVersion request
    ) {
        return service.createFormVersion(formId, request);
    }

    @PostMapping("/forms/{formId}/versions/{versionId}/publish")
    public Map<String, Object> publishFormVersion(@PathVariable UUID formId, @PathVariable UUID versionId) {
        return service.publishFormVersion(formId, versionId);
    }

    @GetMapping("/records")
    public List<Map<String, Object>> records() {
        return service.records();
    }

    @GetMapping("/records/{recordId}")
    public Map<String, Object> record(@PathVariable UUID recordId) {
        return service.record(recordId);
    }

    @PostMapping("/records")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> submit(@Valid @RequestBody WorkDataModels.SubmitWorkRecord request) {
        return service.submit(request);
    }

    @PutMapping("/records/{recordId}")
    public Map<String, Object> updateDraft(
            @PathVariable UUID recordId,
            @Valid @RequestBody WorkDataModels.UpdateDraft request
    ) {
        return service.updateDraft(recordId, request);
    }

    @PostMapping("/records/{recordId}/actions/submit")
    public Map<String, Object> submitDraft(
            @PathVariable UUID recordId,
            @Valid @RequestBody WorkDataModels.SubmitDraft request
    ) {
        return service.submitDraft(recordId, request);
    }

    @PostMapping("/records/{recordId}/actions/review")
    public Map<String, Object> review(
            @PathVariable UUID recordId,
            @Valid @RequestBody WorkDataModels.ReviewRecord request
    ) {
        return service.review(recordId, request);
    }

    @PostMapping("/records/{recordId}/supplements")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> addSupplement(
            @PathVariable UUID recordId,
            @Valid @RequestBody WorkDataModels.AddSupplement request
    ) {
        return service.addSupplement(recordId, request);
    }

    @PostMapping("/records/{recordId}/attachments")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> addAttachment(
            @PathVariable UUID recordId,
            @Valid @RequestBody WorkDataModels.AddAttachment request
    ) {
        return service.addAttachment(recordId, request);
    }

    @PostMapping(value = "/records/{recordId}/attachments/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> uploadAttachment(
            @PathVariable UUID recordId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "FILE_PICKER") String captureSource,
            @RequestParam(required = false) String checkpointCode,
            @RequestParam(required = false) String evidenceInstanceKey,
            @RequestParam(required = false) OffsetDateTime capturedAtClient
    ) {
        return attachmentService.upload(recordId, file, captureSource, checkpointCode,
                evidenceInstanceKey, capturedAtClient);
    }

    @GetMapping("/records/{recordId}/attachments")
    public List<Map<String, Object>> attachments(@PathVariable UUID recordId) {
        return attachmentService.list(recordId);
    }

    @PostMapping("/records/{recordId}/attachments/{attachmentId}/ai-review")
    public Map<String, Object> reviewAttachmentAi(
            @PathVariable UUID recordId,
            @PathVariable UUID attachmentId,
            @Valid @RequestBody WorkDataModels.ReviewAttachmentAi request
    ) {
        return attachmentService.reviewAiProcessing(recordId, attachmentId, request);
    }

    @GetMapping("/attachments/{attachmentId}/content")
    public ResponseEntity<org.springframework.core.io.Resource> attachmentContent(@PathVariable UUID attachmentId) {
        AttachmentService.Download download = attachmentService.download(attachmentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.mediaType()))
                .contentLength(download.sizeBytes())
                .header("Content-Disposition", ContentDisposition.inline()
                        .filename(download.originalName(), StandardCharsets.UTF_8).build().toString())
                .body(download.resource());
    }

    @DeleteMapping("/records/{recordId}/attachments/{attachmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAttachment(@PathVariable UUID recordId, @PathVariable UUID attachmentId) {
        attachmentService.delete(recordId, attachmentId);
    }
}
