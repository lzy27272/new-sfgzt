package cn.sifangguan.hotelaios.storeassets;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class StoreDepartmentDefaultsV86MigrationIntegrationTest {
    @Test void legacyRowsAreClassifiedUnderForcedRlsWithoutChangingBusinessFactsOrManualScopes() throws Exception {
        try (var pg=EmbeddedPostgres.builder().start()) {
            var source=pg.getPostgresDatabase();
            Flyway.configure().dataSource(source).locations("classpath:db/migration").target("85").load().migrate();
            var jdbc=new JdbcTemplate(source);
            String tenant="10000000-0000-0000-0000-000000000001";
            String hotel="12000000-0000-0000-0000-000000000003";
            String front="12000000-0000-0000-0000-000000000005";
            String hk="12000000-0000-0000-0000-000000000006";
            String position="14000000-0000-0000-0000-000000000001";
            String second=UUID.randomUUID().toString(), secondHotel=UUID.randomUUID().toString(), secondDept=UUID.randomUUID().toString();
            jdbc.update("insert into tenant(id,code,name) values(?::uuid,'SCOPE-V86','隔离租户')",second);
            jdbc.update("insert into org_unit(id,tenant_id,code,name,unit_type) values(?::uuid,?::uuid,'HOTEL','隔离门店','HOTEL')",secondHotel,second);
            jdbc.update("insert into org_unit(id,tenant_id,parent_id,code,name,unit_type) values(?::uuid,?::uuid,?::uuid,'KF','客房部','DEPARTMENT')",secondDept,second,secondHotel);
            jdbc.update("insert into org_unit_closure values(?::uuid,?::uuid,?::uuid,1)",second,secondHotel,secondDept);
            // Same name in another tenant must never be inferred across the boundary.
            String isolated=item(jdbc,second,secondHotel,"KF-SECOND","LEGACY");
            String qt=item(jdbc,tenant,hotel,"QT-S001","LEGACY");
            String kf=item(jdbc,tenant,hotel," kf-S009 ","LEGACY");
            String wx=item(jdbc,tenant,hotel,"WX-S010","LEGACY"); // No local maintenance department.
            String common=item(jdbc,tenant,hotel,"UNKNOWN","LEGACY");
            String malformed=item(jdbc,tenant,hotel,"KFA-S001","LEGACY");
            String manual=item(jdbc,tenant,hotel,"QT-MANUAL","DEPARTMENTS");
            jdbc.update("insert into store_item_usage_department values(?::uuid,?::uuid,?::uuid,?::uuid)",tenant,hotel,manual,hk);
            String explicitCommon=item(jdbc,tenant,hotel,"KF-COMMON","ALL_DEPARTMENTS");
            String legacyManual=item(jdbc,tenant,hotel,"KF-MANUAL","LEGACY");
            jdbc.update("insert into store_item_usage_department values(?::uuid,?::uuid,?::uuid,?::uuid)",tenant,hotel,legacyManual,front);
            jdbc.update("insert into store_item_usage_position values(?::uuid,?::uuid,?::uuid,?::uuid)",tenant,hotel,common,position);
            jdbc.update("insert into store_inventory_balance(tenant_id,hotel_org_unit_id,item_id,quantity,inventory_value) values(?::uuid,?::uuid,?::uuid,83,166)",tenant,hotel,common);
            String asset=UUID.randomUUID().toString();
            jdbc.update("insert into store_fixed_asset(id,tenant_id,hotel_org_unit_id,asset_code,name,category,unit,original_value) values(?::uuid,?::uuid,?::uuid,'QT-ASSET','原有设备','设备','台',900)",asset,tenant,hotel);
            // Reproduce the user's 83 unconfigured items; every legacy row receives a scope.
            for(int i=0;i<83;i++) item(jdbc,tenant,hotel,"OLD-"+i,"LEGACY");
            Map<String,String> facts=new LinkedHashMap<>();
            for(String table:List.of("store_inventory_balance","store_inventory_movement","store_item_usage_position","store_asset_usage_position","store_usage_quick_list_item"))
                facts.put(table,snapshot(jdbc,table,""));
            String itemsBefore=snapshot(jdbc,"store_resource_item","-'usage_scope_mode'-'row_version'-'updated_at'");
            String assetsBefore=snapshot(jdbc,"store_fixed_asset","-'usage_scope_mode'-'row_version'-'updated_at'");
            int expected=jdbc.queryForObject("select (select count(*) from store_resource_item where usage_scope_mode='LEGACY')+(select count(*) from store_fixed_asset where usage_scope_mode='LEGACY')",Integer.class);
            jdbc.execute("create role scope_v86_runner nosuperuser nobypassrls");
            jdbc.execute("grant usage on schema public to scope_v86_runner");
            jdbc.execute("grant select,insert,update on all tables in schema public to scope_v86_runner");
            for(String table:List.of("tenant","store_resource_item","store_fixed_asset","store_item_usage_department","store_asset_usage_department"))
                jdbc.execute("alter table "+table+" owner to scope_v86_runner");
            String sql=new ClassPathResource("db/migration/V86__store_item_department_defaults.sql").getContentAsString(StandardCharsets.UTF_8);
            try(var connection=source.getConnection()) {
                connection.setAutoCommit(false);
                var restricted=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
                restricted.execute("set local role scope_v86_runner");
                restricted.execute("select set_config('app.tenant_id','',true)");
                assertThat(restricted.queryForObject("select count(*) from store_resource_item",Integer.class)).isZero();
                restricted.execute(sql);
                assertThat(restricted.queryForObject("select current_setting('app.tenant_id')",String.class)).isEmpty();
                restricted.execute(sql); // Idempotent even if deliberately rerun outside Flyway.
                assertThat(restricted.queryForObject("select count(*) from tenant",Integer.class)).isZero();
                assertThat(restricted.queryForObject("select count(*) from pg_policies where policyname='v86_tenant_discovery'",Integer.class)).isZero();
                assertThat(restricted.queryForObject("select relforcerowsecurity from pg_class where oid='tenant'::regclass",Boolean.class)).isTrue();
                connection.commit();
            }
            assertThat(jdbc.queryForObject("select count(*) from audit_log where action='STORE_LEGACY_SCOPE_DEFAULTED'",Integer.class)).isEqualTo(expected);
            assertThat(jdbc.queryForObject("select count(*) from store_resource_item where usage_scope_mode='LEGACY'",Integer.class)).isZero();
            for(var entry:facts.entrySet()) assertThat(snapshot(jdbc,entry.getKey(),"")).as(entry.getKey()).isEqualTo(entry.getValue());
            assertThat(snapshot(jdbc,"store_resource_item","-'usage_scope_mode'-'row_version'-'updated_at'")).isEqualTo(itemsBefore);
            assertThat(snapshot(jdbc,"store_fixed_asset","-'usage_scope_mode'-'row_version'-'updated_at'")).isEqualTo(assetsBefore);
            assertThat(department(jdbc,qt)).isEqualTo(front);
            assertThat(department(jdbc,kf)).isEqualTo(hk);
            assertThat(department(jdbc,isolated)).isEqualTo(secondDept);
            assertThat(department(jdbc,manual)).isEqualTo(hk);
            assertThat(department(jdbc,legacyManual)).isEqualTo(front);
            for(String id:List.of(wx,common,malformed,explicitCommon))
                assertThat(jdbc.queryForObject("select usage_scope_mode from store_resource_item where id=?::uuid",String.class,id)).isEqualTo("ALL_DEPARTMENTS");
            for(String id:List.of(manual,explicitCommon))
                assertThat(jdbc.queryForObject("select row_version from store_resource_item where id=?::uuid",Integer.class,id)).isZero();
            assertThat(jdbc.queryForObject("select department_id::text from store_asset_usage_department where asset_id=?::uuid",String.class,asset)).isEqualTo(front);
            assertThat(jdbc.queryForObject("select row_version from store_resource_item where id=?::uuid",Integer.class,common)).isOne();
        }
    }
    private String item(JdbcTemplate jdbc,String tenant,String hotel,String code,String mode) {
        String id=UUID.randomUUID().toString();
        jdbc.update("insert into store_resource_item(id,tenant_id,hotel_org_unit_id,sku,name,category,unit,unit_cost,employee_usage_enabled,usage_scope_mode) values(?::uuid,?::uuid,?::uuid,?,'已有物品','CONSUMABLE','支',2,true,?)",id,tenant,hotel,code,mode);
        return id;
    }
    private String department(JdbcTemplate jdbc,String id) {
        return jdbc.queryForObject("select department_id::text from store_item_usage_department where item_id=?::uuid",String.class,id);
    }
    private String snapshot(JdbcTemplate jdbc,String table,String exclusions) {
        String row="to_jsonb(t)"+exclusions;
        return jdbc.queryForObject("select coalesce(jsonb_agg("+row+" order by ("+row+")::text),'[]'::jsonb)::text from "+table+" t",String.class);
    }
}
