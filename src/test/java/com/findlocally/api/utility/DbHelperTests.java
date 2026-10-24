package com.findlocally.api.utility;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.KeyHolder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.*;

class DbHelperTests {
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final DbHelper helper = new DbHelper(jdbc);
    private final MapSqlParameterSource parameters = new MapSqlParameterSource("name", "test");

    @Test
    void singleFieldHandlesMissingAndNullValues() {
        when(jdbc.queryForList("select value", parameters, String.class)).thenReturn(List.of());
        assertNull(helper.getSingleField("select value", parameters, String.class));
        when(jdbc.queryForList("select value", parameters, String.class))
                .thenReturn(java.util.Collections.singletonList(null));
        assertNull(helper.getSingleField("select value", parameters, String.class));
    }

    @Test
    void singleFieldRejectsMultipleRows() {
        when(jdbc.queryForList("select value", parameters, String.class)).thenReturn(List.of("a", "b"));
        assertThrows(IncorrectResultSizeDataAccessException.class,
                () -> helper.getSingleField("select value", parameters, String.class));
    }

    @Test
    void returnsUuidGeneratedKeyAndRequestsOnlyThatColumn() {
        UUID id = UUID.randomUUID();
        when(jdbc.update(eq("insert row"), same(parameters), any(KeyHolder.class), aryEq(new String[]{"id"})))
                .thenAnswer(invocation -> {
                    KeyHolder holder = invocation.getArgument(2);
                    holder.getKeyList().add(Map.of("id", id));
                    return 1;
                });
        assertEquals(id, helper.insertWithKey("insert row", parameters, "id", UUID.class));
    }

    @Test
    void generatedKeyMustExist() {
        when(jdbc.update(eq("insert row"), same(parameters), any(KeyHolder.class), any(String[].class)))
                .thenReturn(1);
        assertThrows(DataRetrievalFailureException.class,
                () -> helper.insertWithKey("insert row", parameters, "id", Long.class));
    }

    @Test
    void keyInsertRequiresExactlyOneRow() {
        when(jdbc.update(eq("insert row"), same(parameters), any(KeyHolder.class), any(String[].class)))
                .thenReturn(0);
        assertThrows(DataRetrievalFailureException.class,
                () -> helper.insertWithKey("insert row", parameters, "id", Long.class));
    }
}
