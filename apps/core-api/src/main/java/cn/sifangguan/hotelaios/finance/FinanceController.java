package cn.sifangguan.hotelaios.finance;

import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.Resource;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/v1/finance")
public class FinanceController {
    private final FinanceService service;
    private final FinanceEvidenceService evidence;
    public FinanceController(FinanceService service,FinanceEvidenceService evidence) { this.service=service;this.evidence=evidence; }
    @GetMapping("/context") public Map<String,Object> context() { return service.context(); }
    @GetMapping("/todos") public Map<String,Object> todos(@RequestParam(defaultValue="0") int page,@RequestParam(required=false) String kind) { return service.todos(page,kind); }
    @GetMapping("/invoice-pool") public Map<String,Object> invoicePool(@RequestParam(defaultValue="0") int page) { return service.invoicePool(page); }
    @PostMapping("/invoice-pool") public Map<String,Object> createIntake(@Valid @RequestBody FinanceModels.Create input) { return service.createIntake(input); }
    @GetMapping("/invoice-pool/{id}") public Map<String,Object> intakeDetail(@PathVariable UUID id) { return service.intakeDetail(id); }
    @PutMapping("/invoice-pool/{id}") public Map<String,Object> saveIntake(@PathVariable UUID id,@Valid @RequestBody FinanceModels.IntakeDraft input) { return service.saveIntake(id,input); }
    @PostMapping("/invoice-pool/{id}/original") public Map<String,Object> uploadIntake(@PathVariable UUID id,@RequestParam MultipartFile file) { return evidence.uploadIntake(id,file); }
    @GetMapping("/invoice-pool/{id}/original") public ResponseEntity<Resource> downloadIntake(@PathVariable UUID id) {
        var file=evidence.downloadIntake(id);return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mediaType()))
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.name(),StandardCharsets.UTF_8).build().toString())
            .header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff").body(file.resource());
    }
    @PostMapping("/invoice-pool/{id}/associate") public Map<String,Object> associateIntake(@PathVariable UUID id,@Valid @RequestBody FinanceModels.Associate input) { return service.associateIntake(id,input); }
    @PostMapping("/invoice-pool/{id}/verification") public Map<String,Object> verifyInvoice(@PathVariable UUID id,@Valid @RequestBody FinanceModels.Verification input) { return service.verifyInvoice(id,input); }
    @GetMapping("/configuration") public Map<String,Object> configuration() { return service.configuration(); }
    @PutMapping("/configuration") public Map<String,Object> configureBatch(@Valid @RequestBody FinanceModels.ConfigureBatch input) { return service.configureBatch(input); }
    @PutMapping("/hotels/{hotelId}/configuration") public Map<String,Object> configure(@PathVariable UUID hotelId,@Valid @RequestBody FinanceModels.StoreConfig input) { return service.configure(hotelId,input); }
    @PutMapping("/hotels/{hotelId}/categories/{categoryId}") public Map<String,Object> category(@PathVariable UUID hotelId,@PathVariable UUID categoryId,@Valid @RequestBody FinanceModels.Category input) { return service.category(hotelId,categoryId,input); }
    @PostMapping("/historical-invoices") public Map<String,Object> historical(@Valid @RequestBody FinanceModels.Historical input) { return service.historical(input); }
    @GetMapping("/claims") public Map<String,Object> list(@RequestParam(defaultValue="mine") String scope,@RequestParam(defaultValue="0") int page) { return service.list(scope,page); }
    @PostMapping("/claims") public Map<String,Object> create(@Valid @RequestBody FinanceModels.Create input) { return service.create(input); }
    @GetMapping("/claims/{id}") public Map<String,Object> detail(@PathVariable UUID id) { return service.detail(id); }
    @PutMapping("/claims/{id}") public Map<String,Object> save(@PathVariable UUID id,@Valid @RequestBody FinanceModels.Draft input) { return service.save(id,input); }
    @PostMapping("/claims/{id}/submit") public Map<String,Object> submit(@PathVariable UUID id,@Valid @RequestBody FinanceModels.Version input) { return service.submit(id,input.expectedVersion()); }
    @PostMapping("/claims/{id}/withdraw") public Map<String,Object> withdraw(@PathVariable UUID id,@Valid @RequestBody FinanceModels.Version input) { return service.withdraw(id,input.expectedVersion()); }
    @PostMapping("/claims/{id}/review") public Map<String,Object> review(@PathVariable UUID id,@Valid @RequestBody FinanceModels.Review input) { return service.review(id,input); }
    @PostMapping("/claims/{id}/payment") public Map<String,Object> payment(@PathVariable UUID id,@Valid @RequestBody FinanceModels.Payment input) { return service.payment(id,input); }
    @PostMapping("/claims/{id}/payments/{paymentId}/receipt") public Map<String,Object> receipt(@PathVariable UUID id,@PathVariable UUID paymentId,@Valid @RequestBody FinanceModels.Receipt input) { return service.linkPaymentReceipt(id,paymentId,input); }
    @PostMapping("/claims/{id}/attachments/{attachmentId}") public Map<String,Object> upload(@PathVariable UUID id,@PathVariable UUID attachmentId,@RequestParam String kind,@RequestParam MultipartFile file) { return evidence.upload(id,attachmentId,kind,file); }
    @DeleteMapping("/claims/{id}/attachments/{attachmentId}") public Map<String,Object> remove(@PathVariable UUID id,@PathVariable UUID attachmentId,@RequestParam int expectedVersion) { return service.removeAttachment(id,attachmentId,expectedVersion); }
    @GetMapping("/claims/{id}/attachments/{attachmentId}") public ResponseEntity<Resource> download(@PathVariable UUID id,@PathVariable UUID attachmentId) {
        var file=evidence.download(id,attachmentId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mediaType()))
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.name(),StandardCharsets.UTF_8).build().toString())
            .header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff").body(file.resource());
    }
    @GetMapping("/ledger") public Map<String,Object> ledger(@RequestParam String month,@RequestParam(required=false) UUID hotelId) { return service.ledger(month,hotelId); }
    @GetMapping("/ledger/export") public ResponseEntity<byte[]> export(@RequestParam String month,@RequestParam(required=false) UUID hotelId) { return csv(service.export(month,hotelId),"finance-expenses.csv"); }
    @GetMapping("/claims/{id}/voucher") public Map<String,Object> voucher(@PathVariable UUID id) { return service.voucher(id); }
    @GetMapping("/claims/{id}/voucher/export") public ResponseEntity<byte[]> voucherExport(@PathVariable UUID id) { return csv(service.voucherExport(id),"voucher-draft.csv"); }
    private ResponseEntity<byte[]> csv(String text,String name) {
        return ResponseEntity.ok().contentType(new MediaType("text","csv",StandardCharsets.UTF_8))
            .header(HttpHeaders.CACHE_CONTROL,"no-store").header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+name+"\"")
            .body(("\uFEFF"+text).getBytes(StandardCharsets.UTF_8));
    }
}
