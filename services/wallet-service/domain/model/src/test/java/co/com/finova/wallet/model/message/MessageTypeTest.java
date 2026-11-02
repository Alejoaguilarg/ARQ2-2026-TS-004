package co.com.finova.wallet.model.message;

import co.com.finova.wallet.model.common.ContractViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageTypeTest {

    @ParameterizedTest
    @CsvSource({
            "WALLET_DEBITED,   WalletDebited,   wallet.events, wallet.debited",
            "DEBIT_REJECTED,   DebitRejected,   wallet.events, wallet.debit-rejected",
            "WALLET_CREDITED,  WalletCredited,  wallet.events, wallet.credited",
            "CREDIT_FAILED,    CreditFailed,    wallet.events, wallet.credit-failed",
            "DEBIT_REVERSED,   DebitReversed,   wallet.events, wallet.debit-reversed",
            "BALANCE_CHANGED,  BalanceChanged,  wallet.events, wallet.balance-changed",
            "ATTEMPT_RECORDED, AttemptRecorded, audit.events,  audit.attempt"
    })
    void publishedMessagesUseTheTopologyOfS4_2(MessageType type, String contractName, String exchange, String routingKey) {
        assertThat(type.contractName()).isEqualTo(contractName);
        assertThat(type.isPublishedByWallet()).isTrue();
        assertThat(type.publishDestination()).isEqualTo(new Destination(exchange, routingKey));
    }

    @ParameterizedTest
    @CsvSource({
            "DEBIT_WALLET,  DebitWallet",
            "CREDIT_WALLET, CreditWallet",
            "REVERSE_DEBIT, ReverseDebit"
    })
    void commandsAreConsumedButNeverPublishedByWallet_s4_3(MessageType type, String contractName) {
        assertThat(type.contractName()).isEqualTo(contractName);
        assertThat(type.kind()).isEqualTo(MessageType.Kind.COMMAND);
        assertThat(type.isPublishedByWallet()).isFalse();
        assertThatThrownBy(type::publishDestination)
                .isInstanceOf(ContractViolationException.class)
                .hasMessageContaining(contractName);
    }

    @Test
    void onlyTheFiveSagaRepliesAreResults_s2_processedMessage() {
        assertThat(Arrays.stream(MessageType.values()).filter(MessageType::isResult))
                .containsExactlyInAnyOrder(MessageType.WALLET_DEBITED, MessageType.DEBIT_REJECTED,
                        MessageType.WALLET_CREDITED, MessageType.CREDIT_FAILED, MessageType.DEBIT_REVERSED);
    }

    @Test
    void kindsOfNonResultMessages_s4_3() {
        assertThat(MessageType.BALANCE_CHANGED.kind()).isEqualTo(MessageType.Kind.PROJECTION);
        assertThat(MessageType.ATTEMPT_RECORDED.kind()).isEqualTo(MessageType.Kind.AUDIT);
        assertThat(MessageType.WALLET_DEBITED.kind()).isEqualTo(MessageType.Kind.RESULT);
    }

    @ParameterizedTest
    @EnumSource(MessageType.class)
    void fromContractNameRoundTrips_s4_1(MessageType type) {
        assertThat(MessageType.fromContractName(type.contractName())).isEqualTo(type);
    }

    @Test
    void fromContractNameRejectsUnknownOrMissingNames_s4_1() {
        assertThatThrownBy(() -> MessageType.fromContractName("TransferCompleted"))
                .isInstanceOf(ContractViolationException.class)
                .hasMessageContaining("TransferCompleted");
        assertThatThrownBy(() -> MessageType.fromContractName("DEBIT_WALLET"))
                .isInstanceOf(ContractViolationException.class);
        assertThatThrownBy(() -> MessageType.fromContractName(null))
                .isInstanceOf(ContractViolationException.class);
    }

    @Test
    void destinationRejectsBlankParts() {
        assertThatThrownBy(() -> new Destination(" ", "wallet.debited"))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("exchange");
        assertThatThrownBy(() -> new Destination("wallet.events", null))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("routingKey");
        assertThatThrownBy(() -> new Destination(null, "wallet.debited"))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("exchange");
        assertThatThrownBy(() -> new Destination("wallet.events", ""))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("routingKey");
    }
}
