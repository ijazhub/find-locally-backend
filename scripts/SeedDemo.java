import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Properties;

/** Local utility: java -cp <postgresql-jdbc.jar> scripts/SeedDemo.java */
class SeedDemo {
    public static void main(String[] args) throws Exception {
        var properties = new Properties();
        Path config = Path.of("deploy/postgres.properties");
        if (Files.exists(config)) {
            try (var reader = Files.newBufferedReader(config)) { properties.load(reader); }
        }
        String database = setting(properties, "POSTGRES_DB", "findlocally");
        String url = setting(properties, "SPRING_DATASOURCE_URL", "jdbc:postgresql://localhost:5433/" + database);
        if (!url.equals("jdbc:postgresql://localhost:5433/findlocally")) {
            throw new IllegalStateException("Demo utility requires the local findlocally database");
        }
        String username = setting(properties, "SPRING_DATASOURCE_USERNAME", setting(properties, "POSTGRES_USER", "sa"));
        String password = setting(properties, "SPRING_DATASOURCE_PASSWORD", setting(properties, "POSTGRES_PASSWORD", ""));
        if (password.isEmpty()) throw new IllegalStateException("Configure the local PostgreSQL password before seeding");
        try (var connection = DriverManager.getConnection(url, username, password);
             var statement = connection.createStatement()) {
            statement.execute(Files.readString(Path.of("scripts/seed-demo.sql")));
            System.out.println("Demo seed committed successfully.");
        }
    }

    private static String setting(Properties properties, String key, String fallback) {
        String environment = System.getenv(key);
        return environment != null ? environment : properties.getProperty(key, fallback);
    }
}
