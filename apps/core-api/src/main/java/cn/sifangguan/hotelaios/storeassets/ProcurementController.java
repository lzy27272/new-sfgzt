package cn.sifangguan.hotelaios.storeassets;

import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/v1/procurement")
public class ProcurementController {
    private final ProcurementService service;
    private final ProcurementEvidenceService evidence;
    private final ProcurementSpreadsheetService sheets;
    public ProcurementController(ProcurementService service,ProcurementEvidenceService evidence,ProcurementSpreadsheetService sheets){this.service=service;this.evidence=evidence;this.sheets=sheets;}
    @GetMapping("/context") public Map<String,Object> context(){return service.context();}
    @GetMapping("/todos") public List<Map<String,Object>> todos(){return service.todos();}
    @GetMapping("/hotels/{hotel}") public Map<String,Object> hotel(@PathVariable UUID hotel){return service.hotel(hotel);}
    @PutMapping("/hotels/{hotel}/config") public Map<String,Object> config(@PathVariable UUID hotel,@Valid @RequestBody ProcurementModels.Configuration in){return service.configure(hotel,in);}
    @PutMapping("/hotels/{hotel}/rules/{item}") public Map<String,Object> rule(@PathVariable UUID hotel,@PathVariable UUID item,@Valid @RequestBody ProcurementModels.Replenishment in){return service.rule(hotel,item,in);}
    @GetMapping("/orders") public List<Map<String,Object>> list(@RequestParam(required=false) UUID hotel,@RequestParam(defaultValue="ALL") String state,@RequestParam(defaultValue="0") int page){return service.list(hotel,state,page);}
    @GetMapping("/orders/{id}") public Map<String,Object> detail(@PathVariable UUID id){return service.detail(id);}
    @PostMapping("/hotels/{hotel}/orders") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> create(@PathVariable UUID hotel,@Valid @RequestBody ProcurementModels.Request in){return service.create(hotel,in);}
    @PutMapping("/orders/{id}") public Map<String,Object> save(@PathVariable UUID id,@Valid @RequestBody ProcurementModels.Request in){return service.save(id,in);}
    @PostMapping("/orders/{id}/submit") public Map<String,Object> submit(@PathVariable UUID id,@Valid @RequestBody ProcurementModels.Command in){return service.submit(id,in);}
    @PostMapping("/orders/{id}/purchase") public Map<String,Object> purchase(@PathVariable UUID id,@Valid @RequestBody ProcurementModels.Purchase in){return service.purchase(id,in);}
    @PostMapping("/orders/{id}/return") public Map<String,Object> returnRequest(@PathVariable UUID id,@Valid @RequestBody ProcurementModels.Command in){return service.returnRequest(id,in);}
    @PutMapping("/orders/{id}/receipts/{receipt}") public Map<String,Object> receipt(@PathVariable UUID id,@PathVariable UUID receipt,@Valid @RequestBody ProcurementModels.Receipt in){return service.saveReceipt(id,receipt,in);}
    @PostMapping("/orders/{id}/receipts/{receipt}/submit") public Map<String,Object> submitReceipt(@PathVariable UUID id,@PathVariable UUID receipt,@Valid @RequestBody ProcurementModels.Command in){return service.submitReceipt(id,receipt,in);}
    @PostMapping("/orders/{id}/receipts/{receipt}/review") public Map<String,Object> review(@PathVariable UUID id,@PathVariable UUID receipt,@Valid @RequestBody ProcurementModels.Review in){return service.review(id,receipt,in);}
    @PostMapping("/orders/{id}/receipts/{receipt}/void") public Map<String,Object> voidDraft(@PathVariable UUID id,@PathVariable UUID receipt,@Valid @RequestBody ProcurementModels.Command in){return service.voidReceiptDraft(id,receipt,in);}
    @PostMapping(value="/orders/{id}/attachments",consumes="multipart/form-data") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> upload(@PathVariable UUID id,@RequestParam(required=false) UUID receipt,@RequestPart("file") MultipartFile file){return evidence.upload(id,receipt,file);}
    @GetMapping("/orders/{id}/attachments/{attachment}") public ResponseEntity<Resource> download(@PathVariable UUID id,@PathVariable UUID attachment){
        var file=evidence.download(id,attachment);boolean image=file.type().startsWith("image/");
        return ResponseEntity.ok().header("Content-Disposition",(image?ContentDisposition.inline():ContentDisposition.attachment()).filename(file.name(),StandardCharsets.UTF_8).build().toString())
            .header("X-Content-Type-Options","nosniff").cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(file.type())).body(file.resource());
    }
    @GetMapping("/hotels/{hotel}/imports/template") public ResponseEntity<byte[]> template(@PathVariable UUID hotel){return excel(sheets.template(hotel),"采购导入模板.xlsx");}
    @PostMapping(value="/hotels/{hotel}/imports/preview",consumes="multipart/form-data") public Map<String,Object> preview(@PathVariable UUID hotel,@RequestPart("file") MultipartFile file){return sheets.preview(hotel,file);}
    @GetMapping("/orders/{id}/export") public ResponseEntity<byte[]> export(@PathVariable UUID id){return excel(sheets.export(id),"采购对账.xlsx");}
    private ResponseEntity<byte[]> excel(byte[] content,String name){return ResponseEntity.ok().header("Content-Disposition",ContentDisposition.attachment().filename(name,StandardCharsets.UTF_8).build().toString())
        .cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(content);}
}
