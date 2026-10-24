package com.ledger.repository;

import com.ledger.model.BlacklistedToken;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface BlacklistedTokenRepository extends MongoRepository<BlacklistedToken, ObjectId> {

    Optional<BlacklistedToken> findByToken(String token);

    boolean existsByToken(String token);
}
