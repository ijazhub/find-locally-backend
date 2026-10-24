package com.findlocally.api.repository;

import com.findlocally.api.model.Category;
import com.findlocally.api.utility.DbHelper;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;

@Repository
public class CategoryRepository {
    private final DbHelper dbHelper;

    public CategoryRepository(DbHelper dbHelper) {
        this.dbHelper = dbHelper;
    }

    public List<Category> fetchCategories() {
        String sql = """
                SELECT c.slug, c.name, c.tint,
                       (SELECT count(*)::integer FROM businesses b
                        WHERE b.category_id = c.id AND b.status = :status) AS provider_count
                FROM categories c
                ORDER BY c.sort_order, c.name
                """;
        return dbHelper.getRows(sql, new MapSqlParameterSource("status", "ACTIVE"),
                (rs, row) -> new Category()
                        .slug(rs.getString("slug"))
                        .name(rs.getString("name"))
                        .tint(rs.getString("tint"))
                        .providerCount(rs.getInt("provider_count")));
    }
}
