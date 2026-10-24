package com.ledger.repository;

import com.ledger.model.Ledger;
import java.util.List;
import org.bson.types.ObjectId;
import org.springframework.data.repository.Repository;

public interface LedgerRepository extends Repository<Ledger, ObjectId>, LedgerRepositoryInsertOperations {

    List<Ledger> findByAccount(ObjectId account);

    List<Ledger> findByTransaction(ObjectId transaction);
}
