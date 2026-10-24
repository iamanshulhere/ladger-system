package com.ledger.transaction;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ledger.controller.dto.TransferRequest;
import com.ledger.repository.AccountRepository;
import com.ledger.repository.TransactionRepository;
import java.math.BigDecimal;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock
    private TransactionalTransactionOperations transactionOperations;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @InjectMocks
    private TransactionService transactionService;

    @Test
    void invalidObjectIdIsRejectedBeforeDatabaseOperations() {
        ObjectId callerId = new ObjectId();
        TransferRequest request = new TransferRequest(
                "not-an-object-id", new ObjectId().toHexString(), new BigDecimal("1.00"), "bad-id");

        assertThatThrownBy(() -> transactionService.transfer(callerId, request))
                .isInstanceOf(TransactionException.class)
                .extracting(exception -> ((TransactionException) exception).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verifyNoInteractions(transactionOperations, accountRepository, transactionRepository);
    }

    @Test
    void amountThatUnderflowsBsonDoubleIsRejectedBeforeDatabaseOperations() {
        ObjectId callerId = new ObjectId();
        TransferRequest request = new TransferRequest(
                new ObjectId().toHexString(), new ObjectId().toHexString(),
                new BigDecimal("1E-400"), "underflow-amount");

        assertThatThrownBy(() -> transactionService.transfer(callerId, request))
                .isInstanceOf(TransactionException.class)
                .hasMessage("Transaction amount cannot be represented as a positive BSON double");

        verifyNoInteractions(transactionOperations, accountRepository, transactionRepository);
    }
}
