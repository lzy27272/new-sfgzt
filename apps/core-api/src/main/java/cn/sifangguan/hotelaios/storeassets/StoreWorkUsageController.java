package cn.sifangguan.hotelaios.storeassets;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.LocalDate;
import com.fasterxml.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/store-resources")
public class StoreWorkUsageController {
    private final StoreWorkUsageService service;
    public StoreWorkUsageController(StoreWorkUsageService service) { this.service = service; }

    @GetMapping("/usage-catalog")
    public Map<String, Object> catalogue(@RequestParam UUID orgUnitId,
            @RequestParam(required=false) LocalDate businessDate, @RequestParam(required=false) String itemCode) {
        return service.catalogue(orgUnitId, businessDate, itemCode);
    }

    @GetMapping("/hotels/{hotelId}/usage-review-config")
    public Map<String, Object> config(@PathVariable UUID hotelId) { return service.configuration(hotelId); }

    @GetMapping("/hotels/{hotelId}/usage-reviewers")
    public List<Map<String, Object>> reviewers(@PathVariable UUID hotelId) { return service.reviewers(hotelId); }

    @PutMapping("/hotels/{hotelId}/usage-review-config")
    public Map<String, Object> configure(@PathVariable UUID hotelId, @Valid @RequestBody Configure request) {
        return service.configure(hotelId, request.reviewerAssignmentId(), request.expectedVersion());
    }

    @GetMapping("/hotels/{hotelId}/work-usage")
    public List<Map<String, Object>> list(@PathVariable UUID hotelId, @RequestParam(defaultValue="PENDING") String status) {
        return service.list(hotelId, status);
    }

    @PostMapping("/hotels/{hotelId}/work-usage/{recordId}/reverse")
    public Map<String, Object> reverse(@PathVariable UUID hotelId, @PathVariable UUID recordId,
                                    @Valid @RequestBody Reverse request) {
        return service.reverse(hotelId, recordId, request.reason());
    }

    public record Configure(@NotNull UUID reviewerAssignmentId, @Min(-1) long expectedVersion) { }
    public record Reverse(@NotBlank @Size(max=1000) String reason) { }

    @GetMapping("/hotels/{hotelId}/usage-quick-list-positions")
    public List<Map<String,Object>> quickListPositions(@PathVariable UUID hotelId) {
        return service.quickListPositions(hotelId);
    }

    @GetMapping("/hotels/{hotelId}/usage-quick-lists/{positionId}")
    public Map<String,Object> quickList(@PathVariable UUID hotelId, @PathVariable UUID positionId) {
        return service.quickList(hotelId, positionId);
    }

    @PutMapping("/hotels/{hotelId}/usage-quick-lists/{positionId}")
    public Map<String,Object> configureQuickList(@PathVariable UUID hotelId, @PathVariable UUID positionId,
                                               @Valid @RequestBody QuickList request) {
        return service.configureQuickList(hotelId, positionId, request.itemIds(), request.expectedVersion());
    }

    public record QuickList(@NotNull @Size(max=100) List<@NotNull UUID> itemIds, @Min(-1) long expectedVersion) { }

    @PostMapping("/hotels/{hotelId}/work-usage/{recordId}/correct")
    public Map<String,Object> correct(@PathVariable UUID hotelId, @PathVariable UUID recordId, @Valid @RequestBody Correct request) {
        return service.correct(hotelId,recordId,request.quantities(),request.expectedVersion(),request.reason());
    }
    public record Correct(@NotNull JsonNode quantities,@Min(0) int expectedVersion,@NotBlank @Size(max=1000) String reason) { }
}
