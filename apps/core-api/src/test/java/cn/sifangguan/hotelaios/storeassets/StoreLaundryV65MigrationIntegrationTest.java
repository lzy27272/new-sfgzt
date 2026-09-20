package cn.sifangguan.hotelaios.storeassets;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.*;

class StoreLaundryV65MigrationIntegrationTest {
    @Test void migratesWithoutBypassRlsPreservesStockAndIsolatesNewTables() throws Exception {
        try(var pg=EmbeddedPostgres.builder().start()) {
            var source=pg.getPostgresDatabase(); var jdbc=new JdbcTemplate(source);
            Flyway.configure().dataSource(source).locations("classpath:db/migration").target("64").load().migrate();
            String tenant="10000000-0000-0000-0000-000000000001", hotel="12000000-0000-0000-0000-000000000003";
            String item="65000000-0000-0000-0000-000000000001", actor="19000000-0000-0000-0000-000000000001";
            jdbc.update("insert into store_resource_item(id,tenant_id,hotel_org_unit_id,sku,name,category,unit,unit_cost) values(?::uuid,?::uuid,?::uuid,'PRE-LAUNDRY','原有浴巾','LINEN','条',10)",item,tenant,hotel);
            jdbc.update("insert into store_inventory_balance(tenant_id,hotel_org_unit_id,item_id,quantity,inventory_value) values(?::uuid,?::uuid,?::uuid,100,1000)",tenant,hotel,item);
            String before=jdbc.queryForObject("select to_jsonb(b)::text from store_inventory_balance b where item_id=?::uuid",String.class,item);
            jdbc.execute("create role laundry_migrator login password 'local-only' nosuperuser nobypassrls");
            jdbc.execute("grant usage,create on schema public to laundry_migrator");
            jdbc.execute("grant all on all tables in schema public to laundry_migrator");
            jdbc.execute("grant all on all sequences in schema public to laundry_migrator");
            jdbc.execute("alter table store_resource_item owner to laundry_migrator");
            jdbc.execute("alter table store_inventory_movement owner to laundry_migrator");
            String url; try(var c=source.getConnection()) { url=c.getMetaData().getURL(); }
            Flyway.configure().dataSource(url,"laundry_migrator","local-only").locations("classpath:db/migration").target("65").load().migrate();
            assertThat(jdbc.queryForObject("select to_jsonb(b)::text from store_inventory_balance b where item_id=?::uuid",String.class,item)).isEqualTo(before);
            for(String table:java.util.List.of("store_laundry_rate","store_laundry_order","store_laundry_line","store_laundry_receipt","store_laundry_receipt_line")) {
                assertThat(jdbc.queryForObject("select count(*) from "+table,Integer.class)).isZero();
                assertThat(jdbc.queryForObject("select relrowsecurity and relforcerowsecurity from pg_class where oid=?::regclass",Boolean.class,table)).isTrue();
            }
            jdbc.update("insert into store_laundry_rate(tenant_id,hotel_org_unit_id,item_id,base_unit,unit_price,updated_by) values(?::uuid,?::uuid,?::uuid,'条',1,?::uuid)",tenant,hotel,item,actor);
            // Even the owner cannot see another tenant. WITH CHECK also rejects cross-tenant inserts.
            try(var connection=DriverManager.getConnection(url,"laundry_migrator","local-only"); var s=connection.createStatement()) {
                s.execute("set app.tenant_id='10000000-0000-0000-0000-000000000002'");
                try(var rs=s.executeQuery("select count(*) from store_laundry_rate")) { rs.next(); assertThat(rs.getInt(1)).isZero(); }
                assertThatThrownBy(()->s.executeUpdate("insert into store_laundry_rate(tenant_id,hotel_org_unit_id,item_id,base_unit,unit_price,updated_by) values('"+tenant+"','"+hotel+"','"+item+"','条',2,'"+actor+"')"))
                        .isInstanceOfSatisfying(java.sql.SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("42501"));
                s.execute("set app.tenant_id='"+tenant+"'");
                try(var rs=s.executeQuery("select count(*) from store_laundry_rate")) { rs.next(); assertThat(rs.getInt(1)).isEqualTo(1); }
            }
        }
    }
}
