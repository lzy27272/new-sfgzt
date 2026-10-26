package cn.sifangguan.hotelaios.finance;

import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import cn.sifangguan.hotelaios.workdata.AttachmentMalwareScanner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Originals share the finance volume, never operational image rotation/compression or photo retention. */
@Service
public class FinanceOperationsEvidenceService {
    private final Path root;private final FinanceOperationsService service;private final AccessPolicy access;private final AttachmentMalwareScanner scanner;
    private static final int LIMIT=20*1024*1024;
    public FinanceOperationsEvidenceService(@Value("${app.finance.attachments-root:var/finance-evidence}")String root,FinanceOperationsService service,AccessPolicy access,AttachmentMalwareScanner scanner){this.root=Path.of(root).toAbsolutePath().normalize();this.service=service;this.access=access;this.scanner=scanner;}
    public Map<String,Object> upload(UUID documentId,UUID attachmentId,String kind,UUID settlementId,MultipartFile file){
        service.authorizeUpload(documentId,kind,settlementId);if(file.isEmpty()||file.getSize()>LIMIT)throw bad("原件应为1字节至20MB的PDF、OFD、XML、JPG或PNG");
        String name=Objects.toString(file.getOriginalFilename(),"财务原件").replaceAll("[\\p{Cntrl}/\\\\]","_");if(name.length()>180)name=name.substring(name.length()-180);
        String key=access.principal().tenantId()+"/"+documentId+"/"+UUID.randomUUID()+".original";Path destination=resolve(key);boolean stored=false;
        try{byte[] bytes;try(var input=file.getInputStream()){bytes=input.readNBytes(LIMIT+1);}if(bytes.length==0||bytes.length>LIMIT)throw bad("原件大小超过20MB");
            Files.createDirectories(destination.getParent());Files.write(destination,bytes,StandardOpenOption.CREATE_NEW);String scan;
            try{scan=scanner.scan(destination);}catch(IllegalArgumentException e){throw bad("财务原件安全扫描未通过或服务不可用，请稍后重试；不会修改原件以绕过扫描");}
            if(!scanner.canDownload(scan)||"SANITIZED_IMAGE".equals(scan))throw bad("财务原件必须通过原始文件安全扫描");String type=FinanceEvidenceService.mediaType(bytes);var extraction=FinanceEvidenceService.inspect(bytes,type);
            var result=service.registerAttachment(documentId,attachmentId,kind,settlementId,name,type,bytes.length,FinanceService.hash(bytes),key,scan,extraction);stored=key.equals(service.attachment(documentId,attachmentId).get("object_key"));return result;
        }catch(ResponseStatusException e){throw e;}catch(IOException|IllegalArgumentException e){throw bad("文件无法安全读取，请上传有效且未加密的财务原件");}
        finally{if(!stored)try{Files.deleteIfExists(destination);}catch(IOException ignored){/* only this uncommitted request's unique path */}}
    }
    public FinanceEvidenceService.Download download(UUID documentId,UUID attachmentId){
        var row=service.attachment(documentId,attachmentId);String scan=row.get("scan_status").toString();if(!scanner.canDownload(scan)||scan.equals("SANITIZED_IMAGE"))throw bad("财务原件未通过安全扫描");Path path=resolve(row.get("object_key").toString());
        try{if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)!=((Number)row.get("size_bytes")).longValue())throw new IOException();byte[] bytes=Files.readAllBytes(path);if(!FinanceService.hash(bytes).equals(row.get("sha256")))throw new IOException();return new FinanceEvidenceService.Download(row.get("original_name").toString(),row.get("media_type").toString(),new ByteArrayResource(bytes));}
        catch(IOException e){throw new ResponseStatusException(HttpStatus.CONFLICT,"原始文件缺失或完整性校验失败，请联系管理员恢复；系统不会替换原件");}
    }
    private Path resolve(String key){if(!key.matches("[a-f0-9-]{36}/[a-f0-9-]{36}/[a-f0-9-]{36}\\.original"))throw bad("原件索引无效");Path target=root.resolve(key).normalize();if(!target.startsWith(root))throw bad("原件索引无效");return target;}
    private static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
}
