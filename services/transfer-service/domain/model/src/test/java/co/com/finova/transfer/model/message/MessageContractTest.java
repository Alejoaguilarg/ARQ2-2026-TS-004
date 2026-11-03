package co.com.finova.transfer.model.message;

import co.com.finova.transfer.model.common.ContractViolationException;
import co.com.finova.transfer.model.common.Currency;
import co.com.finova.transfer.model.message.payload.AttemptOutcome;
import co.com.finova.transfer.model.message.payload.AttemptRecorded;
import co.com.finova.transfer.model.message.payload.AttemptStage;
import co.com.finova.transfer.model.message.payload.DebitWallet;
import co.com.finova.transfer.model.message.payload.WalletDebited;
import co.com.finova.transfer.model.outbox.OutboxMessage;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static co.com.finova.transfer.model.Fixtures.NOW;
import static co.com.finova.transfer.model.Fixtures.money;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec §4.1 envelope, §4.2 topology of what Transfer publishes, §2 OutboxMessage. */
class MessageContractTest {

    private final UUID transferId = UUID.randomUUID();
    private final UUID correlationId = UUID.randomUUID();
    private final DebitWallet debit = new DebitWallet(transferId, UUID.randomUUID(), money("150000.00"), Currency.COP);

    @Test
    void transferPublishesEachMessageToItsExchangeAndRoutingKey() {
        assertThat(MessageType.DEBIT_WALLET.publishDestination()).isEqualTo(new Destination("wallet.commands", "debit"));
        assertThat(MessageType.CREDIT_WALLET.publishDestination()).isEqualTo(new Destination("wallet.commands", "credit"));
        assertThat(MessageType.REVERSE_DEBIT.publishDestination()).isEqualTo(new Destination("wallet.commands", "reverse"));
        assertThat(MessageType.TRANSFER_COMPLETED.publishDestination())
                .isEqualTo(new Destination("transfer.events", "transfer.completed"));
        assertThat(MessageType.TRANSFER_REJECTED.publishDestination())
                .isEqualTo(new Destination("transfer.events", "transfer.rejected"));
        assertThat(MessageType.TRANSFER_REVERSED.publishDestination())
                .isEqualTo(new Destination("transfer.events", "transfer.reversed"));
        assertThat(MessageType.ATTEMPT_RECORDED.publishDestination())
                .isEqualTo(new Destination("audit.events", "audit.attempt"));
    }

    @Test
    void walletResultsAreConsumedNeverPublished() {
        for (MessageType type : new MessageType[]{MessageType.WALLET_DEBITED, MessageType.DEBIT_REJECTED,
                MessageType.WALLET_CREDITED, MessageType.CREDIT_FAILED, MessageType.DEBIT_REVERSED}) {
            assertThat(type.isWalletResult()).isTrue();
            assertThatThrownBy(type::publishDestination).isInstanceOf(ContractViolationException.class);
        }
        assertThat(MessageType.DEBIT_WALLET.isWalletResult()).isFalse();
    }

    @Test
    void typesAreLookedUpByTheirContractName() {
        assertThat(MessageType.fromContractName("WalletCredited")).isEqualTo(MessageType.WALLET_CREDITED);
        assertThat(MessageType.CREDIT_FAILED.contractName()).isEqualTo("CreditFailed");
        assertThatThrownBy(() -> MessageType.fromContractName("BalanceChanged"))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("unknown");
    }

    @Test
    void anEnvelopeCreatedByTransferTakesItsTypeFromThePayload() {
        UUID messageId = UUID.randomUUID();

        MessageEnvelope<DebitWallet> envelope = MessageEnvelope.create(messageId, correlationId, transferId, NOW, debit);

        assertThat(envelope.type()).isEqualTo(MessageType.DEBIT_WALLET);
        assertThat(envelope.producer()).isEqualTo("transfer-service");
        assertThat(envelope.messageId()).isEqualTo(messageId);
    }

    @Test
    void anEnvelopeRejectsMissingFieldsAndAMismatchedType() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> new MessageEnvelope<>(null, MessageType.DEBIT_WALLET, correlationId, transferId,
                "p", NOW, debit)).hasMessageContaining("messageId");
        assertThatThrownBy(() -> new MessageEnvelope<>(id, null, correlationId, transferId, "p", NOW, debit))
                .hasMessageContaining("type");
        assertThatThrownBy(() -> new MessageEnvelope<>(id, MessageType.DEBIT_WALLET, null, transferId, "p", NOW,
                debit)).hasMessageContaining("correlationId");
        assertThatThrownBy(() -> new MessageEnvelope<>(id, MessageType.DEBIT_WALLET, correlationId, transferId, " ",
                NOW, debit)).hasMessageContaining("producer");
        assertThatThrownBy(() -> new MessageEnvelope<>(id, MessageType.DEBIT_WALLET, correlationId, transferId, "p",
                null, debit)).hasMessageContaining("occurredAt");
        assertThatThrownBy(() -> new MessageEnvelope<>(id, MessageType.DEBIT_WALLET, correlationId, transferId, "p",
                NOW, null)).hasMessageContaining("payload");
        assertThatThrownBy(() -> new MessageEnvelope<>(id, MessageType.CREDIT_WALLET, correlationId, transferId, "p",
                NOW, debit)).hasMessageContaining("does not match");
        assertThatThrownBy(() -> MessageEnvelope.create(id, correlationId, transferId, NOW, null))
                .hasMessageContaining("payload");
    }

    @Test
    void anAttemptsEventIdIsTheEnvelopeMessageId() {
        UUID eventId = UUID.randomUUID();
        AttemptRecorded attempt = AttemptRecorded.fromTransfer(eventId, AttemptStage.REQUEST, AttemptOutcome.ACCEPTED,
                null, transferId, UUID.randomUUID(), "k", money("1.00"), 202, correlationId, NOW);

        assertThat(MessageEnvelope.create(eventId, correlationId, transferId, NOW, attempt).messageId())
                .isEqualTo(eventId);
        assertThatThrownBy(() -> MessageEnvelope.create(UUID.randomUUID(), correlationId, transferId, NOW, attempt))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("eventId");
    }

    @Test
    void anOutboxRowGoesWhereItsTypeIsPublished() {
        MessageEnvelope<DebitWallet> envelope = MessageEnvelope.create(UUID.randomUUID(), correlationId, transferId,
                NOW, debit);

        OutboxMessage row = OutboxMessage.of(envelope, NOW);

        assertThat(row.id()).isEqualTo(envelope.messageId());
        assertThat(row.exchange()).isEqualTo("wallet.commands");
        assertThat(row.routingKey()).isEqualTo("debit");
        assertThat(row.createdAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> new OutboxMessage(UUID.randomUUID(), "wallet.commands", "debit", envelope, NOW))
                .hasMessageContaining("messageId");
        assertThatThrownBy(() -> new OutboxMessage(envelope.messageId(), "wallet.commands", "credit", envelope, NOW))
                .hasMessageContaining("must be published to");
        assertThatThrownBy(() -> OutboxMessage.of(null, NOW)).hasMessageContaining("envelope");
        assertThatThrownBy(() -> new OutboxMessage(null, "x", "y", envelope, NOW)).hasMessageContaining("id");
        assertThatThrownBy(() -> new OutboxMessage(envelope.messageId(), "x", "y", envelope, null))
                .hasMessageContaining("createdAt");
    }

    @Test
    void aWalletResultIsNeverWrittenToTheOutbox() {
        WalletDebited debited = new WalletDebited(transferId, UUID.randomUUID(), UUID.randomUUID(),
                money("1.00"), 1);
        MessageEnvelope<WalletDebited> envelope = new MessageEnvelope<>(UUID.randomUUID(),
                MessageType.WALLET_DEBITED, correlationId, transferId, "wallet-service", NOW, debited);

        assertThatThrownBy(() -> OutboxMessage.of(envelope, NOW)).isInstanceOf(ContractViolationException.class);
    }
}
