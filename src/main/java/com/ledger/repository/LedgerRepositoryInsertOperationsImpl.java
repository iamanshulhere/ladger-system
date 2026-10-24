package com.ledger.repository;

import com.ledger.model.Ledger;
import org.springframework.data.mongodb.core.MongoTemplate;

public class LedgerRepositoryInsertOperationsImpl implements LedgerRepositoryInsertOperations {

    private final MongoTemplate mongoTemplate;

    public LedgerRepositoryInsertOperationsImpl(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public Ledger insert(Ledger ledger) {
        return mongoTemplate.insert(ledger);
    }
}
