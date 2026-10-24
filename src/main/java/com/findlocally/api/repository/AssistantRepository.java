package com.findlocally.api.repository;

import com.findlocally.api.utility.DbHelper;
import org.springframework.stereotype.Repository;

/** Persistence skeleton for the Assistant API. */
@Repository
public class AssistantRepository {
    private final DbHelper dbHelper;

    public AssistantRepository(DbHelper dbHelper) {
        this.dbHelper = dbHelper;
    }
}

