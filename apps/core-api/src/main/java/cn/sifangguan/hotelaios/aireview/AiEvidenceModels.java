package cn.sifangguan.hotelaios.aireview;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;

public final class AiEvidenceModels {
    private AiEvidenceModels() {}
    public record Criterion(@NotBlank @Pattern(regexp="UNIFORM|BADGE|EQUIPMENT|MAKEUP") String code,
                            boolean enabled, @Size(max=1000) String standard) {}
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record Configuration(@NotEmpty @Size(max=100) List<@NotNull UUID> hotelIds,
                                @NotEmpty @Size(max=30) List<@NotBlank @Size(max=120) String> itemCodes,
                                @NotEmpty @Size(max=4) List<@NotNull @Valid Criterion> criteria,
                                @Size(max=1000) String exceptions,
                                @Size(max=6) List<@NotNull UUID> sampleIds,
                                @Pattern(regexp="POSITION|WORK_PARTICIPANTS") String subjectScope,
                                UUID sourceRuleId, @Min(1) Integer sourceRuleVersion) {
        public Configuration(List<UUID> hotelIds,List<String> itemCodes,List<Criterion> criteria,String exceptions,List<UUID> sampleIds) {
            this(hotelIds,itemCodes,criteria,exceptions,sampleIds,null,null,null);
        }
    }
    public record SaveRule(@NotNull UUID positionId, UUID expectedLatestId,
                           @NotNull @Valid Configuration configuration, boolean shadow) {}
    public record CheckRequest(@NotNull UUID subjectAssignmentId,
                               @NotEmpty @Size(max=4) List<@NotNull UUID> attachmentIds,
                               UUID supersedesId) {}
    public record Decision(@Min(0) int expectedVersion,
                            @NotBlank @Pattern(regexp="PASS|RETAKE|OVERTURNED|APPEAL|RETRY") String action,
                            @NotBlank @Size(max=2000) String reason) {}
}
