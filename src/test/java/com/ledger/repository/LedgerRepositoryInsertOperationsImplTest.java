package com.ledger.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ledger.model.Ledger;
import com.ledger.model.LedgerEntryType;
import java.math.BigDecimal;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

@ExtendWith(MockitoExtension.class)
class LedgerRepositoryInsertOperationsImplTest {

    @Mock
    private MongoTemplate mongoTemplate;

    @InjectMocks
    private LedgerRepositoryInsertOperationsImpl repository;

    @Test
    void insertsLedgerEntriesInsteadOfExposingUpdateOrDeleteOperations() {
        Ledger ledger = new Ledger(new ObjectId(), new BigDecimal("12.50"), new ObjectId(), LedgerEntryType.CREDIT);
        when(mongoTemplate.insert(ledger)).thenReturn(ledger);

        assertThat(repository.insert(ledger)).isSameAs(ledger);
        verify(mongoTemplate).insert(ledger);
    }
}
