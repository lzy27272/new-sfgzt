package cn.sifangguan.hotelaios.storeassets;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/store-resources/hotels/{hotelId}/laundry")
public class StoreLaundryController {
    private final StoreLaundryService service;
    public StoreLaundryController(StoreLaundryService service) { this.service = service; }

    @GetMapping("/catalog")
    public List<Map<String,Object>> catalogue(@PathVariable UUID hotelId) { return service.catalogue(hotelId); }
    @PutMapping("/rates/{itemId}")
    public Map<String,Object> rate(@PathVariable UUID hotelId, @PathVariable UUID itemId, @Valid @RequestBody Rate input) {
        return service.configure(hotelId,itemId,input);
    }
    @GetMapping("/orders")
    public Map<String,Object> orders(@PathVariable UUID hotelId, @RequestParam(defaultValue="0") int page,
                                    @RequestParam(defaultValue="ALL") String status) { return service.orders(hotelId,page,status); }
    @GetMapping("/orders/{orderId}")
    public Map<String,Object> detail(@PathVariable UUID hotelId, @PathVariable UUID orderId) { return service.detail(hotelId,orderId); }
    @PostMapping("/orders") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> dispatch(@PathVariable UUID hotelId, @RequestHeader("Idempotency-Key") String key,
                                      @Valid @RequestBody Dispatch input) { return service.dispatch(hotelId,key,input); }
    @PostMapping("/orders/{orderId}/receipts") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> receive(@PathVariable UUID hotelId, @PathVariable UUID orderId,
                                     @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Receive input) {
        return service.receive(hotelId,orderId,key,input);
    }
    @PostMapping("/orders/{orderId}/cancel")
    public Map<String,Object> cancel(@PathVariable UUID hotelId, @PathVariable UUID orderId, @Valid @RequestBody Correction input) {
        return service.cancel(hotelId,orderId,input);
    }
    @PostMapping("/orders/{orderId}/receipts/{receiptId}/reverse")
    public Map<String,Object> reverse(@PathVariable UUID hotelId, @PathVariable UUID orderId, @PathVariable UUID receiptId,
                                     @Valid @RequestBody Correction input) { return service.reverse(hotelId,orderId,receiptId,input); }

    public record Rate(@NotNull @DecimalMin("0") BigDecimal unitPrice, @NotNull @Min(-1) Long expectedVersion,
                       @NotNull @Min(0) Long expectedItemVersion) { }
    public record Dispatch(@NotBlank @Size(max=160) String supplier, @NotNull LocalDate dispatchedOn,
                           @Size(max=1000) String note, @NotEmpty @Size(max=100) List<@NotNull @Valid DispatchLine> lines) { }
    public record DispatchLine(@NotNull UUID itemId, @NotNull @DecimalMin("0.0001") BigDecimal quantity,
                               @NotNull @Min(0) Long expectedRateVersion, @NotNull @Min(0) Long expectedItemVersion) { }
    public record Receive(@NotNull LocalDate receivedOn, @Size(max=1000) String note, boolean confirmWriteOff,
                          @NotNull @Min(0) Long expectedVersion, @NotEmpty @Size(max=100) List<@NotNull @Valid ReceiveLine> lines) { }
    public record ReceiveLine(@NotNull UUID lineId, @NotNull @DecimalMin("0") BigDecimal acceptedQuantity,
                              @NotNull @DecimalMin("0") BigDecimal rewashQuantity,
                              @NotNull @DecimalMin("0") BigDecimal writtenOffQuantity, @Size(max=1000) String differenceReason) { }
    public record Correction(@NotBlank @Size(max=1000) String reason, @NotNull @Min(0) Long expectedVersion) { }
}
