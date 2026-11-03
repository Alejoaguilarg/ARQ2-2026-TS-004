package co.com.finova.wallet.model.processedmessage;

import co.com.finova.wallet.model.common.ContractViolationException;
import co.com.finova.wallet.model.common.Currency;
import co.com.finova.wallet.model.message.MessageType;
import co.com.finova.wallet.model.message.payload.BalanceChanged;
import co.com.finova.wallet.model.message.payload.DebitRejected;
import co.com.finova.wallet.model.message.payload.DebitRejectionReason;
import co.com.finova.wallet.model.message.payload.DebitWallet;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static co.com.finova.wallet.model.Fixtures.NOW;
import static co.com.finova.wallet.model.Fixtures.money;
import static co.com.finova.wallet.model.Fixtures.wallet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessedMessageTest {

    private final UUID messageId = UUID.randomUUID();
    private final DebitRejected rejected =
            new DebitRejected(UUID.randomUUID(), UUID.randomUUID(), DebitRejectionReason.INSUFFICIENT_FUNDS);

    @Test
    void withResultStoresTheEmittedResult_s4_7() {
        ProcessedMessage processed = ProcessedMessage.withResult(messageId, "wallet-commands", NOW, rejected);

        assertThat(processed.messageId()).isEqualTo(messageId);
        assertThat(processed.consumer()).isEqualTo("wallet-commands");
        assertThat(processed.processedAt()).isEqualTo(NOW);
        assertThat(processed.resultType()).isEqualTo(MessageType.DEBIT_REJECTED);
        assertThat(processed.resultPayload()).isSameAs(rejected);
    }

    @Test
    void resultIsOptional_s2_processedMessage() {
        ProcessedMessage processed = new ProcessedMessage(messageId, "wallet-commands", NOW, null, null);

        assertThat(processed.resultType()).isNull();
        assertThat(processed.resultPayload()).isNull();
    }

    @Test
    void resultTypeAndPayloadGoTogether_s2_processedMessage() {
        assertThatThrownBy(() -> new ProcessedMessage(messageId, "c", NOW, MessageType.DEBIT_REJECTED, null))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("resultPayload");
        assertThatThrownBy(() -> new ProcessedMessage(messageId, "c", NOW, null, rejected))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("resultType");
    }

    @Test
    void resultTypeMustMatchThePayload_s2_processedMessage() {
        assertThatThrownBy(() -> new ProcessedMessage(messageId, "c", NOW, MessageType.WALLET_DEBITED, rejected))
                .isInstanceOf(ContractViolationException.class)
                .hasMessageContaining("WalletDebited")
                .hasMessageContaining("DebitRejected");
    }

    @Test
    void onlySagaRepliesCanBeStoredAsResults_s2_processedMessage() {
        DebitWallet command = new DebitWallet(UUID.randomUUID(), UUID.randomUUID(), money("1.00"), Currency.COP);
        BalanceChanged changed = BalanceChanged.from(wallet("1.00", 1));

        assertThatThrownBy(() -> ProcessedMessage.withResult(messageId, "c", NOW, command))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("DebitWallet");
        assertThatThrownBy(() -> ProcessedMessage.withResult(messageId, "c", NOW, changed))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("BalanceChanged");
    }

    @Test
    void rejectsMissingRequiredFields_s2_processedMessage() {
        assertThatThrownBy(() -> ProcessedMessage.withResult(null, "c", NOW, rejected))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("messageId");
        assertThatThrownBy(() -> ProcessedMessage.withResult(messageId, null, NOW, rejected))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("consumer");
        assertThatThrownBy(() -> ProcessedMessage.withResult(messageId, " ", NOW, rejected))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("consumer");
        assertThatThrownBy(() -> ProcessedMessage.withResult(messageId, "c", null, rejected))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("processedAt");
        assertThatThrownBy(() -> ProcessedMessage.withResult(messageId, "c", NOW, null))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("resultPayload");
    }
}
