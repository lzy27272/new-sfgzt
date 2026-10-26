package cn.sifangguan.hotelaios.finance;

import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import cn.sifangguan.hotelaios.workdata.AttachmentMalwareScanner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import static cn.sifangguan.hotelaios.finance.FinanceBusinessModels.*;

/** Stores original financial exports/receipts byte-for-byte; no image sanitation/rotation. */
@Service
public class FinanceBusinessEvidenceService {
 private static final int LIMIT=20*1024*1024;private final Path root;private final FinanceBusinessService service;private final AccessPolicy access;private final AttachmentMalwareScanner scanner;private final ObjectMapper mapper;private final Validator validator;
 public FinanceBusinessEvidenceService(@Value("${app.finance.business-attachments-root:var/finance-business-evidence}")String root,FinanceBusinessService service,AccessPolicy access,AttachmentMalwareScanner scanner,ObjectMapper mapper,Validator validator){this.root=Path.of(root).toAbsolutePath().normalize();this.service=service;this.access=access;this.scanner=scanner;this.mapper=mapper;this.validator=validator;}
 public Map<String,Object> importRevenueFile(MultipartFile file,boolean preview){var input=readImport(file,RevenueImport.class);if(preview)return service.previewRevenue(input);service.importRevenue(input);upload("REVENUE",input.requestId(),UUID.nameUUIDFromBytes(("REVENUE:"+input.requestId()).getBytes(java.nio.charset.StandardCharsets.UTF_8)),file);return service.revenueDetail(input.requestId());}
 public Map<String,Object> importPayrollFile(MultipartFile file){var input=readImport(file,PayrollBatch.class);service.createPayroll(input);upload("PAYROLL",input.requestId(),UUID.nameUUIDFromBytes(("PAYROLL:"+input.requestId()).getBytes(java.nio.charset.StandardCharsets.UTF_8)),file);return service.payrollDetail(input.requestId());}
 private <T>T readImport(MultipartFile file,Class<T> type){if(file.isEmpty()||file.getSize()>LIMIT)throw bad("规范导入文件应为1字节至20MB的JSON；金额必须为十进制文本");try(var stream=file.getInputStream()){byte[] bytes=stream.readNBytes(LIMIT+1);if(bytes.length>LIMIT)throw bad("导入文件超限");T input=mapper.readValue(bytes,type);var errors=validator.validate(input);if(!errors.isEmpty())throw bad("导入字段无效："+errors.stream().map(v->v.getPropertyPath()+" "+v.getMessage()).sorted().limit(5).toList());return input;}catch(ResponseStatusException e){throw e;}catch(Exception e){throw bad("导入文件不是有效的规范JSON，请先按字段映射导出完整来源批次，不能忽略无效行");}}
 public Map<String,Object> upload(String domain,UUID id,UUID attachment,MultipartFile file){service.authorizeAttachment(domain,id);if(file.isEmpty()||file.getSize()>LIMIT)throw bad("来源原件应为1字节至20MB");String key=access.principal().tenantId()+"/"+id+"/"+UUID.randomUUID()+".original";Path path=resolve(key);boolean stored=false;
  try{byte[] bytes;try(var stream=file.getInputStream()){bytes=stream.readNBytes(LIMIT+1);}if(bytes.length==0||bytes.length>LIMIT)throw bad("文件大小超限");Files.createDirectories(path.getParent());Files.write(path,bytes,StandardOpenOption.CREATE_NEW);String scan=scanner.scan(path);if(!scanner.canDownload(scan)||scan.equals("SANITIZED_IMAGE"))throw bad("原件未通过安全扫描，不能改写原件绕过");
   String name=Objects.toString(file.getOriginalFilename(),"财务原件").replaceAll("[\\p{Cntrl}/\\\\]","_");if(name.length()>180)name=name.substring(name.length()-180);String type=Objects.toString(file.getContentType(),"application/octet-stream");if(!Set.of("application/pdf","image/png","image/jpeg","application/xml","text/xml","application/json","text/csv","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","application/octet-stream").contains(type))throw bad("原件类型不支持");
   var result=service.registerAttachment(domain,id,attachment,name,type,bytes.length,FinanceService.hash(bytes),key,scan);stored=key.equals(service.attachment(domain,id,attachment).get("object_key"));return result;
  }catch(ResponseStatusException e){throw e;}catch(IOException|IllegalArgumentException e){throw bad("原件安全扫描或读取失败，请稍后重试，不会丢弃已填内容");}finally{if(!stored)try{Files.deleteIfExists(path);}catch(IOException ignored){}}
 }
 public FinanceEvidenceService.Download download(String domain,UUID id,UUID attachment){var row=service.attachment(domain,id,attachment);if(!scanner.canDownload(row.get("scan_status").toString()))throw bad("原件安全状态不可下载");Path file=resolve(row.get("object_key").toString());try{if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)!=((Number)row.get("size_bytes")).longValue())throw new IOException();byte[] bytes=Files.readAllBytes(file);if(!FinanceService.hash(bytes).equals(row.get("sha256")))throw new IOException();return new FinanceEvidenceService.Download(row.get("original_name").toString(),row.get("media_type").toString(),new ByteArrayResource(bytes));}catch(IOException e){throw new ResponseStatusException(HttpStatus.CONFLICT,"原件缺失或完整性不符，需从备份恢复；不替换历史原件");}}
 private Path resolve(String key){if(!key.matches("[a-f0-9-]{36}/[a-f0-9-]{36}/[a-f0-9-]{36}\\.original"))throw bad("原件索引无效");Path out=root.resolve(key).normalize();if(!out.startsWith(root))throw bad("原件路径无效");return out;}
 private static ResponseStatusException bad(String text){return new ResponseStatusException(HttpStatus.BAD_REQUEST,text);}
}
