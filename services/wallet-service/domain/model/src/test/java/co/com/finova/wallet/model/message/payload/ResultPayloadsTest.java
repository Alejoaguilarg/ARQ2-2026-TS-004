package co.com.finova.wallet.model.message.payload;

import co.com.finova.wallet.model.common.ContractViolationException;
import co.com.finova.wallet.model.message.MessageType;
import co.com.finova.wallet.model.money.Money;
import co.com.finova.wallet.model.wallet.Wallet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static co.com.finova.wallet.model.Fixtures.money;
import static co.com.finova.wallet.model.Fixtures.wallet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResultPayloadsTest {

    private final UUID transferId = UUID.randomUUID();
    private final UUID walletId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final Money amount = money("150000.00");
    private final Wallet after = wallet("350000.00", 5);

    @Test
    void walletDebitedFromUpdatedWallet_s4_4() {
        WalletDebited event = WalletDebited.from(transferId, amount, after);

        assertThat(event.transferId()).isEqualTo(transferId);
        assertThat(event.walletId()).isEqualTo(after.walletId());
        assertThat(event.userId()).isEqualTo(after.userId());
        assertThat(event.amount()).isEqualTo(amount);
        assertThat(event.version()).isEqualTo(5);
        assertThat(event.type()).isEqualTo(MessageType.WALLET_DEBITED);
    }

    @Test
    void walletCreditedFromUpdatedWallet_s4_4() {
        WalletCredited event = WalletCredited.from(transferId, amount, after);

        assertThat(event.transferId()).isEqualTo(transferId);
        assertThat(event.walletId()).isEqualTo(after.walletId());
        assertThat(event.userId()).isEqualTo(after.userId());
        assertThat(event.amount()).isEqualTo(amount);
        assertThat(event.version()).isEqualTo(5);
        assertThat(event.type()).isEqualTo(MessageType.WALLET_CREDITED);
    }

    @Test
    void debitReversedFromUpdatedWallet_s4_4() {
        DebitReversed event = DebitReversed.from(transferId, amount, after);

        assertThat(event.transferId()).isEqualTo(transferId);
        assertThat(event.walletId()).isEqualTo(after.walletId());
        assertThat(event.userId()).isEqualTo(after.userId());
        assertThat(event.amount()).isEqualTo(amount);
        assertThat(event.version()).isEqualTo(5);
        assertThat(event.type()).isEqualTo(MessageType.DEBIT_REVERSED);
    }

    @Test
    void balanceEffectEventsRejectInvalidFields_s4_4() {
        assertThatThrownBy(() -> new WalletDebited(transferId, walletId, userId, amount, -1))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("version");
        assertThatThrownBy(() -> new WalletCredited(transferId, walletId, userId, Money.ZERO, 1))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("amount");
        assertThatThrownBy(() -> new DebitReversed(null, walletId, userId, amount, 1))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("transferId");
        assertThatThrownBy(() -> new WalletDebited(transferId, null, userId, amount, 1))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("walletId");
        assertThatThrownBy(() -> new WalletCredited(transferId, walletId, null, amount, 1))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("userId");
        assertThatThrownBy(() -> new DebitReversed(transferId, walletId, userId, amount, -1))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("version");
        assertThatThrownBy(() -> new WalletCredited(transferId, walletId, userId, amount, -1))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("version");
        assertThatThrownBy(() -> new WalletDebited(transferId, walletId, userId, Money.ZERO, 1))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("amount");
        assertThatThrownBy(() -> new DebitReversed(transferId, walletId, userId, Money.ZERO, 1))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("amount");
        assertThatThrownBy(() -> WalletDebited.from(transferId, amount, null))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("walletAfter");
        assertThatThrownBy(() -> WalletCredited.from(transferId, amount, null))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("walletAfter");
        assertThatThrownBy(() -> DebitReversed.from(transferId, amount, null))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("walletAfter");
    }

    @Test
    void balanceEffectEventsAcceptVersionZero_s2() {
        assertThat(new WalletDebited(transferId, walletId, userId, amount, 0).version()).isZero();
        assertThat(new WalletCredited(transferId, walletId, userId, amount, 0).version()).isZero();
        assertThat(new DebitReversed(transferId, walletId, userId, amount, 0).version()).isZero();
    }

    @Test
    void debitRejectedKeepsReason_s4_4() {
        DebitRejected event = new DebitRejected(transferId, userId, DebitRejectionReason.INSUFFICIENT_FUNDS);

        assertThat(event.transferId()).isEqualTo(transferId);
        assertThat(event.userId()).isEqualTo(userId);
        assertThat(event.reason()).isEqualTo(DebitRejectionReason.INSUFFICIENT_FUNDS);
        assertThat(event.type()).isEqualTo(MessageType.DEBIT_REJECTED);
        assertThat(DebitRejectionReason.values()).containsExactly(
                DebitRejectionReason.INSUFFICIENT_FUNDS, DebitRejectionReason.SENDER_WALLET_NOT_FOUND);
    }

    @Test
    void debitRejectedRejectsMissingFields_s4_4() {
        assertThatThrownBy(() -> new DebitRejected(null, userId, DebitRejectionReason.INSUFFICIENT_FUNDS))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("transferId");
        assertThatThrownBy(() -> new DebitRejected(transferId, null, DebitRejectionReason.INSUFFICIENT_FUNDS))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("userId");
        assertThatThrownBy(() -> new DebitRejected(transferId, userId, null))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("reason");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void creditFailedKeepsFields_s4_4(int attempt) {
        CreditFailed event = new CreditFailed(transferId, userId, attempt, CreditFailureReason.TRANSFER_REVERSED);

        assertThat(event.transferId()).isEqualTo(transferId);
        assertThat(event.userId()).isEqualTo(userId);
        assertThat(event.attempt()).isEqualTo(attempt);
        assertThat(event.reason()).isEqualTo(CreditFailureReason.TRANSFER_REVERSED);
        assertThat(event.type()).isEqualTo(MessageType.CREDIT_FAILED);
        assertThat(CreditFailureReason.values()).containsExactly(
                CreditFailureReason.RECIPIENT_WALLET_NOT_FOUND, CreditFailureReason.TRANSFER_REVERSED);
    }

    @Test
    void creditFailedRejectsInvalidFields_s4_4() {
        CreditFailureReason reason = CreditFailureReason.RECIPIENT_WALLET_NOT_FOUND;

        assertThatThrownBy(() -> new CreditFailed(transferId, userId, 0, reason))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("attempt");
        assertThatThrownBy(() -> new CreditFailed(transferId, userId, 4, reason))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("attempt");
        assertThatThrownBy(() -> new CreditFailed(null, userId, 1, reason))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("transferId");
        assertThatThrownBy(() -> new CreditFailed(transferId, null, 1, reason))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("userId");
        assertThatThrownBy(() -> new CreditFailed(transferId, userId, 1, null))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("reason");
    }

    @Test
    void balanceChangedFromWalletAllowsZeroAvailable_s4_4() {
        Wallet emptied = wallet("0.00", 9);

        BalanceChanged event = BalanceChanged.from(emptied);

        assertThat(event.walletId()).isEqualTo(emptied.walletId());
        assertThat(event.userId()).isEqualTo(emptied.userId());
        assertThat(event.available()).isEqualTo(Money.ZERO);
        assertThat(event.version()).isEqualTo(9);
        assertThat(event.type()).isEqualTo(MessageType.BALANCE_CHANGED);
    }

    @Test
    void balanceChangedRejectsInvalidFields_s4_4() {
        assertThatThrownBy(() -> new BalanceChanged(null, userId, amount, 1))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("walletId");
        assertThatThrownBy(() -> new BalanceChanged(walletId, null, amount, 1))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("userId");
        assertThatThrownBy(() -> new BalanceChanged(walletId, userId, null, 1))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("available");
        assertThatThrownBy(() -> new BalanceChanged(walletId, userId, amount, -1))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("version");
        assertThatThrownBy(() -> BalanceChanged.from(null))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("wallet");
        assertThat(new BalanceChanged(walletId, userId, amount, 0).version()).isZero();
    }
}
