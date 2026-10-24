package com.ledger.repository;

import com.ledger.model.Transaction;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface TransactionRepository extends MongoRepository<Transaction, ObjectId> {

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);
}
