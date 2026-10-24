package cn.sifangguan.hotelaios.finance;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.*;
import static cn.sifangguan.hotelaios.finance.FinanceV2Support.*;
@RestController @RequestMapping("/api/v1/finance/migration")
public class FinanceMigrationController {
 private final FinanceMigrationService service;private final FinanceMigrationFileGuard guard;public FinanceMigrationController(FinanceMigrationService service,FinanceMigrationFileGuard guard){this.service=service;this.guard=guard;}
 @GetMapping("/templates") public Map<String,Object> templates(){return service.templates();}
 @PostMapping("/columns") public Map<String,Object> columns(@RequestParam MultipartFile file)throws IOException{if(file.getSize()>20*1024*1024)throw bad("迁移文件超过20MB");service.templates();byte[] bytes=file.getBytes();guard.inspect(bytes);return service.columns(file.getOriginalFilename(),bytes);}
 @GetMapping("/batches") public Map<String,Object> list(@RequestParam(required=false)UUID bookId){return service.batches(bookId);}
 @PostMapping("/batches") public Map<String,Object> preview(@RequestParam UUID requestId,@RequestParam UUID bookId,@RequestParam String kind,@RequestParam String sourceReference,@RequestParam String mapping,@RequestParam MultipartFile file)throws IOException{if(file.getSize()>20*1024*1024)throw bad("迁移文件超过20MB");service.templates();Map<String,String> fields=new LinkedHashMap<>();try{var source=map(mapping);for(var e:source.entrySet()){if(!(e.getValue() instanceof String value))throw new IllegalArgumentException();fields.put(e.getKey(),value);}}catch(Exception e){throw bad("字段映射须为目标字段到源表头的JSON对象");}byte[] bytes=file.getBytes();guard.inspect(bytes);return service.preview(requestId,bookId,kind,sourceReference,file.getOriginalFilename(),bytes,fields);}
 @GetMapping("/batches/{id}") public Map<String,Object> detail(@PathVariable UUID id){return service.detail(id);}
 @GetMapping("/batches/{id}/original") public ResponseEntity<byte[]> original(@PathVariable UUID id){var file=service.original(id);guard.inspect(file.content());return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mediaType())).header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff").header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.name(),StandardCharsets.UTF_8).build().toString()).body(file.content());}
 @PostMapping("/batches/{id}/commit") public Map<String,Object> commit(@PathVariable UUID id,@Valid @RequestBody FinanceMigrationModels.Command in){return service.commit(id,in);}
 @PostMapping("/batches/{id}/activate") public Map<String,Object> activate(@PathVariable UUID id,@Valid @RequestBody FinanceMigrationModels.Command in){return service.transition(id,in,true);}
 @PostMapping("/batches/{id}/cancel") public Map<String,Object> cancel(@PathVariable UUID id,@Valid @RequestBody FinanceMigrationModels.Command in){return service.transition(id,in,false);}
 @GetMapping("/subledger") public Map<String,Object> subledger(@RequestParam UUID bookId,@RequestParam String kind){return service.subledger(bookId,kind);}
}
