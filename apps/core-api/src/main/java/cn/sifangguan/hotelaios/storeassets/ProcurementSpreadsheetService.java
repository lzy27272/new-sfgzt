package cn.sifangguan.hotelaios.storeassets;

import cn.sifangguan.hotelaios.shared.imports.XlsxWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;

/** Preview only. Applying still uses the normal scoped, versioned draft API. */
@Service
public class ProcurementSpreadsheetService {
    private final ProcurementService service;
    private final XlsxWorkbook workbook;
    public ProcurementSpreadsheetService(ProcurementService service,XlsxWorkbook workbook){this.service=service;this.workbook=workbook;}
    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> items(UUID hotel){return (List<Map<String,Object>>)service.hotel(hotel).get("items");}
    public byte[] template(UUID hotel) {
        var sheets=new LinkedHashMap<String,List<List<String>>>();
        sheets.put("采购明细",List.of(List.of("物品编码","数量","单位")));
        var catalog=new ArrayList<List<String>>();catalog.add(List.of("物品编码","物品名称","规格","基础单位","采购单位","换算系数"));
        for(var i:items(hotel))catalog.add(List.of(s(i,"sku"),s(i,"name"),s(i,"specification"),s(i,"unit"),s(i,"purchase_unit"),s(i,"purchase_factor")));
        sheets.put("门店物品参考",catalog);
        sheets.put("填写说明",List.of(List.of("在采购明细填写本门店物品编码、正数数量和后台配置单位；最多200项；请勿填写公式。"),List.of("导入仅添加到草稿，确认提交后才发起采购；不改变库存。新物品请先在物品配置中建档。")));
        return workbook.write(sheets);
    }
    public Map<String,Object> preview(UUID hotel,MultipartFile file) {
        var bySku=new HashMap<String,Map<String,Object>>();for(var i:items(hotel))bySku.put(s(i,"sku"),i);
        if(file.isEmpty()||file.getSize()>10485760L)throw new IllegalArgumentException("请选择10MB以内的xlsx文件");
        List<List<String>> rows;
        try {rows=workbook.read(file.getBytes()).sheets().stream().filter(v->v.name().equals("采购明细")).findFirst().orElseThrow(()->new IllegalArgumentException("缺少采购明细工作表，请下载模板")).rows();}
        catch(IOException e){throw new IllegalArgumentException("无法读取采购表格",e);}
        if(rows.isEmpty()||!rows.getFirst().equals(List.of("物品编码","数量","单位")))throw new IllegalArgumentException("表头应为物品编码、数量、单位");
        var results=new ArrayList<Map<String,Object>>();var seen=new HashSet<UUID>();boolean valid=true;
        for(int n=1;n<rows.size();n++) {
            var cells=rows.get(n);if(cells.stream().allMatch(String::isBlank))continue;
            if(results.size()>=200)throw new IllegalArgumentException("每单最多200项物品");
            var result=new LinkedHashMap<String,Object>();result.put("row",n+1);
            try {
                if(cells.size()!=3)throw new IllegalArgumentException("请填写编码、数量、单位三列");
                var item=bySku.get(cells.get(0).trim());if(item==null)throw new IllegalArgumentException("物品编码不属于本门店启用物品，请先建档");
                UUID id=(UUID)item.get("id");if(!seen.add(id))throw new IllegalArgumentException("同一物品重复，请合并数量");
                var quantity=StoreItemUnits.exact(new BigDecimal(cells.get(1).trim()),4,14,"采购数量");if(quantity.signum()<=0)throw new IllegalArgumentException("数量必须大于0");
                var units=StoreItemUnits.from(item);String unit=units.selectedUnit(cells.get(2).trim());
                result.put("itemId",id);result.put("name",item.get("name"));result.put("quantity",quantity);result.put("unit",unit);result.put("baseQuantity",units.baseQuantity(quantity,unit));result.put("error","");
            } catch(IllegalArgumentException e){valid=false;result.put("error",e instanceof NumberFormatException?"数量须为有效数字":e.getMessage());}
            results.add(result);
        }
        return Map.of("valid",valid&&!results.isEmpty(),"rows",results);
    }
    @SuppressWarnings("unchecked")
    public byte[] export(UUID id) {
        var detail=service.detail(id);var rows=new ArrayList<List<String>>();
        rows.add(List.of("物品编码","名称","采购数量","采购单位","基础数量","基础单位","已复核入库","待复核数量","未入库差额","采购单价"));
        for(var line:(List<Map<String,Object>>)detail.get("lines"))rows.add(List.of(s(line,"sku"),s(line,"item_name"),s(line,"input_quantity"),s(line,"input_unit"),s(line,"quantity"),s(line,"base_unit"),s(line,"approved_quantity"),s(line,"pending_quantity"),new BigDecimal(s(line,"quantity")).subtract(new BigDecimal(s(line,"approved_quantity"))).toPlainString(),s(line,"unit_price")));
        var sheets=new LinkedHashMap<String,List<List<String>>>();sheets.put("采购对账",rows);
        var snapshots=new ArrayList<List<String>>();snapshots.add(List.of("收货人","物品","基础单位","实际到货","合格","拒收","复核通过","入库前","入库后","入库金额"));
        for(var r:(List<Map<String,Object>>)detail.get("receipts"))for(var l:(List<Map<String,Object>>)r.get("lines"))snapshots.add(List.of(s(r,"receiver_name"),s(l,"item_name"),s(l,"base_unit"),s(l,"arrived"),s(l,"accepted"),s(l,"rejected"),"APPROVED".equals(r.get("status"))?"是":"否",s(l,"quantity_before"),s(l,"quantity_after"),s(l,"value_delta")));
        sheets.put("收货入库留痕",snapshots);return workbook.write(sheets);
    }
    private static String s(Map<String,Object> m,String key){return Objects.toString(m.get(key),"");}
}
