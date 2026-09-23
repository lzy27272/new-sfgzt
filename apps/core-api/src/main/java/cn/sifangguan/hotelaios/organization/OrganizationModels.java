package cn.sifangguan.hotelaios.organization;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class OrganizationModels {
    private OrganizationModels() {
    }

    public record CreateOrgUnit(
            UUID parentId,
            @NotBlank String code,
            @NotBlank String name,
            @NotBlank String unitType,
            Integer sortOrder,
            String propertyCode,
            String city,
            Integer roomCount,
            LocalDate openingDate,
            Boolean breakfastServiceEnabled,
            Integer guestRoomFloorCount,
            String assistantWorkTemplateMode,
            Boolean kitchenEnabled,
            Boolean afternoonTeaEnabled,
            Boolean barEnabled,
            Boolean projectionEnabled,
            Boolean gymEnabled,
            Boolean terraceEnabled,
            Boolean trendyPlayAreaEnabled,
            Map<String, List<String>> facilityEvidenceInstances,
            Map<String, List<String>> assetEvidenceInstances
    ) {
        public CreateOrgUnit(
                UUID parentId, String code, String name, String unitType, Integer sortOrder,
                String propertyCode, String city, Integer roomCount, LocalDate openingDate,
                Boolean breakfastServiceEnabled, Integer guestRoomFloorCount
        ) {
            this(parentId, code, name, unitType, sortOrder, propertyCode, city, roomCount,
                    openingDate, breakfastServiceEnabled, guestRoomFloorCount, null,
                    null, null, null, null, null, null, null, null, null);
        }

        public CreateOrgUnit(
                UUID parentId, String code, String name, String unitType, Integer sortOrder,
                String propertyCode, String city, Integer roomCount, LocalDate openingDate
        ) {
            this(parentId, code, name, unitType, sortOrder, propertyCode, city, roomCount,
                    openingDate, null, null);
        }
    }

    public record UpdateOrgUnit(
            @NotBlank String code,
            @NotBlank String name,
            Integer sortOrder,
            @NotBlank String status,
            String propertyCode,
            String city,
            Integer roomCount,
            LocalDate openingDate,
            Boolean breakfastServiceEnabled,
            Integer guestRoomFloorCount,
            String assistantWorkTemplateMode,
            Boolean kitchenEnabled,
            Boolean afternoonTeaEnabled,
            Boolean barEnabled,
            Boolean projectionEnabled,
            Boolean gymEnabled,
            Boolean terraceEnabled,
            Boolean trendyPlayAreaEnabled,
            Map<String, List<String>> facilityEvidenceInstances,
            Map<String, List<String>> assetEvidenceInstances
    ) {
        public UpdateOrgUnit(
                String code, String name, Integer sortOrder, String status,
                String propertyCode, String city, Integer roomCount, LocalDate openingDate,
                Boolean breakfastServiceEnabled, Integer guestRoomFloorCount
        ) {
            this(code, name, sortOrder, status, propertyCode, city, roomCount,
                    openingDate, breakfastServiceEnabled, guestRoomFloorCount, null,
                    null, null, null, null, null, null, null, null, null);
        }

        public UpdateOrgUnit(
                String code, String name, Integer sortOrder, String status,
                String propertyCode, String city, Integer roomCount, LocalDate openingDate
        ) {
            this(code, name, sortOrder, status, propertyCode, city, roomCount,
                    openingDate, null, null);
        }
    }

    public record CreatePosition(
            @NotBlank String code,
            @NotBlank String name,
            @NotBlank String jobFamily,
            String levelCode
    ) {
    }

    public record UpdatePosition(
            @NotBlank String code,
            @NotBlank String name,
            @NotBlank String jobFamily,
            String levelCode,
            @NotBlank String status
    ) {
    }

    public record CreateEmployee(
            @NotBlank String employeeNo,
            @NotBlank String name,
            String mobile,
            LocalDate hiredOn,
            String loginName,
            String temporaryPassword,
            String employeeType
    ) {
        public CreateEmployee(String employeeNo, String name, String mobile, LocalDate hiredOn, String loginName, String temporaryPassword) {
            this(employeeNo, name, mobile, hiredOn, loginName, temporaryPassword, null);
        }
    }

    public record UpdateEmployee(
            @NotBlank String employeeNo,
            @NotBlank String name,
            String mobile,
            LocalDate hiredOn,
            @NotBlank String employmentStatus,
            String loginName,
            String temporaryPassword,
            String employeeType
    ) {
        public UpdateEmployee(String employeeNo, String name, String mobile, LocalDate hiredOn, String employmentStatus, String loginName, String temporaryPassword) {
            this(employeeNo, name, mobile, hiredOn, employmentStatus, loginName, temporaryPassword, null);
        }
    }

    public record CreatePositionAssignment(
            @NotNull UUID orgUnitId,
            @NotNull UUID positionId,
            UUID managerAssignmentId,
            Boolean primary,
            String assignmentType,
            @NotNull LocalDate validFrom,
            LocalDate validTo,
            List<@NotNull UUID> responsibleHotelIds
    ) {
        public CreatePositionAssignment(
                UUID orgUnitId,
                UUID positionId,
                UUID managerAssignmentId,
                Boolean primary,
                String assignmentType,
                LocalDate validFrom,
                LocalDate validTo
        ) {
            this(orgUnitId, positionId, managerAssignmentId, primary,
                    assignmentType, validFrom, validTo, null);
        }
    }

    public record UpdateAssignmentHotelScope(
            List<@NotNull UUID> responsibleHotelIds
    ) {
    }

    public record UpdatePositionAssignment(
            @NotNull UUID orgUnitId,
            @NotNull UUID positionId,
            UUID managerAssignmentId,
            Boolean primary,
            String assignmentType,
            @NotNull LocalDate validFrom,
            LocalDate validTo,
            List<@NotNull UUID> responsibleHotelIds
    ) {
    }
}
