package cn.sifangguan.hotelaios.workdata;

import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class AttachmentService {
    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE);
    private static final String PDF = MediaType.APPLICATION_PDF_VALUE;
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final long MAX_IMAGE_PIXELS = 40_000_000L;

    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext databaseContext;
    private final AccessPolicy accessPolicy;
    private final AuditWriter auditWriter;
    private final AttachmentMalwareScanner malwareScanner;
    private final Path storageRoot;
    private final long maxSizeBytes;

    public AttachmentService(
            NamedParameterJdbcTemplate jdbc,
            TenantDatabaseContext databaseContext,
            AccessPolicy accessPolicy,
            AuditWriter auditWriter,
            AttachmentMalwareScanner malwareScanner,
            @Value("${app.attachments.root:var/attachments}") String storageRoot,
            @Value("${app.attachments.max-size-bytes:20971520}") long maxSizeBytes
    ) {
        this.jdbc = jdbc;
        this.databaseContext = databaseContext;
        this.accessPolicy = accessPolicy;
        this.auditWriter = auditWriter;
        this.malwareScanner = malwareScanner;
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
        this.maxSizeBytes = maxSizeBytes;
    }

    @Transactional
    public Map<String, Object> upload(UUID workRecordId, MultipartFile file) {
        return upload(workRecordId, file, "FILE_PICKER", null, null, null);
    }

    @Transactional
    public Map<String, Object> upload(
            UUID workRecordId,
            MultipartFile file,
            String requestedCaptureSource,
            String requestedCheckpointCode,
            String requestedEvidenceInstanceKey,
            OffsetDateTime capturedAtClient
    ) {
        TenantPrincipal principal = prepare();
        accessPolicy.requirePermission("work-record.submit");
        RecordTarget record = recordTargetForUpdate(principal, workRecordId);
        requireOwnerOrDelegatedSubmit(principal, record);
        if (Set.of("APPROVED", "REJECTED").contains(record.status())) {
            throw new IllegalArgumentException("已完成复核的工作记录不能追加附件");
        }
        UUID attachmentId = UUID.randomUUID();
        OffsetDateTime receivedAt = OffsetDateTime.now(ZoneOffset.UTC);
        String captureSource = normalizeCaptureSource(requestedCaptureSource);
        String checkpointCode = normalizeCheckpointCode(requestedCheckpointCode);
        String evidenceInstanceKey = normalizeEvidenceInstanceKey(requestedEvidenceInstanceKey);
        if (evidenceInstanceKey != null && checkpointCode == null) {
            throw new IllegalArgumentException("逐房证据必须同时指定检查点");
        }
        String originalName = normalizeOriginalName(file.getOriginalFilename());
        ValidatedUpload upload = validatedFile(file, originalName);
        if ("CAMERA".equals(captureSource) && !ALLOWED_IMAGE_TYPES.contains(upload.mediaType())) {
            throw new IllegalArgumentException("现场拍照证据必须是JPG或PNG图片");
        }
        byte[] content = "CAMERA".equals(captureSource)
                ? addTrustedWatermark(upload.content(), upload.mediaType(), record, receivedAt, attachmentId)
                : upload.content();
        String safeName = safeName(originalName);
        String mediaType = upload.mediaType();
        String objectKey = principal.tenantId() + "/work-records/" + workRecordId + "/"
                + attachmentId + "-" + safeName;
        Path path = resolveObjectKey(objectKey);
        String sha256 = sha256(content);
        var evidenceMetadata = JsonNodeFactory.instance.objectNode()
                .put("trustedTimestampSource", "SERVER_RECEIVED_AT")
                .put("watermarked", "CAMERA".equals(captureSource));
        if (upload.isImage()) {
            evidenceMetadata.put("orientationPolicy", "ADAPTIVE")
                    .put("displayOrientation", imageOrientation(upload.normalizedWidth(), upload.normalizedHeight()))
                    .put("sourceWidth", upload.sourceWidth())
                    .put("sourceHeight", upload.sourceHeight())
                    .put("normalizedWidth", upload.normalizedWidth())
                    .put("normalizedHeight", upload.normalizedHeight())
                    .put("exifOrientation", upload.exifOrientation())
                    .put("orientationNormalized", upload.exifOrientation() != 1);
        }
        if (evidenceInstanceKey != null) {
            evidenceMetadata.put("evidenceInstanceKey", evidenceInstanceKey);
        }
        String scanStatus;
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            scanStatus = malwareScanner.scan(path);
            jdbc.update("""
                    insert into attachment
                        (id, tenant_id, work_record_id, object_key, original_name, media_type,
                         size_bytes, sha256, scan_status, capture_source, checkpoint_code,
                         captured_at_client, received_at, source_sha256, evidence_metadata)
                    values
                        (:id, :tenantId, :workRecordId, :objectKey, :originalName, :mediaType,
                         :sizeBytes, :sha256, :scanStatus, :captureSource, :checkpointCode,
                         :capturedAtClient, :receivedAt, :sourceSha256, cast(:evidenceMetadata as jsonb))
                    """, base(principal)
                    .addValue("id", attachmentId)
                    .addValue("workRecordId", workRecordId)
                    .addValue("objectKey", objectKey)
                    .addValue("originalName", originalName)
                    .addValue("mediaType", mediaType)
                    .addValue("sizeBytes", content.length)
                    .addValue("sha256", sha256)
                    .addValue("scanStatus", scanStatus)
                    .addValue("captureSource", captureSource)
                    .addValue("checkpointCode", checkpointCode)
                    .addValue("capturedAtClient", capturedAtClient)
                    .addValue("receivedAt", receivedAt)
                    .addValue("sourceSha256", upload.sourceSha256())
                    .addValue("evidenceMetadata", evidenceMetadata.toString()));
        } catch (RuntimeException exception) {
            deleteQuietly(path);
            throw exception;
        } catch (Exception exception) {
            deleteQuietly(path);
            throw new IllegalArgumentException("附件保存失败", exception);
        }
        auditWriter.record("WORK_RECORD_ATTACHMENT_UPLOADED", "ATTACHMENT", attachmentId,
                "{\"workRecordId\":\"" + workRecordId + "\",\"sha256\":\"" + sha256
                        + "\",\"scanStatus\":\"" + scanStatus + "\"}");
        return attachmentResponse(attachmentId, workRecordId, objectKey, originalName, mediaType,
                content.length, sha256, scanStatus, captureSource, checkpointCode,
                evidenceInstanceKey, capturedAtClient, receivedAt, upload.sourceSha256());
    }

    public StoredObject storeObject(String objectKey, MultipartFile file) {
        String originalName = normalizeOriginalName(file == null ? null : file.getOriginalFilename());
        ValidatedUpload upload = validatedFile(file, originalName);
        Path path = resolveObjectKey(objectKey);
        String digest = sha256(upload.content());
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, upload.content(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            String scanStatus = malwareScanner.scan(path);
            return new StoredObject(objectKey, originalName, upload.mediaType(), upload.content().length, digest, scanStatus);
        } catch (RuntimeException exception) {
            deleteQuietly(path);
            throw exception;
        } catch (Exception exception) {
            deleteQuietly(path);
            throw new IllegalArgumentException("附件保存失败", exception);
        }
    }

    /**
     * Stores bytes produced by a trusted server-side renderer under the same guarded storage root as attachments.
     * The caller owns the object-key namespace; path traversal is rejected by {@link #resolveObjectKey(String)}.
     */
    public StoredObject storeGeneratedBytes(
            String objectKey,
            String originalName,
            String mediaType,
            byte[] content
    ) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("服务端生成文件的对象键不能为空");
        }
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("服务端生成文件不能为空");
        }
        if (content.length > maxSizeBytes) {
            throw new IllegalArgumentException("服务端生成文件超过允许大小");
        }
        String normalizedName = normalizeOriginalName(originalName);
        String normalizedType = normalizedMediaType(mediaType).split(";", 2)[0].trim();
        if (!Set.of("text/csv", PDF, XLSX).contains(normalizedType)) {
            throw new IllegalArgumentException("不支持的服务端生成文件类型");
        }

        Path path = resolveObjectKey(objectKey);
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp-" + UUID.randomUUID());
        String digest = sha256(content);
        try {
            Files.createDirectories(path.getParent());
            Files.write(temporary, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, path,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            return new StoredObject(
                    objectKey, normalizedName, normalizedType, content.length, digest, "SERVER_GENERATED");
        } catch (Exception exception) {
            deleteQuietly(temporary);
            throw new IllegalArgumentException("服务端生成文件保存失败", exception);
        }
    }

    /** Opens only a server-generated object after its owning service has performed authorization checks. */
    public Download openGeneratedObject(
            String objectKey,
            String originalName,
            String mediaType,
            long sizeBytes,
            String expectedSha256
    ) {
        Path path = resolveObjectKey(objectKey);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("服务端生成文件不存在或尚未完成生成");
        }
        try {
            if (Files.size(path) != sizeBytes) {
                throw new IllegalArgumentException("服务端生成文件大小校验失败");
            }
            if (expectedSha256 == null || !expectedSha256.matches("[0-9a-f]{64}")
                    || !expectedSha256.equals(sha256(path))) {
                throw new IllegalArgumentException("服务端生成文件摘要校验失败");
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("无法校验服务端生成文件", exception);
        }
        return new Download(new FileSystemResource(path), normalizeOriginalName(originalName),
                normalizedMediaType(mediaType), sizeBytes);
    }

    public Download openStoredObject(
            String objectKey,
            String originalName,
            String mediaType,
            long sizeBytes,
            String scanStatus
    ) {
        if (!malwareScanner.canDownload(scanStatus)) {
            throw new IllegalArgumentException("附件尚未通过安全扫描，禁止下载");
        }
        Path path = resolveObjectKey(objectKey);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("附件文件不存在或尚未完成上传");
        }
        return new Download(new FileSystemResource(path), originalName, mediaType, sizeBytes);
    }

    public void removeStoredObject(String objectKey) {
        try {
            Files.deleteIfExists(resolveObjectKey(objectKey));
        } catch (Exception exception) {
            throw new IllegalArgumentException("附件文件删除失败", exception);
        }
    }

    /**
     * Deletes generated export attempts only inside the server-owned tenant/job UUID namespace.
     * When a winner key is supplied, that file and its ancestor directories are retained.
     */
    public void cleanupGeneratedExportJob(UUID tenantId, UUID jobId, String winnerObjectKey) {
        String jobPrefix = tenantId + "/operation-exports/" + jobId + "/";
        Path jobDirectory = resolveObjectKey(jobPrefix);
        Path winner = null;
        if (winnerObjectKey != null) {
            if (!winnerObjectKey.startsWith(jobPrefix)
                    || !winnerObjectKey.substring(jobPrefix.length())
                    .matches("attempt-[0-9]+/[\\p{L}\\p{N}._-]{1,160}")) {
                throw new IllegalArgumentException("导出对象键不属于指定租户和作业命名空间");
            }
            winner = resolveObjectKey(winnerObjectKey);
        }
        if (!Files.exists(jobDirectory)) return;
        Path retained = winner;
        try (var paths = Files.walk(jobDirectory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                if (retained != null && (path.equals(retained) || retained.startsWith(path))) return;
                try {
                    Files.deleteIfExists(path);
                } catch (Exception exception) {
                    throw new GeneratedObjectCleanupException(exception);
                }
            });
        } catch (GeneratedObjectCleanupException exception) {
            throw new IllegalArgumentException("导出作业目录清理失败", exception.getCause());
        } catch (Exception exception) {
            throw new IllegalArgumentException("导出作业目录清理失败", exception);
        }
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(UUID workRecordId) {
        TenantPrincipal principal = prepare();
        accessPolicy.requireAnyPermission("work-record.read", "work-record.review", "work-record.team-read");
        RecordTarget record = recordTarget(principal, workRecordId);
        accessPolicy.requireOrgScope(record.targetOrgUnitId());
        return jdbc.queryForList("""
                select id, work_record_id, object_key, original_name, media_type, size_bytes,
                       sha256, scan_status, capture_source, checkpoint_code, captured_at_client,
                       received_at, source_sha256, evidence_metadata,
                       evidence_metadata ->> 'displayOrientation' as display_orientation,
                       nullif(evidence_metadata ->> 'normalizedWidth', '')::integer as normalized_width,
                       nullif(evidence_metadata ->> 'normalizedHeight', '')::integer as normalized_height,
                       evidence_metadata ->> 'evidenceInstanceKey' as evidence_instance_key,
                       created_at
                from attachment
                where tenant_id = :tenantId and work_record_id = :workRecordId
                order by created_at, id
                """, base(principal).addValue("workRecordId", workRecordId));
    }

    @Transactional(readOnly = true)
    public Download download(UUID attachmentId) {
        TenantPrincipal principal = prepare();
        accessPolicy.requireAnyPermission("work-record.read", "work-record.review", "work-record.team-read");
        Map<String, Object> attachment = jdbc.queryForMap("""
                select a.object_key, a.original_name, a.media_type, a.size_bytes, a.scan_status,
                       w.target_org_unit_id
                from attachment a
                join work_record w on w.tenant_id = a.tenant_id and w.id = a.work_record_id
                where a.tenant_id = :tenantId and a.id = :attachmentId
                """, base(principal).addValue("attachmentId", attachmentId));
        accessPolicy.requireOrgScope((UUID) attachment.get("target_org_unit_id"));
        if (!malwareScanner.canDownload(String.valueOf(attachment.get("scan_status")))) {
            throw new IllegalArgumentException("附件尚未通过安全扫描，禁止下载");
        }
        Path path = resolveObjectKey(String.valueOf(attachment.get("object_key")));
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("附件文件不存在或尚未完成上传");
        }
        Resource resource = new FileSystemResource(path);
        return new Download(resource, String.valueOf(attachment.get("original_name")),
                String.valueOf(attachment.get("media_type")), ((Number) attachment.get("size_bytes")).longValue());
    }

    @Transactional
    public void delete(UUID workRecordId, UUID attachmentId) {
        TenantPrincipal principal = prepare();
        accessPolicy.requirePermission("work-record.submit");
        RecordTarget record = recordTargetForUpdate(principal, workRecordId);
        requireOwnerOrDelegatedSubmit(principal, record);
        if (Set.of("APPROVED", "REJECTED").contains(record.status())) {
            throw new IllegalArgumentException("已完成复核的工作记录不能删除附件");
        }
        String objectKey = jdbc.queryForObject("""
                select object_key from attachment
                where tenant_id = :tenantId and id = :attachmentId and work_record_id = :workRecordId
                """, base(principal).addValue("attachmentId", attachmentId)
                .addValue("workRecordId", workRecordId), String.class);
        int deleted = jdbc.update("""
                delete from attachment
                where tenant_id = :tenantId and id = :attachmentId and work_record_id = :workRecordId
                """, base(principal).addValue("attachmentId", attachmentId)
                .addValue("workRecordId", workRecordId));
        if (deleted != 1) {
            throw new IllegalArgumentException("附件不存在或不属于该工作记录");
        }
        try {
            Files.deleteIfExists(resolveObjectKey(objectKey));
        } catch (Exception exception) {
            throw new IllegalArgumentException("附件文件删除失败", exception);
        }
        auditWriter.record("WORK_RECORD_ATTACHMENT_DELETED", "ATTACHMENT", attachmentId,
                "{\"workRecordId\":\"" + workRecordId + "\"}");
    }

    private ValidatedUpload validatedFile(MultipartFile file, String originalName) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择需要上传的附件");
        }
        if (file.getSize() > maxSizeBytes) {
            throw new IllegalArgumentException("附件大小不能超过" + maxSizeBytes + "字节");
        }
        try {
            byte[] content = file.getBytes();
            String extension = extension(originalName);
            String mediaType = resolvedMediaType(file.getContentType(), extension, content);
            if (!ALLOWED_IMAGE_TYPES.contains(mediaType)) {
                return new ValidatedUpload(content, mediaType, sha256(content), false, 0, 0, 0, 0, 1);
            }
            BufferedImage sourceImage = ImageIO.read(new ByteArrayInputStream(content));
            if (sourceImage == null) {
                throw new IllegalArgumentException("文件内容不是有效图片");
            }
            int sourceWidth = sourceImage.getWidth();
            int sourceHeight = sourceImage.getHeight();
            long pixels = Math.multiplyExact((long) sourceWidth, (long) sourceHeight);
            if (sourceWidth <= 0 || sourceHeight <= 0 || pixels > MAX_IMAGE_PIXELS) {
                throw new IllegalArgumentException("图片像素尺寸过大，无法作为工作证据上传");
            }
            int exifOrientation = MediaType.IMAGE_JPEG_VALUE.equals(mediaType)
                    ? jpegExifOrientation(content) : 1;
            BufferedImage image = normalizeImageOrientation(sourceImage, exifOrientation, mediaType);
            String format = MediaType.IMAGE_PNG_VALUE.equals(mediaType) ? "png" : "jpeg";
            ByteArrayOutputStream sanitized = new ByteArrayOutputStream(content.length);
            if (!ImageIO.write(image, format, sanitized)) {
                throw new IllegalArgumentException("图片格式无法安全重新编码");
            }
            byte[] sanitizedContent = sanitized.toByteArray();
            if (sanitizedContent.length > maxSizeBytes) {
                throw new IllegalArgumentException("重新编码后的图片超过允许大小");
            }
            return new ValidatedUpload(sanitizedContent, mediaType, sha256(content), true,
                    sourceWidth, sourceHeight, image.getWidth(), image.getHeight(), exifOrientation);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("无法读取附件内容", exception);
        }
    }

    private BufferedImage normalizeImageOrientation(BufferedImage source, int orientation, String mediaType) {
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        boolean swapsDimensions = orientation >= 5 && orientation <= 8;
        int targetWidth = swapsDimensions ? sourceHeight : sourceWidth;
        int targetHeight = swapsDimensions ? sourceWidth : sourceHeight;
        int imageType = MediaType.IMAGE_PNG_VALUE.equals(mediaType)
                ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage normalized = new BufferedImage(targetWidth, targetHeight, imageType);
        AffineTransform transform = switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, sourceWidth, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, sourceWidth, sourceHeight);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, sourceHeight);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, sourceHeight, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, sourceHeight, sourceWidth);
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, sourceWidth);
            default -> new AffineTransform();
        };
        Graphics2D graphics = normalized.createGraphics();
        try {
            graphics.drawImage(source, transform, null);
        } finally {
            graphics.dispose();
        }
        return normalized;
    }

    private static String imageOrientation(int width, int height) {
        if (width > height) return "LANDSCAPE";
        if (height > width) return "PORTRAIT";
        return "SQUARE";
    }

    private int jpegExifOrientation(byte[] content) {
        if (!startsWith(content, 0xff, 0xd8)) return 1;
        int offset = 2;
        while (offset + 4 <= content.length) {
            if ((content[offset] & 0xff) != 0xff) return 1;
            while (offset < content.length && (content[offset] & 0xff) == 0xff) offset++;
            if (offset >= content.length) return 1;
            int marker = content[offset++] & 0xff;
            if (marker == 0xda || marker == 0xd9) return 1;
            if (marker == 0x01 || (marker >= 0xd0 && marker <= 0xd8)) continue;
            if (offset + 2 > content.length) return 1;
            int segmentLength = unsignedShort(content, offset, false);
            if (segmentLength < 2 || offset + segmentLength > content.length) return 1;
            int payloadStart = offset + 2;
            int payloadLength = segmentLength - 2;
            if (marker == 0xe1 && payloadLength >= 14
                    && content[payloadStart] == 'E' && content[payloadStart + 1] == 'x'
                    && content[payloadStart + 2] == 'i' && content[payloadStart + 3] == 'f'
                    && content[payloadStart + 4] == 0 && content[payloadStart + 5] == 0) {
                return tiffOrientation(content, payloadStart + 6, payloadLength - 6);
            }
            offset += segmentLength;
        }
        return 1;
    }

    private int tiffOrientation(byte[] content, int start, int length) {
        if (length < 8 || start < 0 || start + length > content.length) return 1;
        boolean littleEndian;
        if (content[start] == 'I' && content[start + 1] == 'I') littleEndian = true;
        else if (content[start] == 'M' && content[start + 1] == 'M') littleEndian = false;
        else return 1;
        if (unsignedShort(content, start + 2, littleEndian) != 42) return 1;
        long directoryOffset = unsignedInt(content, start + 4, littleEndian);
        if (directoryOffset > length - 2L) return 1;
        int directory = start + (int) directoryOffset;
        int entries = unsignedShort(content, directory, littleEndian);
        int limit = start + length;
        for (int index = 0; index < entries; index++) {
            int entry = directory + 2 + index * 12;
            if (entry < directory || entry + 12 > limit) return 1;
            int tag = unsignedShort(content, entry, littleEndian);
            if (tag != 0x0112) continue;
            int type = unsignedShort(content, entry + 2, littleEndian);
            long count = unsignedInt(content, entry + 4, littleEndian);
            if (type != 3 || count < 1) return 1;
            int orientation = unsignedShort(content, entry + 8, littleEndian);
            return orientation >= 1 && orientation <= 8 ? orientation : 1;
        }
        return 1;
    }

    private int unsignedShort(byte[] content, int offset, boolean littleEndian) {
        if (offset < 0 || offset + 2 > content.length) return 0;
        int first = content[offset] & 0xff;
        int second = content[offset + 1] & 0xff;
        return littleEndian ? first | (second << 8) : (first << 8) | second;
    }

    private long unsignedInt(byte[] content, int offset, boolean littleEndian) {
        if (offset < 0 || offset + 4 > content.length) return 0;
        if (littleEndian) {
            return (content[offset] & 0xffL)
                    | ((content[offset + 1] & 0xffL) << 8)
                    | ((content[offset + 2] & 0xffL) << 16)
                    | ((content[offset + 3] & 0xffL) << 24);
        }
        return ((content[offset] & 0xffL) << 24)
                | ((content[offset + 1] & 0xffL) << 16)
                | ((content[offset + 2] & 0xffL) << 8)
                | (content[offset + 3] & 0xffL);
    }

    private String resolvedMediaType(String suppliedType, String extension, byte[] content) {
        String normalized = normalizedMediaType(suppliedType);
        if (("jpg".equals(extension) || "jpeg".equals(extension)) && startsWith(content, 0xff, 0xd8, 0xff)) {
            return MediaType.IMAGE_JPEG_VALUE;
        }
        if ("png".equals(extension) && startsWith(content, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) {
            return MediaType.IMAGE_PNG_VALUE;
        }
        if ("pdf".equals(extension) && startsWith(content, 0x25, 0x50, 0x44, 0x46, 0x2d)) {
            return PDF;
        }
        if ("docx".equals(extension) && isOfficeZip(content, "word/")) {
            return DOCX;
        }
        if ("xlsx".equals(extension) && isOfficeZip(content, "xl/")) {
            return XLSX;
        }
        throw new IllegalArgumentException("仅支持JPG、PNG、PDF、DOCX和XLSX附件，且文件内容必须与扩展名一致（收到 " + normalized + "）");
    }

    private boolean isOfficeZip(byte[] content, String requiredPrefix) {
        if (!startsWith(content, 0x50, 0x4b)) return false;
        boolean contentTypes = false;
        boolean requiredDirectory = false;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries += 1;
                if (entries > 10_000) throw new IllegalArgumentException("Office文档条目数量异常");
                String name = entry.getName().replace('\\', '/');
                if ("[Content_Types].xml".equals(name)) contentTypes = true;
                if (name.startsWith(requiredPrefix)) requiredDirectory = true;
                if (contentTypes && requiredDirectory) return true;
            }
            return false;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            return false;
        }
    }

    private boolean startsWith(byte[] content, int... expected) {
        if (content.length < expected.length) return false;
        for (int index = 0; index < expected.length; index++) {
            if ((content[index] & 0xff) != expected[index]) return false;
        }
        return true;
    }

    private String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private RecordTarget recordTarget(TenantPrincipal principal, UUID workRecordId) {
        return recordTarget(principal, workRecordId, false);
    }

    private RecordTarget recordTargetForUpdate(TenantPrincipal principal, UUID workRecordId) {
        return recordTarget(principal, workRecordId, true);
    }

    private RecordTarget recordTarget(TenantPrincipal principal, UUID workRecordId, boolean forUpdate) {
        return jdbc.queryForObject("""
                select record.status, record.target_org_unit_id, record.position_assignment_id,
                       org.name as org_name, coalesce(item.name, '工作记录') as item_name
                from work_record record
                join org_unit org
                  on org.tenant_id = record.tenant_id and org.id = record.target_org_unit_id
                left join work_package_item item
                  on item.tenant_id = record.tenant_id and item.id = record.work_package_item_id
                where record.tenant_id = :tenantId and record.id = :workRecordId
                """ + (forUpdate ? " for update of record" : ""),
                base(principal).addValue("workRecordId", workRecordId),
                (rs, rowNum) -> new RecordTarget(rs.getString("status"),
                        rs.getObject("target_org_unit_id", UUID.class),
                        rs.getObject("position_assignment_id", UUID.class),
                        rs.getString("org_name"), rs.getString("item_name")));
    }

    private void requireOwnerOrDelegatedSubmit(TenantPrincipal principal, RecordTarget record) {
        accessPolicy.requireOrgScope(record.targetOrgUnitId());
        if (!principal.assignmentIds().contains(record.assignmentId())) {
            accessPolicy.requirePermission("work-record.submit-for-other");
        }
    }

    private TenantPrincipal prepare() {
        TenantPrincipal principal = accessPolicy.principal();
        databaseContext.apply(principal.tenantId());
        return principal;
    }

    private MapSqlParameterSource base(TenantPrincipal principal) {
        return new MapSqlParameterSource("tenantId", principal.tenantId());
    }

    private Path resolveObjectKey(String objectKey) {
        Path resolved = storageRoot.resolve(objectKey.replace('/', java.io.File.separatorChar)).normalize();
        if (!resolved.startsWith(storageRoot)) {
            throw new IllegalArgumentException("附件对象键越界");
        }
        return resolved;
    }

    private static String normalizeOriginalName(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            return "image";
        }
        String name = Path.of(originalName).getFileName().toString().trim();
        return name.length() > 240 ? name.substring(name.length() - 240) : name;
    }

    private static String safeName(String originalName) {
        String result = originalName.replaceAll("[^a-zA-Z0-9._-]", "_");
        return result.isBlank() ? "image" : result;
    }

    private static String normalizedMediaType(String mediaType) {
        return mediaType == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : mediaType.trim().toLowerCase(Locale.ROOT);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算附件摘要", exception);
        }
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path, StandardOpenOption.READ)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static Map<String, Object> attachmentResponse(
            UUID id, UUID recordId, String objectKey, String originalName, String mediaType,
            long sizeBytes, String sha256, String scanStatus, String captureSource,
            String checkpointCode, String evidenceInstanceKey,
            OffsetDateTime capturedAtClient, OffsetDateTime receivedAt,
            String sourceSha256
    ) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("id", id);
        result.put("workRecordId", recordId);
        result.put("objectKey", objectKey);
        result.put("originalName", originalName);
        result.put("mediaType", mediaType);
        result.put("sizeBytes", sizeBytes);
        result.put("sha256", sha256);
        result.put("sourceSha256", sourceSha256);
        result.put("scanStatus", scanStatus);
        result.put("captureSource", captureSource);
        result.put("checkpointCode", checkpointCode);
        result.put("evidenceInstanceKey", evidenceInstanceKey);
        result.put("capturedAtClient", capturedAtClient);
        result.put("receivedAt", receivedAt);
        result.put("contentUrl", "/api/v1/work-data/attachments/" + id + "/content");
        return result;
    }

    private byte[] addTrustedWatermark(
            byte[] content,
            String mediaType,
            RecordTarget record,
            OffsetDateTime receivedAt,
            UUID attachmentId
    ) {
        try {
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(content));
            if (original == null) {
                throw new IllegalArgumentException("现场拍照文件不是有效图片");
            }
            if (original.getWidth() < 320 || original.getHeight() < 240) {
                throw new IllegalArgumentException("现场照片分辨率不能低于320×240，无法形成清晰证据水印");
            }
            boolean png = MediaType.IMAGE_PNG_VALUE.equals(mediaType);
            int imageType = png ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
            BufferedImage watermarked = new BufferedImage(original.getWidth(), original.getHeight(), imageType);
            Graphics2D graphics = watermarked.createGraphics();
            try {
                graphics.drawImage(original, 0, 0, null);
                graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                List<String> rawLines = List.of(
                        "SFG | " + record.orgName(),
                        "SERVER " + receivedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX")),
                        record.itemName(),
                        "EVIDENCE " + attachmentId.toString().substring(0, 8).toUpperCase(Locale.ROOT)
                );
                int fontSize = Math.max(11, Math.min(28,
                        Math.min(original.getWidth() / 28, original.getHeight() / 18)));
                int padding = Math.max(8, fontSize / 2);
                int availableWidth = Math.max(1, original.getWidth() - padding * 2);
                while (fontSize > 10) {
                    graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
                    boolean fits = rawLines.stream()
                            .allMatch(line -> graphics.getFontMetrics().stringWidth(line) <= availableWidth);
                    if (fits) break;
                    fontSize--;
                }
                graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
                List<String> lines = rawLines.stream()
                        .map(line -> fitWatermarkText(graphics, line, availableWidth)).toList();
                int lineHeight = graphics.getFontMetrics().getHeight();
                int boxHeight = lineHeight * lines.size() + padding * 2;
                int y = Math.max(0, original.getHeight() - boxHeight);
                graphics.setColor(new Color(0, 0, 0, 175));
                graphics.fillRect(0, y, original.getWidth(), boxHeight);
                graphics.setColor(Color.WHITE);
                for (int index = 0; index < lines.size(); index++) {
                    graphics.drawString(lines.get(index), padding,
                            y + padding + index * lineHeight + graphics.getFontMetrics().getAscent());
                }
            } finally {
                graphics.dispose();
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream(content.length);
            if (!ImageIO.write(watermarked, png ? "png" : "jpeg", output)) {
                throw new IllegalArgumentException("现场照片水印写入失败");
            }
            byte[] result = output.toByteArray();
            if (result.length > maxSizeBytes) {
                throw new IllegalArgumentException("加盖可信时间水印后的图片超过允许大小");
            }
            return result;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("现场照片水印写入失败", exception);
        }
    }

    private static String fitWatermarkText(Graphics2D graphics, String value, int availableWidth) {
        if (graphics.getFontMetrics().stringWidth(value) <= availableWidth) return value;
        String suffix = "…";
        int end = value.length();
        while (end > 0 && graphics.getFontMetrics().stringWidth(value.substring(0, end) + suffix) > availableWidth) {
            end--;
        }
        return end == 0 ? suffix : value.substring(0, end) + suffix;
    }

    private String normalizeCaptureSource(String value) {
        String normalized = value == null || value.isBlank()
                ? "FILE_PICKER" : value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("FILE_PICKER", "CAMERA").contains(normalized)) {
            throw new IllegalArgumentException("不支持的证据采集方式");
        }
        return normalized;
    }

    private String normalizeCheckpointCode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9_-]{0,79}")) {
            throw new IllegalArgumentException("检查点编码格式不正确");
        }
        return normalized;
    }

    private String normalizeEvidenceInstanceKey(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[\\p{L}\\p{N}][\\p{L}\\p{N}_-]{0,31}")) {
            throw new IllegalArgumentException("房号格式不正确，仅支持字母、数字、下划线或短横线，最长32位");
        }
        return normalized;
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
            // The database operation still fails; an orphan cleanup job may remove the file later.
        }
    }

    private record RecordTarget(
            String status,
            UUID targetOrgUnitId,
            UUID assignmentId,
            String orgName,
            String itemName
    ) {
    }

    public record Download(Resource resource, String originalName, String mediaType, long sizeBytes) {
    }

    public record StoredObject(
            String objectKey,
            String originalName,
            String mediaType,
            long sizeBytes,
            String sha256,
            String scanStatus
    ) {
    }

    private record ValidatedUpload(
            byte[] content,
            String mediaType,
            String sourceSha256,
            boolean isImage,
            int sourceWidth,
            int sourceHeight,
            int normalizedWidth,
            int normalizedHeight,
            int exifOrientation
    ) {
    }

    private static final class GeneratedObjectCleanupException extends RuntimeException {
        GeneratedObjectCleanupException(Throwable cause) {
            super(cause);
        }
    }
}
