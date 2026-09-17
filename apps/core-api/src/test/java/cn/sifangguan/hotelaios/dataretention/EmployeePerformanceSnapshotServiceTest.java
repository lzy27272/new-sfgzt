package cn.sifangguan.hotelaios.dataretention;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EmployeePerformanceSnapshotServiceTest {

    @Test
    void stableAssignmentIdsProvidesOneGlobalLockOrderForConcurrentRequests() {
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID third = UUID.fromString("00000000-0000-0000-0000-000000000003");

        List<UUID> requestA = EmployeePerformanceSnapshotService.stableAssignmentIds(
                List.of(third, first, second, first));
        List<UUID> requestB = EmployeePerformanceSnapshotService.stableAssignmentIds(
                List.of(second, third, first));

        assertThat(requestA).containsExactly(first, second, third);
        assertThat(requestB).isEqualTo(requestA);
        assertThat(EmployeePerformanceSnapshotService.stableAssignmentIds(null)).isEmpty();
    }

    @Test
    void equalContentIsIdempotentAndChangedContentAppendsRevision() {
        assertThat(EmployeePerformanceSnapshotService.nextRevision(null, 0, "first")).isEqualTo(1);
        assertThat(EmployeePerformanceSnapshotService.nextRevision("same", 3, "same")).isZero();
        assertThat(EmployeePerformanceSnapshotService.nextRevision("old", 3, "new")).isEqualTo(4);
    }
}
