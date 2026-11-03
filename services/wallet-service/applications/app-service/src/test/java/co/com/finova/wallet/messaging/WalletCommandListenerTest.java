package co.com.finova.wallet.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * wallet.commands.q through a real broker (spec §4.2): commands reach their use case and are acked; invalid
 * messages go straight to wallet.commands.q.dlq without a processed_messages row (decision S2).
 */
@SpringBootTest
class WalletCommandListenerTest extends MessagingTestBase {

    private static final String COMMANDS = "wallet.commands";
    private static final String QUEUE = "wallet.commands.q";
    private static final String DLQ = "wallet.commands.q.dlq";
    private static final Duration WAIT = Duration.ofSeconds(10);

    private final UUID sender = UUID.randomUUID();
    private final UUID recipient = UUID.randomUUID();
    private final UUID transferId = UUID.randomUUID();
    private final UUID correlationId = UUID.randomUUID();

    @Test
    void Listener_debitCommandIsAppliedAndAcked() {
        givenWallet(sender, "500000.00");

        sendDebit(UUID.randomUUID().toString(), "150000.00");

        await("the DEBIT ledger entry", WAIT, () -> ledgerRows("DEBIT") == 1);
        assertThat(balanceOf(sender)).isEqualByComparingTo("350000.00");
        await("the processed_messages row", WAIT, () -> processedMessages() == 1);
        assertThat(messageCount(QUEUE)).isZero();
        assertThat(messageCount(DLQ)).isZero();
    }

    @Test
    void Listener_creditAndReverseReachTheirUseCases() {
        givenWallet(sender, "500000.00");
        givenWallet(recipient, "0.00");
        UUID reversed = UUID.randomUUID();
        sendDebit(UUID.randomUUID().toString(), "100000.00");
        String secondDebitId = UUID.randomUUID().toString();
        publish(COMMANDS, "debit", secondDebitId, envelope(secondDebitId, "DebitWallet", correlationId, reversed,
                "transfer-service", debitWallet(reversed, sender, "50000.00")));
        await("both debits", WAIT, () -> ledgerRows("DEBIT") == 2);

        String creditId = UUID.randomUUID().toString();
        publish(COMMANDS, "credit", creditId, envelope(creditId, "CreditWallet", correlationId, transferId,
                "transfer-service", creditWallet(transferId, recipient, "100000.00", 1)));
        String reverseId = UUID.randomUUID().toString();
        publish(COMMANDS, "reverse", reverseId, envelope(reverseId, "ReverseDebit", correlationId, reversed,
                "transfer-service", reverseDebit(reversed, sender, "50000.00", "CREDIT_TIMEOUT")));

        await("the CREDIT and the REVERSAL", WAIT, () -> ledgerRows("CREDIT") == 1 && ledgerRows("REVERSAL") == 1);
        assertThat(balanceOf(recipient)).isEqualByComparingTo("100000.00");
        assertThat(balanceOf(sender)).isEqualByComparingTo("400000.00");
    }

    @Test
    void Listener_redeliveredCommandIsNotAppliedTwice() {
        givenWallet(sender, "500000.00");
        String messageId = UUID.randomUUID().toString();

        sendDebit(messageId, "150000.00");
        sendDebit(messageId, "150000.00");

        await("the first delivery applied", WAIT, () -> processedMessages() == 1);
        await("the second delivery consumed", WAIT, () -> messageCount(QUEUE) == 0);
        assertThat(processedMessages()).isEqualTo(1);
        assertThat(ledgerRows("DEBIT")).isEqualTo(1);
        assertThat(balanceOf(sender)).isEqualByComparingTo("350000.00");
    }

    @Test
    void Dlq_invalidJsonGoesStraightToDlqWithoutProcessedMessage() {
        publish(COMMANDS, "debit", UUID.randomUUID().toString(), "{\"messageId\":");

        Message dead = receive(DLQ, WAIT);

        assertThat(dead).isNotNull();
        assertThat(new String(dead.getBody())).isEqualTo("{\"messageId\":");
        assertThat(dead.getMessageProperties().<String>getHeader("x-wallet-error"))
                .startsWith("ContractViolationException");
        assertThat(dead.getMessageProperties().<Object>getHeader("x-death")).isNull();
        assertThat(processedMessages()).isZero();
    }

    @Test
    void Dlq_debitMismatchGoesStraightToDlq() {
        givenWallet(recipient, "0.00");
        String creditId = UUID.randomUUID().toString();

        publish(COMMANDS, "credit", creditId, envelope(creditId, "CreditWallet", correlationId, transferId,
                "transfer-service", creditWallet(transferId, recipient, "100000.00", 1)));

        Message dead = receive(DLQ, WAIT);
        assertThat(dead).isNotNull();
        assertThat(dead.getMessageProperties().<String>getHeader("x-wallet-error"))
                .startsWith("DebitMismatchException");
        assertThat(processedMessages()).isZero();
        assertThat(balanceOf(recipient)).isEqualByComparingTo("0.00");
    }

    @Test
    void Dlq_messageTypeThatDoesNotBelongToTheQueueGoesToDlq() {
        String messageId = UUID.randomUUID().toString();
        String balanceChanged = """
                {"walletId":"%s","userId":"%s","available":"10.00","version":1}"""
                .formatted(UUID.randomUUID(), sender);

        publish("", QUEUE, messageId, envelope(messageId, "BalanceChanged", correlationId, null,
                "wallet-service", balanceChanged));

        Message dead = receive(DLQ, WAIT);
        assertThat(dead).isNotNull();
        assertThat(dead.getMessageProperties().<String>getHeader("x-wallet-error")).contains("BalanceChanged");
    }

    private void sendDebit(String messageId, String amount) {
        publish(COMMANDS, "debit", messageId, envelope(messageId, "DebitWallet", correlationId, transferId,
                "transfer-service", debitWallet(transferId, sender, amount)));
    }

    private int ledgerRows(String type) {
        return jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE type = ?", Integer.class, type);
    }

    private int processedMessages() {
        return jdbc.queryForObject("SELECT count(*) FROM processed_messages", Integer.class);
    }

    private BigDecimal balanceOf(UUID userId) {
        return jdbc.queryForObject("SELECT balance FROM wallets WHERE user_id = ?", BigDecimal.class, userId);
    }
}
