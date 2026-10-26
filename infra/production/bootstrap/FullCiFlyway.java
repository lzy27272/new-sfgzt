import java.nio.file.Path;
import org.flywaydb.core.Flyway;

/** No Spring bootstrap, HTTP server, schedules, WeCom, OTA or AI worker can run here. */
public final class FullCiFlyway {
    public static void main(String[] args) {
        if (args.length != 3 || !(args[0].equals("hotel_ai_os") || args[0].matches("hotel_ai_os_full_ci_[0-9]+_[0-9]+"))
            || !args[2].matches("MIGRATE|VALIDATE") || Runtime.version().feature() != 21)
            throw new IllegalArgumentException("Fixed isolated migration contract required");
        Path sql = Path.of(args[1]).toAbsolutePath().normalize();
        if (!sql.startsWith("/run/hotel-ai-os-full-ci") || !sql.endsWith("classes/db/migration"))
            throw new IllegalArgumentException("Fixed SQL-only runtime required");
        Flyway target = Flyway.configure().dataSource("jdbc:postgresql://127.0.0.1:5432/" + args[0],
            System.getenv("DB_MIGRATION_USERNAME"), System.getenv("DB_MIGRATION_PASSWORD"))
            .locations("filesystem:" + sql).ignoreMigrationPatterns(new String[0]).validateOnMigrate(true).cleanDisabled(true).load();
        if (args[2].equals("MIGRATE")) target.migrate();
        target.validate();
        System.out.println("FULL_CI_FLYWAY_" + args[2] + "_PASS");
    }
}
