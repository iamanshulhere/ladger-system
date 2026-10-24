package com.ledger.repository;

import org.bson.types.ObjectId;

public interface AccountConcurrencyOperations {

    boolean touchActiveAccount(ObjectId accountId);

    boolean touchActiveAccountOwnedBy(ObjectId accountId, ObjectId ownerId);
}
