package com.ledger.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledger.controller.dto.InitialFundsRequest;
import com.ledger.controller.dto.TransferRequest;
import com.ledger.model.Account;
import com.ledger.model.Ledger;
import com.ledger.model.LedgerEntryType;
import com.ledger.model.Transaction;
import com.ledger.model.TransactionStatus;
import com.ledger.repository.AccountRepository;
import com.ledger.repository.LedgerRepository;
import com.ledger.repository.TransactionRepository;
import com.mongodb.ConnectionString;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.ValidationOptions;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named = "LEDGER_MONGO_REPLICA_SET_URI", matches = ".+")
@SpringBootTest(properties = {
        "jwt.secret=transaction-integration-secret-at-least-32-bytes",
        "spring.data.mongodb.auto-index-creation=true",
        "debug=false",
        "logging.level.root=WARN"
})
class TransactionReplicaSetIntegrationTest {

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", TransactionReplicaSetIntegrationTest::isolatedMongoUri);
    }

    private static String isolatedMongoUri() {
        String uri = System.getenv("LEDGER_MONGO_REPLICA_SET_URI");
        String database = new ConnectionString(uri).getDatabase();
        String normalizedName = database == null ? "" : database.toLowerCase(Locale.ROOT);
        if (!(normalizedName.endsWith("-test") || normalizedName.endsWith("_test")
                || normalizedName.endsWith("-it") || normalizedName.endsWith("_it"))) {
            throw new IllegalStateException(
                    "LEDGER_MONGO_REPLICA_SET_URI must select a disposable database ending in -test or -it");
        }
        return uri;
    }

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private LedgerRepository ledgerRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void clearIsolatedDatabase() {
        mongoTemplate.dropCollection(Ledger.class);
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        ensureLedgerIndexes();
    }

    @Test
    void successfulTransferCommitsCompletedTransactionAndBothEntries() {
        ObjectId ownerId = new ObjectId();
        Account from = saveAccount(ownerId);
        Account to = saveAccount(new ObjectId());
        seedCredit(from.getId(), "100.00");

        TransactionOutcome outcome = transactionService.transfer(ownerId,
                transfer(from, to, "25.00", "success-" + UUID.randomUUID()));

        assertThat(outcome.status().value()).isEqualTo(201);
        assertThat(outcome.transaction().getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        List<Ledger> entries = ledgerRepository.findByTransaction(outcome.transaction().getId());
        assertThat(entries).hasSize(2);
        assertThat(entries).extracting(Ledger::getType)
                .containsExactlyInAnyOrder(LedgerEntryType.DEBIT, LedgerEntryType.CREDIT);
        assertThat(balance(from.getId())).isEqualByComparingTo("75.00");
        assertThat(balance(to.getId())).isEqualByComparingTo("25.00");
        assertThat(accountRepository.findById(from.getId()).orElseThrow().getVersion()).isEqualTo(1);
        assertThat(accountRepository.findById(to.getId()).orElseThrow().getVersion()).isEqualTo(1);
    }

    @Test
    void repeatedInitialFundsRequestReturnsExistingResultWithoutCreatingMoreFunds() {
        ObjectId systemUserId = new ObjectId();
        Account system = saveAccount(systemUserId);
        Account target = saveAccount(new ObjectId());
        InitialFundsRequest request = new InitialFundsRequest(target.getId().toHexString(),
                new BigDecimal("40.00"), "initial-" + UUID.randomUUID());

        TransactionOutcome first = transactionService.initialFunds(systemUserId, request);
        TransactionOutcome retry = transactionService.initialFunds(systemUserId, request);

        assertThat(first.status().value()).isEqualTo(201);
        assertThat(retry.status().value()).isEqualTo(200);
        assertThat(retry.message()).isEqualTo("Initial funds transaction already processed");
        assertThat(ledgerRepository.findByAccount(system.getId())).hasSize(1);
        assertThat(ledgerRepository.findByAccount(target.getId())).hasSize(1);
        assertThat(balance(target.getId())).isEqualByComparingTo("40.00");
    }

    @Test
    void concurrentSameIdempotencyKeyCreatesOnlyOneTransactionAndPairOfEntries() throws Exception {
        ObjectId ownerId = new ObjectId();
        Account from = saveAccount(ownerId);
        Account to = saveAccount(new ObjectId());
        seedCredit(from.getId(), "1000.00");
        TransferRequest request = transfer(from, to, "100.00", "concurrent-" + UUID.randomUUID());

        List<Object> results = runTogether(
                () -> transactionService.transfer(ownerId, request),
                () -> transactionService.transfer(ownerId, request));

        List<TransactionOutcome> outcomes = results.stream()
                .filter(TransactionOutcome.class::isInstance)
                .map(TransactionOutcome.class::cast)
                .toList();
        assertThat(outcomes).hasSize(2);
        assertThat(outcomes).extracting(outcome -> outcome.status().value())
                .containsExactlyInAnyOrder(201, 200);
        Transaction stored = transactionRepository.findByIdempotencyKey(request.idempotencyKey()).orElseThrow();
        assertThat(ledgerRepository.findByTransaction(stored.getId())).hasSize(2);
        assertThat(balance(from.getId())).isEqualByComparingTo("900.00");
    }

    @Test
    void concurrentTransfersCannotOverdrawOneSourceAccount() throws Exception {
        ObjectId ownerId = new ObjectId();
        Account from = saveAccount(ownerId);
        Account destinationOne = saveAccount(new ObjectId());
        Account destinationTwo = saveAccount(new ObjectId());
        seedCredit(from.getId(), "100.00");

        List<Object> results = runTogether(
                () -> transactionService.transfer(ownerId,
                        transfer(from, destinationOne, "80.00", "overdraft-a-" + UUID.randomUUID())),
                () -> transactionService.transfer(ownerId,
                        transfer(from, destinationTwo, "80.00", "overdraft-b-" + UUID.randomUUID())));

        long committed = results.stream()
                .filter(TransactionOutcome.class::isInstance)
                .map(TransactionOutcome.class::cast)
                .filter(outcome -> outcome.status().value() == 201)
                .count();
        assertThat(committed).isEqualTo(1);
        assertThat(balance(from.getId())).isEqualByComparingTo("20.00");
        List<Transaction> completed = transactionRepository.findAll().stream()
                .filter(transaction -> transaction.getStatus() == TransactionStatus.COMPLETED)
                .filter(transaction -> !transaction.getIdempotencyKey().startsWith("seed-"))
                .toList();
        assertThat(completed).hasSize(1);
        assertThat(ledgerRepository.findByTransaction(completed.get(0).getId())).hasSize(2);
    }

    @Test
    void failureOnSecondLedgerInsertRollsBackPendingTransactionDebitAndAccountLocks() {
        installDebitOnlyLedgerValidator();
        ObjectId systemUserId = new ObjectId();
        Account system = saveAccount(systemUserId);
        Account target = saveAccount(new ObjectId());
        InitialFundsRequest request = new InitialFundsRequest(target.getId().toHexString(),
                new BigDecimal("15.00"), "rollback-" + UUID.randomUUID());

        assertThatThrownBy(() -> transactionService.initialFunds(systemUserId, request))
                .isInstanceOf(RuntimeException.class);

        assertThat(transactionRepository.findByIdempotencyKey(request.idempotencyKey())).isEmpty();
        assertThat(ledgerRepository.findByAccount(system.getId())).isEmpty();
        assertThat(ledgerRepository.findByAccount(target.getId())).isEmpty();
        assertThat(accountRepository.findById(system.getId()).orElseThrow().getVersion()).isZero();
        assertThat(accountRepository.findById(target.getId()).orElseThrow().getVersion()).isZero();
    }

    private Account saveAccount(ObjectId ownerId) {
        return accountRepository.save(new Account(ownerId));
    }

    private void seedCredit(ObjectId accountId, String amount) {
        Transaction seed = new Transaction();
        seed.setFromAccount(new ObjectId());
        seed.setToAccount(accountId);
        seed.setAmount(new BigDecimal(amount));
        seed.setIdempotencyKey("seed-" + UUID.randomUUID());
        seed.setStatus(TransactionStatus.COMPLETED);
        Transaction saved = transactionRepository.save(seed);
        ledgerRepository.insert(new Ledger(accountId, new BigDecimal(amount),
                saved.getId(), LedgerEntryType.CREDIT));
    }

    private TransferRequest transfer(Account from, Account to, String amount, String key) {
        return new TransferRequest(from.getId().toHexString(), to.getId().toHexString(),
                new BigDecimal(amount), key);
    }

    private BigDecimal balance(ObjectId accountId) {
        return ledgerRepository.findByAccount(accountId).stream()
                .map(entry -> entry.getType() == LedgerEntryType.CREDIT
                        ? entry.getAmount() : entry.getAmount().negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<Object> runTogether(java.util.concurrent.Callable<TransactionOutcome> first,
            java.util.concurrent.Callable<TransactionOutcome> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Object> firstFuture = executor.submit(() -> invokeAtBarrier(first, ready, start));
            Future<Object> secondFuture = executor.submit(() -> invokeAtBarrier(second, ready, start));
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(firstFuture.get(), secondFuture.get());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private Object invokeAtBarrier(java.util.concurrent.Callable<TransactionOutcome> action,
            CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            start.await();
            return action.call();
        } catch (ExecutionException exception) {
            return exception.getCause();
        } catch (Exception exception) {
            return exception;
        }
    }

    private void ensureLedgerIndexes() {
        mongoTemplate.indexOps(Ledger.class).ensureIndex(new Index().on("account", Sort.Direction.ASC).named("account_1"));
        mongoTemplate.indexOps(Ledger.class).ensureIndex(
                new Index().on("transaction", Sort.Direction.ASC).named("transaction_1"));
    }

    private void installDebitOnlyLedgerValidator() {
        mongoTemplate.dropCollection(Ledger.class);
        Document validator = new Document("$jsonSchema", new Document("bsonType", "object")
                .append("required", List.of("type"))
                .append("properties", new Document("type", new Document("bsonType", "string")
                        .append("enum", List.of("DEBIT")))));
        mongoTemplate.getDb().createCollection("ledgers", new CreateCollectionOptions()
                .validationOptions(new ValidationOptions().validator(validator)));
        ensureLedgerIndexes();
    }
}
