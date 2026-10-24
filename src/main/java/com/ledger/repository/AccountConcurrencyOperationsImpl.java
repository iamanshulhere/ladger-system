package com.ledger.repository;

import com.ledger.model.Account;
import com.ledger.model.AccountStatus;
import com.mongodb.client.result.UpdateResult;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

public class AccountConcurrencyOperationsImpl implements AccountConcurrencyOperations {

    private final MongoTemplate mongoTemplate;

    public AccountConcurrencyOperationsImpl(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public boolean touchActiveAccount(ObjectId accountId) {
        return incrementVersion(Query.query(Criteria.where("_id").is(accountId)
                .and("status").is(AccountStatus.ACTIVE)));
    }

    @Override
    public boolean touchActiveAccountOwnedBy(ObjectId accountId, ObjectId ownerId) {
        return incrementVersion(Query.query(Criteria.where("_id").is(accountId)
                .and("user").is(ownerId)
                .and("status").is(AccountStatus.ACTIVE)));
    }

    private boolean incrementVersion(Query query) {
        UpdateResult result = mongoTemplate.updateFirst(query, new Update().inc("__v", 1), Account.class);
        return result.getMatchedCount() == 1;
    }
}
