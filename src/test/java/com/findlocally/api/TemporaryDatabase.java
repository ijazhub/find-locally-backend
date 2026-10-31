package com.findlocally.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.regex.Pattern;

/** Subclasses run in a rolled-back transaction, with all V1 tables shadowed by temporary tables. */
abstract class TemporaryDatabase {
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PlatformTransactionManager transactions;

    @BeforeEach
    void temporarySchema() throws Exception {
        String schema = new ClassPathResource("db/migration/V1__init_schema.sql")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var tables = Pattern.compile("CREATE TABLE (\\w+) \\([\\s\\S]*?\\r?\\n\\);").matcher(schema);
        int count = 0;
        while (tables.find()) {
            String ddl = tables.group().replaceFirst("CREATE TABLE", "CREATE TEMP TABLE");
            jdbc.execute(ddl.substring(0, ddl.length() - 2) + ") ON COMMIT DROP");
            count++;
        }
        org.junit.jupiter.api.Assertions.assertTrue(count >= 25);
        jdbc.execute("ALTER TABLE pg_temp.users ALTER COLUMN phone DROP NOT NULL");
        jdbc.execute("ALTER TABLE pg_temp.users ADD COLUMN password_hash varchar(100), ADD COLUMN failed_login_attempts integer NOT NULL DEFAULT 0, ADD COLUMN locked_until timestamptz");
        jdbc.execute("CREATE UNIQUE INDEX uq_test_users_email ON pg_temp.users (lower(btrim(email))) WHERE email IS NOT NULL");
        jdbc.execute("ALTER TABLE pg_temp.businesses ADD COLUMN onboarding_data jsonb NOT NULL DEFAULT '{}'");
        jdbc.execute("ALTER TABLE pg_temp.idempotency_keys ADD COLUMN request_hash varchar(64)");
        jdbc.execute("CREATE UNIQUE INDEX uq_test_addresses_default ON pg_temp.addresses(user_id) WHERE is_default");
        String resets = new ClassPathResource("db/migration/V6__password_reset_tokens.sql")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        jdbc.execute(resets.substring(0, resets.indexOf("CREATE INDEX"))
                .replace("CREATE TABLE password_reset_tokens", "CREATE TEMP TABLE password_reset_tokens")
                .replace(");", ") ON COMMIT DROP;"));
        for (String migration : new String[]{"V7__outbox_events.sql","V8__exclusive_accounts_and_negotiation.sql","V9__media_processing_and_publication.sql","V10__rename_brand_badge.sql"}) {
            String sql=new ClassPathResource("db/migration/"+migration).getContentAsString(java.nio.charset.StandardCharsets.UTF_8)
                    .replace("CREATE TABLE ","CREATE TEMP TABLE ")
                    .replace("CREATE FUNCTION ","CREATE FUNCTION pg_temp.")
                    .replace("EXECUTE FUNCTION ","EXECUTE FUNCTION pg_temp.");
            jdbc.execute(sql);
        }
    }

    @AfterEach
    void clearIdentity() { SecurityContextHolder.clearContext(); }

    protected void nested(Runnable action) {
        var tx = new TransactionTemplate(transactions);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        tx.executeWithoutResult(status -> action.run());
    }
}
