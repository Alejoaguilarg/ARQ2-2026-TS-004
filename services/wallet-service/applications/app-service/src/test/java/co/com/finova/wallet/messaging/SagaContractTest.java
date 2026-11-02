package co.com.finova.wallet.messaging;

import co.com.finova.wallet.model.message.MessageEnvelope;
import co.com.finova.wallet.model.message.payload.WalletDebited;
import co.com.finova.wallet.model.money.Money;
import co.com.finova.wallet.model.outbox.OutboxMessage;
import co.com.finova.wallet.model.outbox.gateways.OutboxRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Wallet seen from Transfer, through a real broker (spec §4.2–§4.4, §4.7, ADR 002): a command published to
 * wallet.commands goes through the listener, the use case, the outbox and the relay, and its reply arrives in
 * transfer.wallet-events.q with the envelope of §4.1. Payload schemas of §4.4.
 */
@SpringBootTest
class SagaContractTest extends MessagingTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COMMANDS = "wallet.commands";
    private static final String TRANSFER_EVENTS = "transfer.wallet-events.q";
    private static final String AUDIT = "audit.attempts.q";
    private static final Duration WAIT = Duration.ofSeconds(15);

    @Autowired
    private RabbitListenerEndpointRegistry listeners;

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private ApplicationContext context;

    private final UUID sender = UUID.randomUUID();
    private final UUID recipient = UUID.randomUUID();
    private final UUID transferId = UUID.randomUUID();
    private final UUID correlationId = UUID.randomUUID();

    // ---------------------------------------------------------------- contract with Transfer

    @Test
    void Contract_debitWalletRepliesOnTransferQueue() throws Exception {
        UUID senderWallet = givenWallet(sender, "500000.00");

        sendDebit(UUID.randomUUID(), "150000.00");

        Reply reply = nextReply();
        assertThat(reply.routingKey).isEqualTo("wallet.debited");
        assertThat(reply.exchange).isEqualTo("wallet.events");
        assertEnvelope(reply, "WalletDebited");
        JsonNode payload = reply.envelope.get("payload");
        assertThat(fieldNames(payload)).containsExactlyInAnyOrder("transferId", "walletId", "userId", "amount",
                "version");
        assertThat(payload.get("transferId").asText()).isEqualTo(transferId.toString());
        assertThat(payload.get("walletId").asText()).isEqualTo(senderWallet.toString());
        assertThat(payload.get("userId").asText()).isEqualTo(sender.toString());
        assertThat(payload.get("amount").isTextual()).isTrue();
        assertThat(payload.get("amount").asText()).isEqualTo("150000.00");
        assertThat(payload.get("version").asLong()).isEqualTo(1);

        // BalanceChanged went through the relay and the projection queue into Redis.
        await("Redis balance of the sender", WAIT,
                () -> "350000.00".equals(redis.opsForHash().get("balance:" + sender, "available")));
        await("every outbox row published", WAIT, () -> pendingOutboxRows() == 0);
    }

    @Test
    void Contract_insufficientFundsRepliesDebitRejected() throws Exception {
        givenWallet(sender, "50000.00");

        sendDebit(UUID.randomUUID(), "80000.00");

        Reply reply = nextReply();
        assertThat(reply.routingKey).isEqualTo("wallet.debit-rejected");
        assertEnvelope(reply, "DebitRejected");
        JsonNode payload = reply.envelope.get("payload");
        assertThat(fieldNames(payload)).containsExactlyInAnyOrder("transferId", "userId", "reason");
        assertThat(payload.get("reason").asText()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(balanceOf(sender)).isEqualByComparingTo("50000.00");
    }

    @Test
    void Contract_creditWithoutWalletRepliesCreditFailed() throws Exception {
        givenWallet(sender, "500000.00");
        sendDebit(UUID.randomUUID(), "100000.00");
        assertThat(nextReply().type()).isEqualTo("WalletDebited");

        sendCredit(UUID.randomUUID(), recipient, 1);

        Reply reply = nextReply();
        assertThat(reply.routingKey).isEqualTo("wallet.credit-failed");
        assertEnvelope(reply, "CreditFailed");
        JsonNode payload = reply.envelope.get("payload");
        assertThat(fieldNames(payload)).containsExactlyInAnyOrder("transferId", "userId", "attempt", "reason");
        assertThat(payload.get("reason").asText()).isEqualTo("RECIPIENT_WALLET_NOT_FOUND");
        assertThat(payload.get("attempt").asInt()).isEqualTo(1);
    }

    @Test
    void Contract_reverseAfterCreditReemitsWalletCredited() throws Exception {
        givenWallet(sender, "500000.00");
        givenWallet(recipient, "0.00");
        sendDebit(UUID.randomUUID(), "100000.00");
        assertThat(nextReply().type()).isEqualTo("WalletDebited");
        sendCredit(UUID.randomUUID(), recipient, 1);
        Reply credited = nextReply();
        assertThat(credited.routingKey).isEqualTo("wallet.credited");

        sendReverse(UUID.randomUUID());

        Reply reply = nextReply();
        assertThat(reply.routingKey).isEqualTo("wallet.credited");
        assertThat(reply.envelope.get("payload")).isEqualTo(credited.envelope.get("payload"));
        assertThat(ledgerRows("REVERSAL")).isZero();
        assertThat(balanceOf(sender)).isEqualByComparingTo("400000.00");
        assertThat(balanceOf(recipient)).isEqualByComparingTo("100000.00");
    }

    @Test
    void F4_8_duplicateDebitThroughBrokerRepublishesStoredResult() throws Exception {
        givenWallet(sender, "500000.00");
        UUID messageId = UUID.randomUUID();

        sendDebit(messageId, "150000.00");
        Reply first = nextReply();
        sendDebit(messageId, "150000.00");
        Reply second = nextReply();

        assertThat(first.type()).isEqualTo("WalletDebited");
        assertThat(second.type()).isEqualTo("WalletDebited");
        assertThat(second.envelope.get("payload")).isEqualTo(first.envelope.get("payload"));
        assertThat(ledgerRows("DEBIT")).isEqualTo(1);
        assertThat(balanceOf(sender)).isEqualByComparingTo("350000.00");
    }

    @Test
    void F4_10_commandsQueuedWhileWalletDownAreAppliedOnStart() throws Exception {
        givenWallet(sender, "500000.00");
        listeners.stop();
        try {
            sendDebit(UUID.randomUUID(), "150000.00");
            await("the command waiting in the durable queue", WAIT,
                    () -> messageCount("wallet.commands.q") == 1);
            assertThat(ledgerRows("DEBIT")).isZero();
        } finally {
            listeners.start();
        }

        assertThat(nextReply().type()).isEqualTo("WalletDebited");
        assertThat(balanceOf(sender)).isEqualByComparingTo("350000.00");
    }

    @Test
    void F4_11_queuedCreditAndReverseSettleOnlyOnce() throws Exception {
        givenWallet(sender, "500000.00");
        givenWallet(recipient, "0.00");
        sendDebit(UUID.randomUUID(), "100000.00");
        assertThat(nextReply().type()).isEqualTo("WalletDebited");

        listeners.stop();
        try {
            sendCredit(UUID.randomUUID(), recipient, 1);
            sendReverse(UUID.randomUUID());
            await("both commands queued", WAIT, () -> messageCount("wallet.commands.q") == 2);
        } finally {
            listeners.start();
        }

        List<String> replies = List.of(nextReply().type(), nextReply().type());
        int credits = ledgerRows("CREDIT");
        int reversals = ledgerRows("REVERSAL");
        assertThat(credits + reversals).as("exactly one settlement").isEqualTo(1);
        if (credits == 1) {
            assertThat(replies).containsExactly("WalletCredited", "WalletCredited");
        } else {
            assertThat(replies).containsExactlyInAnyOrder("DebitReversed", "CreditFailed");
        }
        assertThat(balanceOf(sender).add(balanceOf(recipient)))
                .as("money is never lost or created").isEqualByComparingTo("500000.00");
    }

    // ---------------------------------------------------------------- audit

    @Test
    void Audit_attemptRecordedReachesAuditQueueWithHttpStatusKey() throws Exception {
        givenWallet(sender, "500000.00");

        sendDebit(UUID.randomUUID(), "150000.00");

        Message message = receive(AUDIT, WAIT);
        assertThat(message).as("AttemptRecorded in audit.attempts.q").isNotNull();
        assertThat(message.getMessageProperties().getReceivedRoutingKey()).isEqualTo("audit.attempt");
        JsonNode envelope = JSON.readTree(message.getBody());
        assertThat(envelope.get("type").asText()).isEqualTo("AttemptRecorded");
        JsonNode payload = envelope.get("payload");
        assertThat(payload.has("httpStatus")).isTrue();
        assertThat(payload.get("httpStatus").isNull()).isTrue();
        assertThat(payload.has("statusCode")).isFalse();
        assertThat(payload.get("eventId").asText()).isEqualTo(envelope.get("messageId").asText());
        assertThat(payload.get("source").asText()).isEqualTo("WALLET");
        assertThat(payload.get("stage").asText()).isEqualTo("DEBIT");
        assertThat(payload.get("outcome").asText()).isEqualTo("APPLIED");
    }

    // ---------------------------------------------------------------- relay

    @Test
    void Relay_pendingRowsPublishedAfterRestart() throws Exception {
        // A row committed before a crash and never published: the relay finds it on its next run.
        UUID messageId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        WalletDebited debited = new WalletDebited(transferId, UUID.randomUUID(), sender, Money.parse("10.00"), 1);
        outbox.save(OutboxMessage.of(MessageEnvelope.create(messageId, correlationId, transferId, now, debited),
                now));

        Reply reply = nextReply();

        assertThat(reply.envelope.get("messageId").asText()).isEqualTo(messageId.toString());
        await("published_at set", WAIT, () -> pendingOutboxRows() == 0);
    }

    @Test
    void Relay_failedPublishKeepsRowPending() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO outbox_messages (id, exchange, routing_key, payload, created_at, published_at)
                VALUES (?, 'missing.exchange', 'nowhere', CAST(? AS jsonb), now(), NULL)
                """, id, "{\"messageId\":\"" + id + "\",\"correlationId\":\"" + correlationId + "\"}");

        pause(Duration.ofSeconds(2)); // about 20 relay runs

        assertThat(jdbc.queryForObject("SELECT published_at IS NULL FROM outbox_messages WHERE id = ?",
                Boolean.class, id)).isTrue();
    }

    // ---------------------------------------------------------------- topology

    @Test
    void Topology_walletDeclaresNothing() throws Exception {
        assertThat(context.getBeansOfType(Declarable.class)).as("Queue, Exchange or Binding beans").isEmpty();
        assertThat(context.getBeansOfType(RabbitAdmin.class)).as("RabbitAdmin beans").isEmpty();

        givenWallet(sender, "500000.00");
        sendDebit(UUID.randomUUID(), "150000.00");
        nextReply();

        assertThat(brokerQueues()).containsExactlyInAnyOrderElementsOf(definitionQueues());
    }

    // ---------------------------------------------------------------- helpers

    private record Reply(JsonNode envelope, String messageId, String exchange, String routingKey) {
        String type() {
            return envelope.get("type").asText();
        }
    }

    private Reply nextReply() throws Exception {
        Message message = receive(TRANSFER_EVENTS, WAIT);
        assertThat(message).as("a reply in " + TRANSFER_EVENTS).isNotNull();
        return new Reply(JSON.readTree(message.getBody()), message.getMessageProperties().getMessageId(),
                message.getMessageProperties().getReceivedExchange(),
                message.getMessageProperties().getReceivedRoutingKey());
    }

    private void assertEnvelope(Reply reply, String type) {
        JsonNode envelope = reply.envelope;
        assertThat(fieldNames(envelope)).containsExactlyInAnyOrder("messageId", "type", "correlationId",
                "transferId", "producer", "occurredAt", "payload");
        assertThat(envelope.get("type").asText()).isEqualTo(type);
        assertThat(envelope.get("messageId").asText()).as("AMQP message-id = envelope messageId (§4.1)")
                .isEqualTo(reply.messageId);
        assertThat(envelope.get("correlationId").asText()).isEqualTo(correlationId.toString());
        assertThat(envelope.get("transferId").asText()).isEqualTo(transferId.toString());
        assertThat(envelope.get("producer").asText()).isEqualTo("wallet-service");
        assertThat(Instant.parse(envelope.get("occurredAt").asText())).isNotNull();
    }

    private void sendDebit(UUID messageId, String amount) {
        publish(COMMANDS, "debit", messageId.toString(), envelope(messageId.toString(), "DebitWallet",
                correlationId, transferId, "transfer-service", debitWallet(transferId, sender, amount)));
    }

    private void sendCredit(UUID messageId, UUID userId, int attempt) {
        publish(COMMANDS, "credit", messageId.toString(), envelope(messageId.toString(), "CreditWallet",
                correlationId, transferId, "transfer-service", creditWallet(transferId, userId, "100000.00",
                        attempt)));
    }

    private void sendReverse(UUID messageId) {
        publish(COMMANDS, "reverse", messageId.toString(), envelope(messageId.toString(), "ReverseDebit",
                correlationId, transferId, "transfer-service",
                reverseDebit(transferId, sender, "100000.00", "CREDIT_TIMEOUT")));
    }

    private int ledgerRows(String type) {
        return jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE type = ?", Integer.class, type);
    }

    private int pendingOutboxRows() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_messages WHERE published_at IS NULL",
                Integer.class);
    }

    private BigDecimal balanceOf(UUID userId) {
        return jdbc.queryForObject("SELECT balance FROM wallets WHERE user_id = ?", BigDecimal.class, userId);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
