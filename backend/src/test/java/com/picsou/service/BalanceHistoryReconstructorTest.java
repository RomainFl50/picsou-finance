package com.picsou.service;

import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.BalanceSnapshot;
import com.picsou.model.PriceSnapshot;
import com.picsou.model.Transaction;
import com.picsou.model.TransactionType;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.PriceSnapshotRepository;
import com.picsou.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A sync writes one snapshot, dated today, so an account's chart starts the day it was connected
 * however much history the provider returned. These cover what the imported ledger can and cannot
 * establish about the days before it.
 */
@ExtendWith(MockitoExtension.class)
class BalanceHistoryReconstructorTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 25);

    @Mock TransactionRepository transactionRepository;
    @Mock BalanceSnapshotRepository snapshotRepository;
    @Mock PriceSnapshotRepository priceSnapshotRepository;
    @Captor ArgumentCaptor<List<BalanceSnapshot>> snapshotsCaptor;

    BalanceHistoryReconstructor reconstructor;

    @BeforeEach
    void setUp() {
        reconstructor = new BalanceHistoryReconstructor(
            transactionRepository, snapshotRepository, priceSnapshotRepository);
        lenient().when(snapshotRepository.findByAccountIdAndDate(anyLong(), any()))
            .thenReturn(Optional.empty());
    }

    private Account account(AccountType type) {
        return Account.builder().id(20L).type(type).name("Test").currency("EUR").build();
    }

    private Transaction cashRow(LocalDate date, String amount) {
        return Transaction.builder().date(date).amount(new BigDecimal(amount)).build();
    }

    private Transaction trade(LocalDate date, TransactionType type, String ticker, String qty) {
        return Transaction.builder()
            .date(date).txType(type).ticker(ticker).quantity(new BigDecimal(qty))
            .amount(BigDecimal.ONE).build();
    }

    private Transaction trade(
        LocalDate date, TransactionType type, String ticker, String qty, String unitPrice
    ) {
        return Transaction.builder()
            .date(date).txType(type).ticker(ticker).quantity(new BigDecimal(qty))
            .pricePerUnit(new BigDecimal(unitPrice)).amount(BigDecimal.ONE).build();
    }

    private void priceIs(String ticker, String priceEur) {
        when(priceSnapshotRepository.findByTickerAndDate(eq(ticker), any()))
            .thenReturn(Optional.of(PriceSnapshot.builder()
                .ticker(ticker).priceEur(new BigDecimal(priceEur)).build()));
    }

    @Test
    void walksACashLedgerBackwardsFromTheBalanceKnownToday() {
        // Balance at the end of a day is today's balance minus everything booked after it.
        when(transactionRepository.findByAccountIdAndIsManualFalse(20L)).thenReturn(List.of(
            cashRow(TODAY.minusDays(1), "-50"),
            cashRow(TODAY.minusDays(3), "200")
        ));

        int created = reconstructor.reconstruct(account(AccountType.CHECKING), new BigDecimal("1000"), TODAY);

        assertThat(created).isEqualTo(2);
        verify(snapshotRepository).saveAll(snapshotsCaptor.capture());
        // invested_amount is NOT NULL: a cash account has no cost basis distinct from its
        // balance, the same convention DashboardService applies to a holdings-free account.
        assertThat(snapshotsCaptor.getValue()).allSatisfy(s ->
            assertThat(s.getInvestedAmount()).isEqualByComparingTo(s.getBalance()));
        assertThat(snapshotsCaptor.getValue())
            .extracting(s -> s.getDate() + "=" + s.getBalance().stripTrailingZeros().toPlainString())
            .containsExactly(
                TODAY.minusDays(1) + "=1000",   // after the -50 was booked
                TODAY.minusDays(3) + "=1050");  // before it, after the +200
    }

    @Test
    void neverOverwritesAnExistingSnapshot() {
        // A same-date row may be a figure the user entered by hand, or one an earlier sync
        // observed directly. Either is better evidence than a reconstruction.
        when(transactionRepository.findByAccountIdAndIsManualFalse(20L))
            .thenReturn(List.of(cashRow(TODAY.minusDays(1), "-50")));
        when(snapshotRepository.findByAccountIdAndDate(20L, TODAY.minusDays(1)))
            .thenReturn(Optional.of(BalanceSnapshot.builder().build()));

        int created = reconstructor.reconstruct(account(AccountType.CHECKING), new BigDecimal("1000"), TODAY);

        assertThat(created).isZero();
        verify(snapshotRepository, never()).saveAll(any());
    }

    @Test
    void valuesSecuritiesFromQuantitiesHeldAndPricesOfTheDay() {
        when(transactionRepository.findByAccountIdAndIsManualFalse(20L)).thenReturn(List.of(
            trade(TODAY.minusDays(10), TransactionType.BUY, "TTE.PA", "10")
        ));
        when(priceSnapshotRepository.findByTickerAndDate(eq("TTE.PA"), any()))
            .thenReturn(Optional.of(PriceSnapshot.builder()
                .ticker("TTE.PA").priceEur(new BigDecimal("50")).build()));

        int created = reconstructor.reconstruct(account(AccountType.PEA), new BigDecimal("500"), TODAY);

        // One point per day from the first trade up to (but not including) today, which the
        // sync itself owns: between two trades the holdings hold still, the price does not.
        assertThat(created).isEqualTo(10);
        verify(snapshotRepository).saveAll(snapshotsCaptor.capture());
        assertThat(snapshotsCaptor.getValue()).allSatisfy(s ->
            assertThat(s.getBalance()).isEqualByComparingTo("500"));
        assertThat(snapshotsCaptor.getValue()).first()
            .satisfies(s -> assertThat(s.getDate()).isEqualTo(TODAY.minusDays(10)));
        assertThat(snapshotsCaptor.getValue()).last()
            .satisfies(s -> assertThat(s.getDate()).isEqualTo(TODAY.minusDays(1)));
    }

    @Test
    void carriesTheCostBasisOfTheTradesThatBuiltThePosition() {
        // invested_amount is the whole of what the P&L curve subtracts. Writing the day's own
        // value there reports every reconstructed day as a gain of exactly zero and leaves the
        // account's entire gain to appear as one vertical step on today's live point.
        when(transactionRepository.findByAccountIdAndIsManualFalse(20L)).thenReturn(List.of(
            trade(TODAY.minusDays(10), TransactionType.BUY, "TTE.PA", "10", "40")
        ));
        priceIs("TTE.PA", "50");

        int created = reconstructor.reconstruct(account(AccountType.PEA), new BigDecimal("500"), TODAY);

        assertThat(created).isEqualTo(10);
        verify(snapshotRepository).saveAll(snapshotsCaptor.capture());
        assertThat(snapshotsCaptor.getValue()).allSatisfy(s -> {
            assertThat(s.getBalance()).isEqualByComparingTo("500");
            assertThat(s.getInvestedAmount()).isEqualByComparingTo("400");
        });
    }

    @Test
    void countsTheFeesOfAPurchaseIntoWhatItCost() {
        // Fees are part of the price paid, so part of what has to be earned back before the
        // line shows a gain.
        Transaction buy = trade(TODAY.minusDays(3), TransactionType.BUY, "TTE.PA", "10", "40");
        buy.setFees(new BigDecimal("-12"));
        when(transactionRepository.findByAccountIdAndIsManualFalse(20L)).thenReturn(List.of(buy));
        priceIs("TTE.PA", "50");

        reconstructor.reconstruct(account(AccountType.PEA), new BigDecimal("500"), TODAY);

        verify(snapshotRepository).saveAll(snapshotsCaptor.capture());
        assertThat(snapshotsCaptor.getValue()).allSatisfy(s ->
            assertThat(s.getInvestedAmount()).isEqualByComparingTo("412"));
    }

    @Test
    void aSaleRemovesCostAtTheAverageRatherThanAtItsProceeds() {
        // Selling half a line leaves half its cost behind. Removing the proceeds instead would
        // let a profitable sale rewrite the history of the shares that were kept.
        when(transactionRepository.findByAccountIdAndIsManualFalse(20L)).thenReturn(List.of(
            trade(TODAY.minusDays(6), TransactionType.BUY, "TTE.PA", "10", "40"),
            trade(TODAY.minusDays(3), TransactionType.SELL, "TTE.PA", "6", "70")
        ));
        priceIs("TTE.PA", "50");

        reconstructor.reconstruct(account(AccountType.PEA), new BigDecimal("200"), TODAY);

        verify(snapshotRepository).saveAll(snapshotsCaptor.capture());
        assertThat(snapshotsCaptor.getValue())
            .filteredOn(s -> !s.getDate().isBefore(TODAY.minusDays(3)))
            .allSatisfy(s -> {
                assertThat(s.getBalance()).isEqualByComparingTo("200");   // 4 shares at 50
                assertThat(s.getInvestedAmount()).isEqualByComparingTo("160"); // 4 at cost 40
            });
    }

    @Test
    void aPurchaseWithNoExecutionPriceClaimsNoGainRatherThanAWrongOne() {
        // The cost of that line is unknowable, and a zero would read as "this was free" --
        // printing the whole position as gain. The day is still drawn, at a basis equal to its
        // value, which is the same convention a holdings-free account gets.
        when(transactionRepository.findByAccountIdAndIsManualFalse(20L)).thenReturn(List.of(
            trade(TODAY.minusDays(4), TransactionType.BUY, "TTE.PA", "10", "40"),
            trade(TODAY.minusDays(4), TransactionType.BUY, "AI.PA", "2")
        ));
        priceIs("TTE.PA", "50");
        priceIs("AI.PA", "100");

        reconstructor.reconstruct(account(AccountType.PEA), new BigDecimal("700"), TODAY);

        verify(snapshotRepository).saveAll(snapshotsCaptor.capture());
        assertThat(snapshotsCaptor.getValue()).allSatisfy(s ->
            assertThat(s.getInvestedAmount()).isEqualByComparingTo(s.getBalance()));
    }

    @Test
    void aFreshPositionCanEstablishItsBasisAfterAnUnknownPositionWasClosed() {
        when(transactionRepository.findByAccountIdAndIsManualFalse(20L)).thenReturn(List.of(
            trade(TODAY.minusDays(8), TransactionType.BUY, "TTE.PA", "10"),
            trade(TODAY.minusDays(6), TransactionType.SELL, "TTE.PA", "10", "50"),
            trade(TODAY.minusDays(4), TransactionType.BUY, "TTE.PA", "2", "30")
        ));
        priceIs("TTE.PA", "40");

        reconstructor.reconstruct(account(AccountType.PEA), new BigDecimal("80"), TODAY);

        verify(snapshotRepository).saveAll(snapshotsCaptor.capture());
        assertThat(snapshotsCaptor.getValue())
            .filteredOn(s -> !s.getDate().isBefore(TODAY.minusDays(4)))
            .allSatisfy(s -> assertThat(s.getInvestedAmount()).isEqualByComparingTo("60"));
    }

    @Test
    void skipsADayWhoseInstrumentHasNoPrice() {
        // A curve with a gap is honest; a curve carrying a stale or invented point is not.
        when(transactionRepository.findByAccountIdAndIsManualFalse(20L)).thenReturn(List.of(
            trade(TODAY.minusDays(10), TransactionType.BUY, "TTE.PA", "10")
        ));
        when(priceSnapshotRepository.findByTickerAndDate(eq("TTE.PA"), any()))
            .thenReturn(Optional.empty());

        int created = reconstructor.reconstruct(account(AccountType.PEA), new BigDecimal("500"), TODAY);

        assertThat(created).isZero();
        verify(snapshotRepository, never()).saveAll(any());
    }

    @Test
    void anAccountWithNoLedgerIsLeftAlone() {
        when(transactionRepository.findByAccountIdAndIsManualFalse(20L)).thenReturn(List.of());

        assertThat(reconstructor.reconstruct(account(AccountType.CHECKING), BigDecimal.TEN, TODAY))
            .isZero();
        verify(snapshotRepository, never()).saveAll(any());
    }
}
