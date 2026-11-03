package co.com.finova.wallet.model.message.payload;

import co.com.finova.wallet.model.common.ContractViolationException;
import co.com.finova.wallet.model.common.Currency;
import co.com.finova.wallet.model.message.MessageType;
import co.com.finova.wallet.model.money.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static co.com.finova.wallet.model.Fixtures.money;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandPayloadsTest {

    private final UUID transferId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final Money amount = money("150000.00");

    @Test
    void debitWalletKeepsFieldsAndType_s4_4() {
        DebitWallet command = new DebitWallet(transferId, userId, amount, Currency.COP);

        assertThat(command.transferId()).isEqualTo(transferId);
        assertThat(command.userId()).isEqualTo(userId);
        assertThat(command.amount()).isEqualTo(amount);
        assertThat(command.currency()).isEqualTo(Currency.COP);
        assertThat(command.type()).isEqualTo(MessageType.DEBIT_WALLET);
    }

    @Test
    void debitWalletRejectsZeroAmount_s1_5() {
        assertThatThrownBy(() -> new DebitWallet(transferId, userId, Money.ZERO, Currency.COP))
                .isInstanceOf(ContractViolationException.class)
                .hasMessageContaining("amount must be > 0");
    }

    @Test
    void debitWalletRejectsMissingFields_s4_4() {
        assertThatThrownBy(() -> new DebitWallet(null, userId, amount, Currency.COP))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("transferId");
        assertThatThrownBy(() -> new DebitWallet(transferId, null, amount, Currency.COP))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("userId");
        assertThatThrownBy(() -> new DebitWallet(transferId, userId, null, Currency.COP))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("amount");
        assertThatThrownBy(() -> new DebitWallet(transferId, userId, amount, null))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("currency");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void creditWalletAcceptsAttemptsOneToThree_s4_4(int attempt) {
        CreditWallet command = new CreditWallet(transferId, userId, amount, Currency.COP, attempt);

        assertThat(command.attempt()).isEqualTo(attempt);
        assertThat(command.type()).isEqualTo(MessageType.CREDIT_WALLET);
        assertThat(command.transferId()).isEqualTo(transferId);
        assertThat(command.userId()).isEqualTo(userId);
        assertThat(command.amount()).isEqualTo(amount);
        assertThat(command.currency()).isEqualTo(Currency.COP);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 4, -1})
    void creditWalletRejectsAttemptsOutsideOneToThree_s4_4(int attempt) {
        assertThatThrownBy(() -> new CreditWallet(transferId, userId, amount, Currency.COP, attempt))
                .isInstanceOf(ContractViolationException.class)
                .hasMessageContaining("attempt");
    }

    @Test
    void creditWalletRejectsMissingFieldsAndZeroAmount_s4_4() {
        assertThatThrownBy(() -> new CreditWallet(null, userId, amount, Currency.COP, 1))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("transferId");
        assertThatThrownBy(() -> new CreditWallet(transferId, null, amount, Currency.COP, 1))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("userId");
        assertThatThrownBy(() -> new CreditWallet(transferId, userId, Money.ZERO, Currency.COP, 1))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("amount");
        assertThatThrownBy(() -> new CreditWallet(transferId, userId, amount, null, 1))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("currency");
    }

    @Test
    void reverseDebitKeepsFieldsAndType_s4_4() {
        ReverseDebit command = new ReverseDebit(transferId, userId, amount, Currency.COP, ReverseReason.CREDIT_TIMEOUT);

        assertThat(command.reason()).isEqualTo(ReverseReason.CREDIT_TIMEOUT);
        assertThat(command.type()).isEqualTo(MessageType.REVERSE_DEBIT);
        assertThat(command.transferId()).isEqualTo(transferId);
        assertThat(command.userId()).isEqualTo(userId);
        assertThat(command.amount()).isEqualTo(amount);
        assertThat(command.currency()).isEqualTo(Currency.COP);
    }

    @Test
    void reverseReasonsAreTheTwoOfS4_4() {
        assertThat(ReverseReason.values())
                .containsExactly(ReverseReason.RECIPIENT_WALLET_NOT_FOUND, ReverseReason.CREDIT_TIMEOUT);
    }

    @Test
    void reverseDebitRejectsMissingFieldsAndZeroAmount_s4_4() {
        ReverseReason reason = ReverseReason.RECIPIENT_WALLET_NOT_FOUND;

        assertThatThrownBy(() -> new ReverseDebit(null, userId, amount, Currency.COP, reason))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("transferId");
        assertThatThrownBy(() -> new ReverseDebit(transferId, null, amount, Currency.COP, reason))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("userId");
        assertThatThrownBy(() -> new ReverseDebit(transferId, userId, Money.ZERO, Currency.COP, reason))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("amount");
        assertThatThrownBy(() -> new ReverseDebit(transferId, userId, amount, null, reason))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("currency");
        assertThatThrownBy(() -> new ReverseDebit(transferId, userId, amount, Currency.COP, null))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("reason");
    }
}
