package com.ledger.repository;

import com.ledger.model.Account;
import com.ledger.model.AccountStatus;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AccountRepository extends MongoRepository<Account, ObjectId>, AccountConcurrencyOperations {

    List<Account> findByUser(ObjectId user);

    List<Account> findByUserAndStatus(ObjectId user, AccountStatus status);

    Optional<Account> findByIdAndUser(ObjectId id, ObjectId user);
}
