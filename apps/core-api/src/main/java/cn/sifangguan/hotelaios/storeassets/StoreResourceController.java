package cn.sifangguan.hotelaios.storeassets;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/store-resources")
public class StoreResourceController {
    private final StoreResourceService service;

    public StoreResourceController(StoreResourceService service) {
        this.service = service;
    }

    @GetMapping("/hotels")
    public List<Map<String, Object>> hotels() {
        return service.hotels();
    }

    @GetMapping("/hotels/{hotelId}/items")
    public List<Map<String, Object>> items(
            @PathVariable UUID hotelId,
            @RequestParam(defaultValue = "false") boolean includeInactive
    ) {
        return service.items(hotelId, includeInactive);
    }

    @GetMapping("/hotels/{hotelId}/summary")
    public Map<String, Object> summary(@PathVariable UUID hotelId) {
        return service.summary(hotelId);
    }

    @PostMapping("/hotels/{hotelId}/items")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createItem(
            @PathVariable UUID hotelId,
            @Valid @RequestBody StoreResourceModels.CreateItemRequest request
    ) {
        return service.createItem(hotelId, request);
    }

    @PutMapping("/hotels/{hotelId}/items/{itemId}")
    public Map<String, Object> updateItem(
            @PathVariable UUID hotelId,
            @PathVariable UUID itemId,
            @Valid @RequestBody StoreResourceModels.UpdateItemRequest request
    ) {
        return service.updateItem(hotelId, itemId, request);
    }

    @PostMapping("/hotels/{hotelId}/items/{itemId}/actions/enable")
    public Map<String, Object> enableItem(
            @PathVariable UUID hotelId,
            @PathVariable UUID itemId,
            @Valid @RequestBody StoreResourceModels.VersionCommand request
    ) {
        return service.setItemEnabled(hotelId, itemId, true, request);
    }

    @PostMapping("/hotels/{hotelId}/items/{itemId}/actions/disable")
    public Map<String, Object> disableItem(
            @PathVariable UUID hotelId,
            @PathVariable UUID itemId,
            @Valid @RequestBody StoreResourceModels.VersionCommand request
    ) {
        return service.setItemEnabled(hotelId, itemId, false, request);
    }

    @DeleteMapping("/hotels/{hotelId}/items/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteItem(@PathVariable UUID hotelId, @PathVariable UUID itemId) {
        service.deactivateItem(hotelId, itemId);
    }

    @GetMapping("/hotels/{hotelId}/balances")
    public List<Map<String, Object>> balances(@PathVariable UUID hotelId) {
        return service.balances(hotelId);
    }

    @GetMapping("/hotels/{hotelId}/movements")
    public List<Map<String, Object>> movements(
            @PathVariable UUID hotelId,
            @RequestParam(defaultValue = "100") int limit
    ) {
        return service.movements(hotelId, limit);
    }

    @PostMapping("/hotels/{hotelId}/movements")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createMovement(
            @PathVariable UUID hotelId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody StoreResourceModels.CreateMovementRequest request
    ) {
        return service.createMovement(hotelId, idempotencyKey, request);
    }

    @GetMapping("/hotels/{hotelId}/assets")
    public List<Map<String, Object>> assets(
            @PathVariable UUID hotelId,
            @RequestParam(defaultValue = "false") boolean includeRetired
    ) {
        return service.assets(hotelId, includeRetired);
    }

    @PostMapping("/hotels/{hotelId}/assets")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createAsset(
            @PathVariable UUID hotelId,
            @Valid @RequestBody StoreResourceModels.CreateAssetRequest request
    ) {
        return service.createAsset(hotelId, request);
    }

    @PutMapping("/hotels/{hotelId}/assets/{assetId}")
    public Map<String, Object> updateAsset(
            @PathVariable UUID hotelId,
            @PathVariable UUID assetId,
            @Valid @RequestBody StoreResourceModels.UpdateAssetRequest request
    ) {
        return service.updateAsset(hotelId, assetId, request);
    }

    @DeleteMapping("/hotels/{hotelId}/assets/{assetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void retireAsset(@PathVariable UUID hotelId, @PathVariable UUID assetId) {
        service.retireAsset(hotelId, assetId);
    }

    @GetMapping("/hotels/{hotelId}/assignment-options")
    public List<Map<String, Object>> assignmentOptions(@PathVariable UUID hotelId) {
        return service.assignmentOptions(hotelId);
    }

    @GetMapping("/hotels/{hotelId}/handovers")
    public List<Map<String, Object>> handovers(@PathVariable UUID hotelId) {
        return service.handovers(hotelId);
    }

    @PostMapping("/hotels/{hotelId}/handovers")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createHandover(
            @PathVariable UUID hotelId,
            @Valid @RequestBody StoreResourceModels.CreateHandoverRequest request
    ) {
        return service.createHandover(hotelId, request);
    }

    @GetMapping("/handovers/{handoverId}")
    public Map<String, Object> handover(@PathVariable UUID handoverId) {
        return service.handover(handoverId);
    }

    @PostMapping("/handovers/{handoverId}/actions/dispute-decision")
    public Map<String, Object> decideDispute(
            @PathVariable UUID handoverId,
            @Valid @RequestBody StoreResourceModels.DisputeDecisionRequest request
    ) {
        return service.decideDispute(handoverId, request);
    }

    @PostMapping("/handovers/{handoverId}/actions/physical-count")
    public Map<String, Object> recordPhysicalCount(
            @PathVariable UUID handoverId,
            @Valid @RequestBody StoreResourceModels.PhysicalCountRequest request
    ) {
        return service.recordPhysicalCount(handoverId, request);
    }

    @PostMapping("/handovers/{handoverId}/actions/transferor-confirm")
    public Map<String, Object> confirmTransferor(
            @PathVariable UUID handoverId,
            @Valid @RequestBody StoreResourceModels.VersionCommand request
    ) {
        return service.confirmTransferor(handoverId, request);
    }

    @PostMapping("/handovers/{handoverId}/actions/recipient-confirm")
    public Map<String, Object> confirmRecipient(
            @PathVariable UUID handoverId,
            @Valid @RequestBody StoreResourceModels.VersionCommand request
    ) {
        return service.confirmRecipient(handoverId, request);
    }

    @PostMapping("/handovers/{handoverId}/actions/approve")
    public Map<String, Object> approve(
            @PathVariable UUID handoverId,
            @Valid @RequestBody StoreResourceModels.ApprovalRequest request
    ) {
        return service.approve(handoverId, request);
    }
}
