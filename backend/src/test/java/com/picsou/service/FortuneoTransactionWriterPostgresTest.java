package com.picsou.service;

import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.Transaction;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Proves the Fortuneo delete/insert replacement rolls back on real PostgreSQL. */
@DataJpaTest
@Import(FortuneoTransactionWriter.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@EnabledIf("dockerAvailable")
class FortuneoTransactionWriterPostgresTest {
    static {
        // Keep the CI/IDE floor pinned at 1.44. The override exists only so contributors
        // with an older local Docker Engine can still run this isolated test explicitly.
        System.setProperty(
            "api.version",
            System.getProperty("picsou.docker.api.version", "1.44")
        );
    }

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired FortuneoTransactionWriter transactionWriter;
    @Autowired TransactionRepository transactionRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired FamilyMemberRepository memberRepository;
    @Autowired PlatformTransactionManager transactionManager;

    private Long accountId;
    private LocalDate cutoff;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    static boolean dockerAvailable() {
        boolean available = DockerClientFactory.instance().isDockerAvailable();
        if (!available && Boolean.parseBoolean(System.getenv("PICSOU_REQUIRE_DOCKER_TESTS"))) {
            throw new IllegalStateException(
                "PICSOU_REQUIRE_DOCKER_TESTS is set but no Docker environment was found"
            );
        }
        return available;
    }

    @BeforeEach
    void seedAccountAndHistory() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        cutoff = LocalDate.now().minusDays(90);
        accountId = tx.execute(status -> {
            transactionRepository.deleteAll();
            accountRepository.deleteAll();
            memberRepository.deleteAll();

            FamilyMember member = memberRepository.save(
                FamilyMember.builder().displayName("Rollback owner").build()
            );
            Account account = accountRepository.save(Account.builder()
                .member(member)
                .name("Fortuneo checking")
                .type(AccountType.CHECKING)
                .currency("EUR")
                .currentBalance(new BigDecimal("1000"))
                .cashBalance(new BigDecimal("1000"))
                .provider("Fortuneo")
                .externalAccountId("ft_rollback")
                .isManual(false)
                .color("#f59e0b")
                .build());
            transactionRepository.saveAndFlush(Transaction.builder()
                .account(account)
                .date(cutoff.plusDays(1))
                .description("previous synchronized row")
                .amount(new BigDecimal("10"))
                .nativeCurrency("EUR")
                .isManual(false)
                .build());
            return account.getId();
        });
        assertThat(accountId).isNotNull();
    }

    @Test
    void insertionFailureAfterDeletion_rollsBackTheCompleteReplacement() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Account account = accountRepository.findById(accountId).orElseThrow();
        Transaction numericOverflow = Transaction.builder()
            .account(account)
            .date(cutoff.plusDays(2))
            .description("invalid replacement")
            .amount(new BigDecimal("123456789012345678901234567890"))
            .nativeCurrency("EUR")
            .isManual(false)
            .build();

        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
            transactionWriter.replaceRecentTransactions(
                accountId,
                cutoff,
                List.of(numericOverflow)
            )
        )).isInstanceOf(RuntimeException.class);

        assertThat(transactionRepository.findByAccountIdOrderByDateDesc(accountId))
            .singleElement()
            .satisfies(previous -> {
                assertThat(previous.getDescription()).isEqualTo("previous synchronized row");
                assertThat(previous.getAmount()).isEqualByComparingTo("10");
            });
    }

    @Test
    void reconciliationFailure_rollsBackDeletesAndInsertsTogether() {
        // The full-history import deletes the rows the provider no longer reports before
        // reinserting the ones it does. A failure anywhere in between must leave the account
        // exactly as it was: a partial response can never cost the user real history.
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Account account = accountRepository.findById(accountId).orElseThrow();
        Transaction obsolete = transactionRepository.findByAccountIdOrderByDateDesc(accountId).getFirst();
        Transaction numericOverflow = Transaction.builder()
            .account(account)
            .externalId("tx-1")
            .date(cutoff.plusDays(2))
            .description("invalid replacement")
            .amount(new BigDecimal("123456789012345678901234567890"))
            .nativeCurrency("EUR")
            .isManual(false)
            .build();

        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
            transactionWriter.reconcileHistory(List.of(obsolete), List.of(numericOverflow))
        )).isInstanceOf(RuntimeException.class);

        assertThat(transactionRepository.findByAccountIdOrderByDateDesc(accountId))
            .singleElement()
            .satisfies(previous ->
                assertThat(previous.getDescription()).isEqualTo("previous synchronized row"));
    }

    @Test
    void reconciliation_replacesTheWindowEraRowWithItsIdentifiedCounterpart() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Account account = accountRepository.findById(accountId).orElseThrow();
        Transaction windowEraRow = transactionRepository.findByAccountIdOrderByDateDesc(accountId).getFirst();
        Transaction identified = Transaction.builder()
            .account(account)
            .externalId("tx-1")
            .date(windowEraRow.getDate())
            .description("same row, now identified")
            .amount(new BigDecimal("10"))
            .nativeCurrency("EUR")
            .isManual(false)
            .build();

        tx.executeWithoutResult(status ->
            transactionWriter.reconcileHistory(List.of(windowEraRow), List.of(identified)));

        assertThat(transactionRepository.findByAccountIdOrderByDateDesc(accountId))
            .singleElement()
            .satisfies(row -> {
                assertThat(row.getExternalId()).isEqualTo("tx-1");
                assertThat(row.getDescription()).isEqualTo("same row, now identified");
            });
    }

    @Test
    void theSameExternalIdCannotBeStoredTwiceOnOneAccount() {
        // Proves the partial unique index from V67 is really in the schema: it is the last
        // line of defence against a re-sync appending a second copy of every transaction.
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Account account = accountRepository.findById(accountId).orElseThrow();

        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
            transactionWriter.reconcileHistory(List.of(), List.of(
                duplicateOf(account, "tx-dup", "first copy"),
                duplicateOf(account, "tx-dup", "second copy")
            ))
        )).isInstanceOf(RuntimeException.class);
    }

    private Transaction duplicateOf(Account account, String externalId, String description) {
        return Transaction.builder()
            .account(account)
            .externalId(externalId)
            .date(cutoff.plusDays(3))
            .description(description)
            .amount(BigDecimal.ONE)
            .nativeCurrency("EUR")
            .isManual(false)
            .build();
    }
}
