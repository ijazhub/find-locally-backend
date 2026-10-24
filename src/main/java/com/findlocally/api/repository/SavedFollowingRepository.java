package com.findlocally.api.repository;

import com.findlocally.api.model.ProviderPage;
import com.findlocally.api.utility.*;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;

@Repository
public class SavedFollowingRepository {
    private final DbHelper db;
    private final CatalogRepository catalog;
    public SavedFollowingRepository(DbHelper db, CatalogRepository catalog) { this.db = db; this.catalog = catalog; }
    public void add(UUID user, UUID provider, boolean follow) {
        db.insertWithoutKey("INSERT INTO " + table(follow) + " (user_id, business_id) VALUES (:user, :provider) ON CONFLICT DO NOTHING",
                new MapSqlParameterSource("user", user).addValue("provider", provider));
    }
    public void remove(UUID user, UUID provider, boolean follow) {
        db.update("DELETE FROM " + table(follow) + " WHERE user_id = :user AND business_id = :provider",
                new MapSqlParameterSource("user", user).addValue("provider", provider));
    }
    public ProviderPage list(UUID user, String category, boolean follow, Pages page) {
        return catalog.collection(user, category, follow, page);
    }
    private String table(boolean follow) { return follow ? "follows" : "saved_businesses"; }
}

