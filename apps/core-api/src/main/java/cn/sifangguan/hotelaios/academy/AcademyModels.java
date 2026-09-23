package cn.sifangguan.hotelaios.academy;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;

public final class AcademyModels {
    private AcademyModels() {}
    public record Course(@NotBlank @Size(max=180) String title, @Size(max=2000) String description,
                         @Pattern(regexp="VIDEO|PPT") @NotNull String kind, List<String> knowledgeTags,
                         List<@Min(1) @Max(600) Integer> pageSeconds, int revision) {}
    public record Question(UUID id, @NotNull UUID courseId,
                           @Pattern(regexp="SINGLE|MULTIPLE|BOOLEAN") @NotNull String kind,
                           @NotBlank @Size(max=4000) String stem,
                           @NotNull @Size(min=2,max=8) List<@NotBlank @Size(max=1000) String> options,
                           @NotNull @Size(min=1,max=8) List<Integer> answers,
                           @Size(max=4000) String explanation, @Size(max=120) String knowledge,
                           @Pattern(regexp="EASY|NORMAL|HARD") @NotNull String difficulty,
                           boolean active, int version) {}
    public record DrawRule(@Pattern(regexp="SINGLE|MULTIPLE|BOOLEAN") @NotNull String kind,
                           @Min(1) @Max(100) int count, @Min(1) @Max(100) int points,
                           @Pattern(regexp="EASY|NORMAL|HARD") String difficulty,
                           @Size(max=120) String knowledge, List<UUID> courseIds) {}
    public record Stage(@NotBlank @Size(max=180) String title, @Size(max=2000) String objective,
                        @NotNull @Size(min=1,max=50) List<UUID> courseIds,
                        @NotNull @Size(min=1,max=30) List<@Valid DrawRule> rules,
                        @Min(1) int passScore, @Min(1) @Max(180) int examMinutes,
                        @Min(1) @Max(20) int attemptLimit, @Min(0) @Max(10080) int retryMinutes,
                        @Min(1) @Max(365) Integer dueDays) {}
    public record GrowthMap(@NotBlank @Size(max=180) String title, @NotNull UUID positionId,
                            UUID scopeOrgId, @NotNull @Size(min=1,max=30) List<@Valid Stage> stages,
                            int revision) {}
    public record Version(int revision) {}
    public record Session(@NotNull UUID planId, @NotNull UUID courseId,
                          @DecimalMin("0") double position, @Min(1) int page) {}
    public record Heartbeat(@Min(1) int sequence, @DecimalMin("0") double position,
                            @Min(1) int page, boolean playing, boolean visible,
                            @DecimalMin("0") double playbackRate) {}
    public record Responses(@NotNull Map<String,List<Integer>> answers, int version) {}
    public record ExtraAttempt(@NotNull UUID stageId, @NotBlank @Size(max=500) String reason) {}
}

