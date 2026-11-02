package co.com.finova.wallet.model.outbox;

import co.com.finova.wallet.model.common.ContractViolationException;
import co.com.finova.wallet.model.common.Currency;
import co.com.finova.wallet.model.message.MessageEnvelope;
import co.com.finova.wallet.model.message.MessageType;
import co.com.finova.wallet.model.message.payload.AttemptOutcome;
import co.com.finova.wallet.model.message.payload.AttemptRecorded;
import co.com.finova.wallet.model.message.payload.AttemptStage;
import co.com.finova.wallet.model.message.payload.DebitWallet;
import co.com.finova.wallet.model.message.payload.WalletDebited;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static co.com.finova.wallet.model.Fixtures.NOW;
import static co.com.finova.wallet.model.Fixtures.money;
import static co.com.finova.wallet.model.Fixtures.wallet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxMessageTest {

    private final UUID messageId = UUID.randomUUID();
    private final UUID correlationId = UUID.randomUUID();
    private final UUID transferId = UUID.randomUUID();
    private final MessageEnvelope<WalletDebited> debited = MessageEnvelope.create(messageId, correlationId,
            transferId, NOW, WalletDebited.from(transferId, money("10.00"), wallet("90.00", 1)));

    @Test
    void ofTakesIdAndDestinationFromTheEnvelope_s2_outbox() {
        OutboxMessage row = OutboxMessage.of(debited, NOW);

        assertThat(row.id()).isEqualTo(messageId);
        assertThat(row.exchange()).isEqualTo("wallet.events");
        assertThat(row.routingKey()).isEqualTo("wallet.debited");
        assertThat(row.envelope()).isSameAs(debited);
        assertThat(row.createdAt()).isEqualTo(NOW);
        assertThat(row.publishedAt()).isNull();
    }

    @Test
    void attemptRecordedGoesToAuditEvents_s4_2() {
        AttemptRecorded attempt = AttemptRecorded.fromWallet(messageId, AttemptStage.DEBIT, AttemptOutcome.APPLIED,
                null, transferId, UUID.randomUUID(), money("10.00"), correlationId, NOW);

        OutboxMessage row = OutboxMessage.of(MessageEnvelope.create(messageId, correlationId, transferId, NOW,
                attempt), NOW);

        assertThat(row.exchange()).isEqualTo("audit.events");
        assertThat(row.routingKey()).isEqualTo("audit.attempt");
    }

    @Test
    void acceptsPublishedAt_s2_outbox() {
        assertThat(new OutboxMessage(messageId, "wallet.events", "wallet.debited", debited, NOW, NOW).publishedAt())
                .isEqualTo(NOW);
    }

    @Test
    void rejectsCommandsBecauseWalletNeverPublishesThem_s4_3() {
        DebitWallet command = new DebitWallet(transferId, UUID.randomUUID(), money("10.00"), Currency.COP);
        MessageEnvelope<DebitWallet> incoming = new MessageEnvelope<>(messageId, MessageType.DEBIT_WALLET,
                correlationId, transferId, "transfer-service", NOW, command);

        assertThatThrownBy(() -> OutboxMessage.of(incoming, NOW))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("DebitWallet");
    }

    @Test
    void idMustEqualTheEnvelopeMessageId_s2_outbox() {
        assertThatThrownBy(() -> new OutboxMessage(UUID.randomUUID(), "wallet.events", "wallet.debited",
                debited, NOW, null))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("messageId");
    }

    @Test
    void destinationMustMatchTheTopology_s4_2() {
        assertThatThrownBy(() -> new OutboxMessage(messageId, "wallet.events", "wallet.credited", debited, NOW, null))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("WalletDebited");
        assertThatThrownBy(() -> new OutboxMessage(messageId, "audit.events", "wallet.debited", debited, NOW, null))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("WalletDebited");
    }

    @Test
    void rejectsMissingRequiredFields_s2_outbox() {
        assertThatThrownBy(() -> OutboxMessage.of(null, NOW))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("envelope");
        assertThatThrownBy(() -> OutboxMessage.of(debited, null))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("createdAt");
        assertThatThrownBy(() -> new OutboxMessage(null, "wallet.events", "wallet.debited", debited, NOW, null))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("id");
        assertThatThrownBy(() -> new OutboxMessage(messageId, null, "wallet.debited", debited, NOW, null))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("exchange");
        assertThatThrownBy(() -> new OutboxMessage(messageId, "wallet.events", null, debited, NOW, null))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("routingKey");
    }
}
