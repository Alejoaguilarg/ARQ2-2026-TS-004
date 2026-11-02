package co.com.finova.wallet.model.ledger;

import co.com.finova.wallet.model.common.ContractViolationException;
import co.com.finova.wallet.model.money.Money;
import co.com.finova.wallet.model.wallet.Wallet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.UUID;

import static co.com.finova.wallet.model.Fixtures.NOW;
import static co.com.finova.wallet.model.Fixtures.money;
import static co.com.finova.wallet.model.Fixtures.wallet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerEntryTest {

    private final UUID entryId = UUID.randomUUID();
    private final UUID walletId = UUID.randomUUID();
    private final UUID transferId = UUID.randomUUID();

    @Test
    void openingHasNoTransferAndBalanceAfterEqualsAmount_s2() {
        LedgerEntry opening = LedgerEntry.opening(entryId, walletId, money("500000.00"), NOW);

        assertThat(opening.entryId()).isEqualTo(entryId);
        assertThat(opening.walletId()).isEqualTo(walletId);
        assertThat(opening.transferId()).isNull();
        assertThat(opening.type()).isEqualTo(LedgerEntryType.OPENING);
        assertThat(opening.amount()).isEqualTo(money("500000.00"));
        assertThat(opening.balanceAfter()).isEqualTo(money("500000.00"));
        assertThat(opening.createdAt()).isEqualTo(NOW);
    }

    @Test
    void debitTakesWalletIdAndBalanceAfterFromTheUpdatedWallet_s4_7() {
        Wallet after = wallet("350000.00", 1);

        LedgerEntry debit = LedgerEntry.debit(entryId, transferId, money("150000.00"), after, NOW);

        assertThat(debit.type()).isEqualTo(LedgerEntryType.DEBIT);
        assertThat(debit.walletId()).isEqualTo(after.walletId());
        assertThat(debit.transferId()).isEqualTo(transferId);
        assertThat(debit.amount()).isEqualTo(money("150000.00"));
        assertThat(debit.balanceAfter()).isEqualTo(money("350000.00"));
        assertThat(debit.entryId()).isEqualTo(entryId);
        assertThat(debit.createdAt()).isEqualTo(NOW);
    }

    @Test
    void creditAndReversalUseTheirOwnTypes_s4_7() {
        Wallet after = wallet("250000.00", 4);

        LedgerEntry credit = LedgerEntry.credit(entryId, transferId, money("150000.00"), after, NOW);
        LedgerEntry reversal = LedgerEntry.reversal(entryId, transferId, money("150000.00"), after, NOW);

        assertThat(credit.type()).isEqualTo(LedgerEntryType.CREDIT);
        assertThat(credit.balanceAfter()).isEqualTo(money("250000.00"));
        assertThat(credit.walletId()).isEqualTo(after.walletId());
        assertThat(reversal.type()).isEqualTo(LedgerEntryType.REVERSAL);
        assertThat(reversal.transferId()).isEqualTo(transferId);
        assertThat(reversal.walletId()).isEqualTo(after.walletId());
    }

    @Test
    void openingRejectsTransferId_s2() {
        assertThatThrownBy(() -> new LedgerEntry(entryId, walletId, transferId, LedgerEntryType.OPENING,
                money("1.00"), money("1.00"), NOW))
                .isInstanceOf(ContractViolationException.class)
                .hasMessageContaining("transferId");
    }

    @ParameterizedTest
    @EnumSource(value = LedgerEntryType.class, names = {"DEBIT", "CREDIT", "REVERSAL"})
    void transferEntriesRequireTransferId_s2(LedgerEntryType type) {
        assertThatThrownBy(() -> new LedgerEntry(entryId, walletId, null, type, money("1.00"), money("1.00"), NOW))
                .isInstanceOf(ContractViolationException.class)
                .hasMessageContaining("transferId");
    }

    @Test
    void factoriesRequireTransferIdAndWallet_s2() {
        Wallet after = wallet("1.00", 1);

        assertThatThrownBy(() -> LedgerEntry.debit(entryId, null, money("1.00"), after, NOW))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("transferId");
        assertThatThrownBy(() -> LedgerEntry.credit(entryId, transferId, money("1.00"), null, NOW))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("walletAfter");
    }

    @Test
    void rejectsZeroAmount_s1_5() {
        assertThatThrownBy(() -> LedgerEntry.opening(entryId, walletId, Money.ZERO, NOW))
                .isInstanceOf(ContractViolationException.class)
                .hasMessageContaining("amount must be > 0");
    }

    @Test
    void rejectsMissingRequiredFields_s2() {
        Money one = money("1.00");

        assertThatThrownBy(() -> new LedgerEntry(null, walletId, transferId, LedgerEntryType.DEBIT, one, one, NOW))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("entryId");
        assertThatThrownBy(() -> new LedgerEntry(entryId, null, transferId, LedgerEntryType.DEBIT, one, one, NOW))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("walletId");
        assertThatThrownBy(() -> new LedgerEntry(entryId, walletId, transferId, null, one, one, NOW))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("type");
        assertThatThrownBy(() -> new LedgerEntry(entryId, walletId, transferId, LedgerEntryType.DEBIT, null, one, NOW))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("amount");
        assertThatThrownBy(() -> new LedgerEntry(entryId, walletId, transferId, LedgerEntryType.DEBIT, one, null, NOW))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("balanceAfter");
        assertThatThrownBy(() -> new LedgerEntry(entryId, walletId, transferId, LedgerEntryType.DEBIT, one, one, null))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("createdAt");
    }

    @Test
    void acceptsZeroBalanceAfter_s2() {
        Wallet emptied = wallet("0.00", 2);

        assertThat(LedgerEntry.debit(entryId, transferId, money("80000.00"), emptied, NOW).balanceAfter())
                .isEqualTo(Money.ZERO);
    }
}
