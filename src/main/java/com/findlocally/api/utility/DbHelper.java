package com.findlocally.api.utility;

import java.util.List;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

/** Shared operations using named parameters supplied separately from SQL text. */
@Component
public class DbHelper {
    private final NamedParameterJdbcTemplate jdbc;

    public DbHelper(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns the affected row count for UPDATE or DELETE. */
    public int update(String sql, SqlParameterSource parameters) {
        return jdbc.update(sql, parameters);
    }

    public <T> List<T> getRows(String sql, SqlParameterSource parameters, RowMapper<T> mapper) {
        return jdbc.query(sql, parameters, mapper);
    }

    /** Maps beans, records, and classes with constructor parameters. */
    public <T> List<T> getRows(String sql, SqlParameterSource parameters, Class<T> rowType) {
        return getRows(sql, parameters, DataClassRowMapper.newInstance(rowType));
    }

    /** Null for no row; multiple rows raise IncorrectResultSizeDataAccessException. */
    public <T> T getSingleField(String sql, SqlParameterSource parameters, Class<T> fieldType) {
        return DataAccessUtils.singleResult(jdbc.queryForList(sql, parameters, fieldType));
    }

    public int insertWithoutKey(String sql, SqlParameterSource parameters) {
        return jdbc.update(sql, parameters);
    }

    /** Requests only the specified key column, supporting UUID and numeric keys. */
    public <T> T insertWithKey(String sql, SqlParameterSource parameters,
                               String keyColumn, Class<T> keyType) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        int inserted = jdbc.update(sql, parameters, keyHolder, new String[] {keyColumn});
        if (inserted != 1) {
            throw new DataRetrievalFailureException("Expected one inserted row but got " + inserted);
        }
        T key = keyHolder.getKeyAs(keyType);
        if (key == null) {
            throw new DataRetrievalFailureException("Insert did not return key column " + keyColumn);
        }
        return key;
    }
}
