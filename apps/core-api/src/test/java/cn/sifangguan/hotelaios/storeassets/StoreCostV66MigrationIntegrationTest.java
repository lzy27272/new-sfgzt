package cn.sifangguan.hotelaios.storeassets;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.*;

class StoreCostV66MigrationIntegrationTest {
    @Test void backfillsUnderRlsWithoutChangingBalancesAndRestrictsBothCostTables() throws Exception {
        try(var pg=EmbeddedPostgres.builder().start()) {
            var source=pg.getPostgresDatabase();var jdbc=new JdbcTemplate(source);
            Flyway.configure().dataSource(source).locations("classpath:db/migration").target("65").load().migrate();
            String tenant="10000000-0000-0000-0000-000000000001",hotel="12000000-0000-0000-0000-000000000003";
            String item="66000000-0000-0000-0000-000000000001",actor="19000000-0000-0000-0000-000000000001";
            jdbc.update("insert into store_resource_item(id,tenant_id,hotel_org_unit_id,sku,name,category,unit,unit_cost) values(?::uuid,?::uuid,?::uuid,'COST-OLD','原库存','CONSUMABLE','支',10)",item,tenant,hotel);
            jdbc.update("insert into store_inventory_balance(tenant_id,hotel_org_unit_id,item_id,quantity,inventory_value) values(?::uuid,?::uuid,?::uuid,9,90)",tenant,hotel,item);
            jdbc.update("insert into store_inventory_movement(tenant_id,hotel_org_unit_id,item_id,movement_type,quantity_delta,quantity_before,quantity_after,unit_cost_snapshot,value_delta,reason,idempotency_key,request_hash,actor_account_id) values(?::uuid,?::uuid,?::uuid,'CONSUMPTION',-1,10,9,10,-10,'原耗用','cost-old',repeat('0',64),?::uuid)",tenant,hotel,item,actor);
            String before=jdbc.queryForObject("select to_jsonb(b)::text from store_inventory_balance b where item_id=?::uuid",String.class,item);
            jdbc.execute("create role cost_migrator login password 'local-only' nosuperuser nobypassrls");
            jdbc.execute("grant usage,create on schema public to cost_migrator");
            jdbc.execute("grant all on all tables in schema public to cost_migrator");
            jdbc.execute("grant all on all sequences in schema public to cost_migrator");
            jdbc.execute("alter table tenant owner to cost_migrator");
            jdbc.execute("alter table store_inventory_movement owner to cost_migrator");
            String url;try(var c=source.getConnection()){url=c.getMetaData().getURL();}
            Flyway.configure().dataSource(url,"cost_migrator","local-only").locations("classpath:db/migration").target("66").load().migrate();
            assertThat(jdbc.queryForObject("select to_jsonb(b)::text from store_inventory_balance b where item_id=?::uuid",String.class,item)).isEqualTo(before);
            var fact=jdbc.queryForMap("select * from store_cost_entry where item_id=?::uuid",item);
            assertThat(fact.get("legacy_classification")).isEqualTo(true);
            assertThat((java.math.BigDecimal)fact.get("amount")).isEqualByComparingTo("10");
            for(String table:java.util.List.of("tenant","store_cost_entry","store_cost_month_close"))
                assertThat(jdbc.queryForObject("select relrowsecurity and relforcerowsecurity from pg_class where oid=?::regclass",Boolean.class,table)).isTrue();
            try(var c=DriverManager.getConnection(url,"cost_migrator","local-only");var s=c.createStatement()) {
                s.execute("set app.tenant_id='10000000-0000-0000-0000-000000000002'");
                for(String table:java.util.List.of("store_cost_entry","store_cost_month_close")) {
                    try(var r=s.executeQuery("select count(*) from "+table)){r.next();assertThat(r.getInt(1)).isZero();}
                }
                s.execute("set app.tenant_id='"+tenant+"'");
                try(var r=s.executeQuery("select count(*) from store_cost_entry")){r.next();assertThat(r.getInt(1)).isEqualTo(1);}
                assertThatThrownBy(()->s.executeUpdate("delete from store_cost_entry")).isInstanceOfSatisfying(java.sql.SQLException.class,e->assertThat(e.getSQLState()).isEqualTo("55000"));
                // Empty context cannot read cross-tenant cost or create a close for another tenant.
                s.execute("reset app.tenant_id");
                try(var r=s.executeQuery("select count(*) from store_cost_entry")){r.next();assertThat(r.getInt(1)).isZero();}
            }
            assertThat(jdbc.queryForObject("select count(*) from permission where code='store-resource.cost-close' and delegable_to_position",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from role_permission rp join permission p on p.id=rp.permission_id where p.code='store-resource.cost-close'",Integer.class)).isZero();
        }
    }
}
