package co.com.finova.wallet.model.message;

import co.com.finova.wallet.model.common.ContractViolationException;
import co.com.finova.wallet.model.common.Currency;
import co.com.finova.wallet.model.message.payload.AttemptOutcome;
import co.com.finova.wallet.model.message.payload.AttemptRecorded;
import co.com.finova.wallet.model.message.payload.AttemptStage;
import co.com.finova.wallet.model.message.payload.BalanceChanged;
import co.com.finova.wallet.model.message.payload.DebitWallet;
import co.com.finova.wallet.model.message.payload.WalletDebited;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static co.com.finova.wallet.model.Fixtures.NOW;
import static co.com.finova.wallet.model.Fixtures.money;
import static co.com.finova.wallet.model.Fixtures.wallet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageEnvelopeTest {

    private final UUID messageId = UUID.randomUUID();
    private final UUID correlationId = UUID.randomUUID();
    private final UUID transferId = UUID.randomUUID();
    private final WalletDebited debited = WalletDebited.from(transferId, money("150000.00"), wallet("350000.00", 1));

    @Test
    void createUsesWalletProducerAndTypeFromPayload_s4_1() {
        MessageEnvelope<WalletDebited> envelope =
                MessageEnvelope.create(messageId, correlationId, transferId, NOW, debited);

        assertThat(envelope.messageId()).isEqualTo(messageId);
        assertThat(envelope.type()).isEqualTo(MessageType.WALLET_DEBITED);
        assertThat(envelope.correlationId()).isEqualTo(correlationId);
        assertThat(envelope.transferId()).isEqualTo(transferId);
        assertThat(envelope.producer()).isEqualTo("wallet-service").isEqualTo(MessageEnvelope.WALLET_PRODUCER);
        assertThat(envelope.occurredAt()).isEqualTo(NOW);
        assertThat(envelope.payload()).isSameAs(debited);
    }

    @Test
    void acceptsIncomingCommandFromAnotherProducer_s4_1() {
        DebitWallet command = new DebitWallet(transferId, UUID.randomUUID(), money("150000.00"), Currency.COP);

        MessageEnvelope<DebitWallet> envelope = new MessageEnvelope<>(messageId, MessageType.DEBIT_WALLET,
                correlationId, transferId, "transfer-service", NOW, command);

        assertThat(envelope.producer()).isEqualTo("transfer-service");
    }

    @Test
    void transferIdMayBeNull_s4_1() {
        BalanceChanged changed = BalanceChanged.from(wallet("100.00", 2));

        assertThat(MessageEnvelope.create(messageId, correlationId, null, NOW, changed).transferId()).isNull();
    }

    @Test
    void rejectsTypeThatDoesNotMatchThePayload_s4_1() {
        assertThatThrownBy(() -> new MessageEnvelope<>(messageId, MessageType.WALLET_CREDITED,
                correlationId, transferId, "wallet-service", NOW, debited))
                .isInstanceOf(ContractViolationException.class)
                .hasMessageContaining("WalletCredited")
                .hasMessageContaining("WalletDebited");
    }

    @Test
    void rejectsBlankProducer_s4_1() {
        assertThatThrownBy(() -> new MessageEnvelope<>(messageId, MessageType.WALLET_DEBITED,
                correlationId, transferId, "  ", NOW, debited))
                .isInstanceOf(ContractViolationException.class)
                .hasMessageContaining("producer");
    }

    @Test
    void attemptRecordedEventIdMustEqualMessageId_s4_4() {
        AttemptRecorded sameId = AttemptRecorded.fromWallet(messageId, AttemptStage.DEBIT, AttemptOutcome.APPLIED,
                null, transferId, UUID.randomUUID(), money("10.00"), correlationId, NOW);
        AttemptRecorded otherId = AttemptRecorded.fromWallet(UUID.randomUUID(), AttemptStage.DEBIT,
                AttemptOutcome.APPLIED, null, transferId, UUID.randomUUID(), money("10.00"), correlationId, NOW);

        assertThat(MessageEnvelope.create(messageId, correlationId, transferId, NOW, sameId).payload())
                .isSameAs(sameId);
        assertThatThrownBy(() -> MessageEnvelope.create(messageId, correlationId, transferId, NOW, otherId))
                .isInstanceOf(ContractViolationException.class)
                .hasMessageContaining("eventId");
    }

    @Test
    void rejectsMissingRequiredFields_s4_1() {
        assertThatThrownBy(() -> MessageEnvelope.create(null, correlationId, transferId, NOW, debited))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("messageId");
        assertThatThrownBy(() -> MessageEnvelope.create(messageId, null, transferId, NOW, debited))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("correlationId");
        assertThatThrownBy(() -> MessageEnvelope.create(messageId, correlationId, transferId, null, debited))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("occurredAt");
        assertThatThrownBy(() -> MessageEnvelope.create(messageId, correlationId, transferId, NOW, null))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("payload");
        assertThatThrownBy(() -> new MessageEnvelope<>(messageId, null, correlationId, transferId,
                "wallet-service", NOW, debited))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("type");
        assertThatThrownBy(() -> new MessageEnvelope<>(messageId, MessageType.WALLET_DEBITED, correlationId,
                transferId, null, NOW, debited))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("producer");
    }
}
