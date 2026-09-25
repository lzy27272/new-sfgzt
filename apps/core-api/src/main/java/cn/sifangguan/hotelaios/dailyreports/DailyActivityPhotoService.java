package cn.sifangguan.hotelaios.dailyreports;

import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import cn.sifangguan.hotelaios.workdata.AttachmentService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.io.IOException;

/** Reuses the existing EXIF normalization, image decoding limits and malware scanner. */
@Service
public class DailyActivityPhotoService {
    private final DailyActivityService activities;
    private final AttachmentService attachments;
    private final AccessPolicy access;
    public DailyActivityPhotoService(DailyActivityService activities,AttachmentService attachments,AccessPolicy access) {
        this.activities=activities;this.attachments=attachments;this.access=access;
    }
    public Map<String,Object> upload(UUID report,UUID caseId,UUID photo,MultipartFile file) {
        activities.authorizePhotoUpload(report,caseId);
        if(file==null||file.isEmpty()||file.getSize()>20L*1024*1024)throw new IllegalArgumentException("请选择20MB以内的JPG或PNG照片");
        String key=access.principal().tenantId()+"/daily-activities/"+(report==null?caseId:report)+"/"+UUID.randomUUID()+".image";
        AttachmentService.StoredObject stored=null;boolean committed=false;
        try {
            stored=attachments.storeObject(key,file);
            if(!Set.of("image/jpeg","image/png").contains(stored.mediaType()))throw new IllegalArgumentException("服务案例和遗留物仅支持JPG或PNG照片");
            var result=activities.registerPhoto(report,caseId,photo,stored.originalName(),stored.mediaType(),
                    stored.sizeBytes(),stored.sha256(),stored.objectKey(),stored.scanStatus());
            committed=key.equals(result.get("objectKey"));
            return Map.of("id",photo);
        } catch(IllegalArgumentException error) {
            // Scanner diagnostics may contain server paths; expose a user-facing message only.
            throw new IllegalArgumentException("照片上传失败，请检查照片格式、大小或联系管理员检查安全扫描；已填写内容仍保留");
        } finally {
            if(stored!=null&&!committed)attachments.discardUncommittedObject(stored);
        }
    }
    @Transactional(readOnly=true)
    public AttachmentService.Download download(UUID report,UUID caseId,UUID photo) {
        var row=activities.photo(report,caseId,photo);
        var download=attachments.openStoredObject(row.get("object_key").toString(),row.get("original_name").toString(),
                row.get("media_type").toString(),((Number)row.get("size_bytes")).longValue(),row.get("scan_status").toString());
        try {
            byte[] bytes=download.resource().getContentAsByteArray();
            if(bytes.length!=((Number)row.get("size_bytes")).longValue()||!DailyActivityService.hash(bytes).equals(row.get("sha256")))
                throw new IOException("Integrity mismatch");
            return new AttachmentService.Download(new ByteArrayResource(bytes),download.originalName(),download.mediaType(),bytes.length);
        } catch(IOException error) {throw new IllegalArgumentException("照片完整性校验失败，请联系管理员恢复备份");}
    }
}
