package com.findlocally.api;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/** The URL must identify a separate empty disposable database, never the application database. */
@EnabledIfSystemProperty(named="migration.audit.url",matches=".+")
class AccountMigrationSafetyTests {
    @Test void dualRoleAuditBlocksUpgradeWithoutReassigningRoles() throws Exception {
        String url=System.getProperty("migration.audit.url"), user=System.getProperty("spring.datasource.username","test_owner");
        String password=System.getProperty("spring.datasource.password","");
        Flyway.configure().dataSource(url,user,password).target("7").load().migrate();
        UUID account=UUID.randomUUID();
        UUID owner=UUID.randomUUID(), business=UUID.randomUUID();
        try(var connection=DriverManager.getConnection(url,user,password)) {
            try(var insert=connection.prepareStatement("INSERT INTO users(id,email) VALUES(?,?)")) {
                insert.setObject(1,account);insert.setString(2,account+"@test.example");insert.executeUpdate();
            }
            try(var roles=connection.prepareStatement("INSERT INTO user_roles(user_id,role) VALUES(?,'CUSTOMER'),(?,'BUSINESS')")) {
                roles.setObject(1,account);roles.setObject(2,account);roles.executeUpdate();
            }
            try(var insert=connection.prepareStatement("INSERT INTO users(id,email) VALUES(?,?)")) {
                insert.setObject(1,owner);insert.setString(2,owner+"@test.example");insert.executeUpdate();
            }
            try(var role=connection.prepareStatement("INSERT INTO user_roles(user_id,role) VALUES(?,'BUSINESS')")) {
                role.setObject(1,owner);role.executeUpdate();
            }
            try(var insert=connection.prepareStatement("INSERT INTO businesses(id,owner_user_id) VALUES(?,?)")) {
                insert.setObject(1,business);insert.setObject(2,owner);insert.executeUpdate();
            }
            try(var badge=connection.prepareStatement("INSERT INTO business_badges(business_id,badge,awarded_at) VALUES(?,'TRUSTED_BY_NIVA','2026-01-01T00:00:00Z')")) {
                badge.setObject(1,business);badge.executeUpdate();
            }
            var failure=assertThrows(FlywayException.class,() -> Flyway.configure().dataSource(url,user,password).load().migrate());
            assertTrue(failure.getMessage().contains("Account type audit required"));
            try(var result=connection.createStatement().executeQuery("SELECT count(*) FROM user_roles")) {
                assertTrue(result.next());assertEquals(3,result.getInt(1));
            }
            // This deletion represents the test fixture's explicit approved remediation, not migration behavior.
            try(var remediation=connection.prepareStatement("DELETE FROM user_roles WHERE user_id=? AND role='BUSINESS'")) {
                remediation.setObject(1,account);remediation.executeUpdate();
            }
            Flyway.configure().dataSource(url,user,password).load().migrate();
            try(var select=connection.prepareStatement("SELECT badge,awarded_at FROM business_badges WHERE business_id=?")) {
                select.setObject(1,business);
                try(var result=select.executeQuery()) {
                    assertTrue(result.next());assertEquals("TRUSTED_BY_FINDLOCALLY",result.getString("badge"));
                    assertEquals(java.time.Instant.parse("2026-01-01T00:00:00Z"),result.getObject("awarded_at",java.time.OffsetDateTime.class).toInstant());
                }
            }
            try(var insert=connection.prepareStatement("INSERT INTO user_roles(user_id,role) VALUES(?,'BUSINESS')")) {
                insert.setObject(1,account);
                assertThrows(java.sql.SQLException.class,insert::executeUpdate);
            }
            assertThrows(java.sql.SQLException.class,() -> connection.createStatement().executeUpdate("UPDATE user_roles SET role='BUSINESS'"));
            assertThrows(java.sql.SQLException.class,() -> connection.createStatement().executeUpdate("DELETE FROM user_roles"));
            assertThrows(java.sql.SQLException.class,() -> connection.createStatement().executeUpdate("INSERT INTO users(email) VALUES('missing-role@test.example')"));
        }
    }
}
