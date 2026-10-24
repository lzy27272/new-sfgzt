package cn.sifangguan.hotelaios.finance;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.*;
@RestController @RequestMapping("/api/v1/finance/supply")
public class FinanceSupplyController {
 private final FinanceSupplyService service;
 public FinanceSupplyController(FinanceSupplyService service){this.service=service;}
 @GetMapping("/context") public Map<String,Object> context(){return service.context();}
 @GetMapping("/receipts") public Map<String,Object> receipts(@RequestParam UUID hotelId){return service.receipts(hotelId);}
 @GetMapping("/receipts/{id}") public Map<String,Object> receipt(@PathVariable UUID id){return service.receipt(id);}
 @PostMapping("/matches") public Map<String,Object> match(@Valid @RequestBody FinanceSupplyModels.Match in){return service.match(in);}
 @PostMapping("/recognitions/{id}/confirm") public Map<String,Object> confirm(@PathVariable UUID id,@Valid @RequestBody FinanceSupplyModels.Confirm in){return service.confirm(id,in);}
 @PostMapping("/recognitions/{id}/payable") public Map<String,Object> payable(@PathVariable UUID id,@Valid @RequestBody FinanceSupplyModels.Payable in){return service.createPayable(id,in);}
 @PostMapping("/returns") public Map<String,Object> returns(@Valid @RequestBody FinanceSupplyModels.Return in){return service.createReturn(in);}
 @PostMapping("/returns/{id}/review") public Map<String,Object> reviewReturn(@PathVariable UUID id,@Valid @RequestBody FinanceSupplyModels.ReturnReview in){return service.reviewReturn(id,in);}
 @PostMapping("/returns/{id}/settlement") public Map<String,Object> settle(@PathVariable UUID id,@Valid @RequestBody FinanceSupplyModels.Settlement in){return service.settleReturn(id,in);}
 @PostMapping("/inventory/sync") public Map<String,Object> sync(@Valid @RequestBody FinanceSupplyModels.Sync in){return service.syncInventory(in);}
 @PostMapping("/inventory/opening") public Map<String,Object> openingStock(@Valid @RequestBody FinanceSupplyModels.OpeningStock in){return service.linkOpeningStock(in);}
 @GetMapping("/inventory/reconciliation") public Map<String,Object> reconciliation(@RequestParam UUID hotelId,@RequestParam LocalDate throughDate){return service.reconciliation(hotelId,throughDate);}
 @GetMapping("/assets") public Map<String,Object> assets(@RequestParam UUID hotelId){return service.assets(hotelId);}
 @PostMapping("/assets") public Map<String,Object> asset(@Valid @RequestBody FinanceSupplyModels.Asset in){return service.createAsset(in);}
 @GetMapping("/assets/{id}") public Map<String,Object> asset(@PathVariable UUID id){return service.asset(id);}
 @PostMapping("/assets/{id}/events") public Map<String,Object> assetEvent(@PathVariable UUID id,@Valid @RequestBody FinanceSupplyModels.AssetEvent in){return service.assetEvent(id,in);}
 @PostMapping("/physical-transfers") public Map<String,Object> transfer(@Valid @RequestBody FinanceSupplyModels.PhysicalTransfer in){return service.requestPhysicalTransfer(in);}
 @PostMapping("/physical-transfers/{id}/confirm") public Map<String,Object> confirmTransfer(@PathVariable UUID id,@Valid @RequestBody FinanceSupplyModels.Confirm in){return service.confirmPhysicalTransfer(id,in);}
 @PostMapping("/physical-transfers/{id}/cancel") public Map<String,Object> cancelTransfer(@PathVariable UUID id,@Valid @RequestBody FinanceSupplyModels.Confirm in){return service.cancelPhysicalTransfer(id,in);}
 @GetMapping("/physical-transfers") public Map<String,Object> transfers(){return service.physicalTransfers();}
}
