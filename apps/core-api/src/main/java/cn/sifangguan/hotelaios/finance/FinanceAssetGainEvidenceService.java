package cn.sifangguan.hotelaios.finance;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import cn.sifangguan.hotelaios.workdata.AttachmentMalwareScanner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static cn.sifangguan.hotelaios.finance.FinanceV2Support.*;
/** Original bytes share the backed-up financial volume. Never operational photo processing/rotation. */
@Service
public class FinanceAssetGainEvidenceService {
 private final Path root;private final FinanceAssetGainService service;private final AccessPolicy access;private final AttachmentMalwareScanner scanner;
 private static final int LIMIT=20*1024*1024;
 public FinanceAssetGainEvidenceService(@Value("${app.finance.attachments-root:var/finance-evidence}")String root,FinanceAssetGainService service,AccessPolicy access,AttachmentMalwareScanner scanner){this.root=Path.of(root).toAbsolutePath().normalize();this.service=service;this.access=access;this.scanner=scanner;}
 public Map<String,Object> upload(UUID gain,UUID attachment,MultipartFile file){service.authorizeOriginal(gain);if(file.isEmpty()||file.getSize()>LIMIT)throw bad("估值依据原件应为1字节至20MB");String key=access.principal().tenantId()+"/"+gain+"/"+UUID.randomUUID()+".original";Path path=resolve(key);boolean stored=false;
  try{byte[] bytes;try(var input=file.getInputStream()){bytes=input.readNBytes(LIMIT+1);}if(bytes.length==0||bytes.length>LIMIT)throw bad("估值原件大小无效");String type=FinanceEvidenceService.mediaType(bytes);FinanceEvidenceService.inspect(bytes,type);Files.createDirectories(path.getParent());Files.write(path,bytes,StandardOpenOption.CREATE_NEW);String scan=scanner.scan(path);if(!scanner.canDownload(scan)||scan.equals("SANITIZED_IMAGE"))throw bad("财务估值依据必须通过原文件安全扫描，不以重编码代替原件");String name=Objects.toString(file.getOriginalFilename(),"估值依据").replaceAll("[\\p{Cntrl}/\\\\]","_");if(name.length()>180)name=name.substring(name.length()-180);var result=service.registerOriginal(gain,attachment,name,type,bytes.length,FinanceService.hash(bytes),key,scan);stored=key.equals(service.original(gain,attachment).get("object_key"));return result;
  }catch(ResponseStatusException e){throw e;}catch(IOException|IllegalArgumentException e){throw bad("估值依据原件扫描或读取失败，已填内容保留，请使用有效未加密原件重试");}finally{if(!stored)try{Files.deleteIfExists(path);}catch(IOException ignored){}}
 }
 public FinanceEvidenceService.Download download(UUID gain,UUID attachment){var row=service.original(gain,attachment);if(!scanner.canDownload(row.get("scan_status").toString())||row.get("scan_status").equals("SANITIZED_IMAGE"))throw conflict("原件扫描状态不允许调阅");Path file=resolve(row.get("object_key").toString());try{if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)!=((Number)row.get("size_bytes")).longValue())throw new IOException();byte[] bytes=Files.readAllBytes(file);if(!FinanceService.hash(bytes).equals(row.get("sha256")))throw new IOException();return new FinanceEvidenceService.Download(row.get("original_name").toString(),row.get("media_type").toString(),new ByteArrayResource(bytes));}catch(IOException e){throw conflict("估值依据原件缺失或字节指纹不符，请恢复原始备份，不替换历史原件");}}
 private Path resolve(String key){if(!key.matches("[a-f0-9-]{36}/[a-f0-9-]{36}/[a-f0-9-]{36}\\.original"))throw bad("财务原件索引无效");Path path=root.resolve(key).normalize();if(!path.startsWith(root))throw bad("财务原件路径无效");return path;}
}
