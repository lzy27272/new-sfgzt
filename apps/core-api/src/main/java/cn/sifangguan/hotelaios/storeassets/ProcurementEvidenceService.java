package cn.sifangguan.hotelaios.storeassets;

import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import cn.sifangguan.hotelaios.workdata.AttachmentMalwareScanner;
import cn.sifangguan.hotelaios.workdata.AttachmentService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Stored below the existing attachment backup root; open procurement evidence is never a daily-photo cleanup target. */
@Service
public class ProcurementEvidenceService {
    private final Path root;
    private final ProcurementService service;
    private final AccessPolicy access;
    private final AttachmentService validation;
    private final AttachmentMalwareScanner scanner;
    public ProcurementEvidenceService(@Value("${app.attachments.root:var/attachments}") String root,
            ProcurementService service, AccessPolicy access, AttachmentService validation, AttachmentMalwareScanner scanner) {
        this.root=Path.of(root).toAbsolutePath().normalize().resolve("procurement");
        this.service=service;this.access=access;this.validation=validation;this.scanner=scanner;
    }
    public Map<String,Object> upload(UUID order,UUID receipt,MultipartFile file) {
        service.authorizeAttachment(order,receipt,true);
        if(file==null||file.isEmpty()||file.getSize()>20971520L)throw bad("附件须为1字节至20MB");
        var upload=validation.prepareUpload(file);
        var id=UUID.randomUUID();String key=access.principal().tenantId()+"/"+order+"/"+id+".evidence";
        Path path=resolve(key);boolean committed=false;
        try {
            Files.createDirectories(path.getParent());Files.write(path,upload.content(),StandardOpenOption.CREATE_NEW);
            String scan=scanner.scan(path);
            if(!scanner.canDownload(scan)||("SANITIZED_IMAGE".equals(scan)&&!upload.isImage()))throw bad("附件安全扫描未通过");
            String name=Objects.toString(file.getOriginalFilename(),"采购附件").replaceAll("[\\p{Cntrl}/\\\\]","_");
            if(name.length()>180)name=name.substring(name.length()-180);
            var result=service.registerAttachment(order,receipt,id,name,upload.mediaType(),upload.content().length,
                    ProcurementService.hash(upload.content()),key,scan);
            committed=true;return result;
        } catch(IOException e) {throw bad("附件保存失败，请重试");}
        finally {if(!committed)try{Files.deleteIfExists(path);}catch(IOException ignored){ /* Only this request's orphan. */ }}
    }
    public Download download(UUID order,UUID id) {
        var row=service.attachment(order,id);
        if(row.get("purged_at")!=null)throw new ResponseStatusException(HttpStatus.GONE,"附件已按保留策略清理，数量及复核记录仍保留");
        if(!scanner.canDownload(row.get("scan_status").toString()))throw bad("附件未通过安全扫描");
        try {
            Path path=resolve(row.get("object_key").toString());
            if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)!=((Number)row.get("size_bytes")).longValue())throw new IOException();
            byte[] bytes=Files.readAllBytes(path);if(!ProcurementService.hash(bytes).equals(row.get("sha256")))throw new IOException();
            return new Download(row.get("original_name").toString(),row.get("media_type").toString(),new ByteArrayResource(bytes));
        } catch(IOException e) {throw new ResponseStatusException(HttpStatus.CONFLICT,"附件缺失或校验失败，请联系管理员恢复备份");}
    }
    private Path resolve(String key) {
        if(!key.matches("[a-f0-9-]{36}/[a-f0-9-]{36}/[a-f0-9-]{36}\\.evidence"))throw bad("附件索引无效");
        Path path=root.resolve(key).normalize();if(!path.startsWith(root))throw bad("附件索引无效");return path;
    }
    private static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    public record Download(String name,String type,ByteArrayResource resource) { }
}
