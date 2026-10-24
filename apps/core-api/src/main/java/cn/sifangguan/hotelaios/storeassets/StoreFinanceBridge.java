package cn.sifangguan.hotelaios.storeassets;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.math.*;
import java.util.*;
/** Internal finance adapter. Reuses the original item lock, WAC balance and immutable movement writer. */
@Service @Transactional
public class StoreFinanceBridge {
 private final NamedParameterJdbcTemplate jdbc;private final StoreResourceService stock;private final AccessPolicy access;
 public StoreFinanceBridge(NamedParameterJdbcTemplate jdbc,StoreResourceService stock,AccessPolicy access){this.jdbc=jdbc;this.stock=stock;this.access=access;}
 public Map<String,Object> returnReviewedBatch(TenantPrincipal p,UUID hotel,UUID movement,UUID returnId,BigDecimal quantity,String reason){
  access.requirePermission("finance.review");access.requireOrgScope(hotel);
  var b=new MapSqlParameterSource("tenant",p.tenantId()).addValue("hotel",hotel).addValue("movement",movement);
  var origin=jdbc.queryForMap("select * from store_inventory_movement where tenant_id=:tenant and hotel_org_unit_id=:hotel and id=:movement and source_type='PROCUREMENT' and quantity_delta>0",b);
  var item=jdbc.queryForMap("select * from store_resource_item where tenant_id=:tenant and hotel_org_unit_id=:hotel and id=:item for update",b.addValue("item",origin.get("item_id")));
  // Conservative batch eligibility: any intervening outflow consumes the original batch first.
  // Never infer that consumed/issued goods are still returnable merely because a later receipt replenished stock.
  BigDecimal consumed=jdbc.queryForObject("select coalesce(-sum(quantity_delta),0) from store_inventory_movement where tenant_id=:tenant and hotel_org_unit_id=:hotel and item_id=:item and quantity_delta<0 and (occurred_at,id)>(:at,:movement)",b.addValue("at",origin.get("occurred_at")),BigDecimal.class);
  BigDecimal remaining=new BigDecimal(origin.get("quantity_delta").toString()).subtract(consumed);
  if(quantity.signum()<=0||quantity.compareTo(remaining)>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"原批次已领用/耗用/退货部分不可再退；后续补货不恢复原批次可退数量");
  String unit=item.get("unit").toString(),key="finance-return:"+returnId;
  var units=new StoreItemUnits(unit,unit,BigDecimal.ONE,unit,BigDecimal.ONE);
  var request=new StoreResourceModels.CreateMovementRequest((UUID)origin.get("item_id"),"ADJUSTMENT",quantity.negate(),null,reason,null,unit,null);
  return stock.postMovementFact(p,hotel,request,item,"ADJUSTMENT",quantity.negate(),units,unit,BigDecimal.ONE,quantity.negate(),key,hash(key),"PROCUREMENT_RETURN",returnId,null);
 }
 private String hash(String value){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 public Map<String,Object> confirmPhysicalTransfer(TenantPrincipal p,UUID transferId){
  access.requirePermission("store-resource.asset-manage");var b=new MapSqlParameterSource("tenant",p.tenantId()).addValue("id",transferId).addValue("actor",p.actorId());
  var transfer=jdbc.queryForMap("select * from fs_physical_transfer where tenant_id=:tenant and id=:id for update",b);access.requireOrgScope((UUID)transfer.get("target_hotel_id"));
  if(!p.actorId().equals(transfer.get("receiver_id"))||p.actorId().equals(transfer.get("requester_id")))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"必须目标门店独立配置负责人确认实物接收");
  if("CONFIRMED".equals(transfer.get("status")))return transfer;
  var asset=jdbc.queryForMap("select * from store_fixed_asset where tenant_id=:tenant and id=:asset for update",b.addValue("asset",transfer.get("physical_asset_id")));
  if(!Objects.equals(asset.get("hotel_org_unit_id"),transfer.get("source_hotel_id"))||((Number)asset.get("row_version")).longValue()!=((Number)transfer.get("expected_version")).longValue())throw new ResponseStatusException(HttpStatus.CONFLICT,"资产已变化，原调拨不可继续；请核实当前实物状态后重新办理");
  if(jdbc.queryForObject("select count(*) from store_resource_handover h where h.tenant_id=:tenant and h.hotel_org_unit_id in (:hotel,:targetHotel) and h.status not in ('COMPLETED','CANCELLED','REJECTED')",b.addValue("hotel",transfer.get("source_hotel_id")).addValue("targetHotel",transfer.get("target_hotel_id")),Long.class)>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"门店交接未完成，不能在其间调拨实物");
  // Department/position choices belong to the old store. Their full snapshot is retained on the immutable request.
  jdbc.update("delete from store_asset_usage_department where tenant_id=:tenant and asset_id=:asset",b);jdbc.update("delete from store_asset_usage_position where tenant_id=:tenant and asset_id=:asset",b);
  jdbc.update("update store_fixed_asset set hotel_org_unit_id=:target,responsible_assignment_id=null,usage_scope_mode='LEGACY',row_version=row_version+1,updated_at=now() where tenant_id=:tenant and id=:asset",b.addValue("target",transfer.get("target_hotel_id")));
  jdbc.update("update fs_physical_transfer set status='CONFIRMED',confirmed_at=now() where tenant_id=:tenant and id=:id",b);
  return jdbc.queryForMap("select * from fs_physical_transfer where tenant_id=:tenant and id=:id",b);
 }
}
