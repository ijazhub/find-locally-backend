import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.regex.Pattern;

/** Local utility: java -cp <postgresql-jdbc.jar> scripts/SeedDemo.java */
class SeedDemo {
    public static void main(String[] args) throws Exception {
        String config = Files.readString(Path.of("src/main/resources/application.yaml"));
        String url = setting(config, "url");
        if (!url.equals("jdbc:postgresql://localhost:5433/findlocally")) {
            throw new IllegalStateException("Demo utility requires the local findlocally database");
        }
        try (var connection = DriverManager.getConnection(url, setting(config, "username"), setting(config, "password"));
             var statement = connection.createStatement()) {
            statement.execute(Files.readString(Path.of("scripts/seed-demo.sql")));
            System.out.println("Demo seed committed successfully.");
        }
    }

    private static String setting(String config, String key) {
        var matcher = Pattern.compile("(?m)^    " + key + ":\\s*(.+)$").matcher(config);
        if (!matcher.find()) throw new IllegalStateException("Missing datasource " + key);
        return matcher.group(1).strip();
    }
}
