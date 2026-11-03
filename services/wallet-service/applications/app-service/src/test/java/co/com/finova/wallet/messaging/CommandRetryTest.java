package co.com.finova.wallet.messaging;

import co.com.finova.wallet.usecase.debitwallet.DebitWalletUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Decision S1: a technical error on wallet.commands.q is retried 3 times after the first delivery
 * (4 deliveries, 5 s apart in wallet.commands.q.retry) and then dead-lettered. The debit use case is
 * replaced by a mock that always fails, standing in for an outage of wallet_db.
 */
@SpringBootTest
class CommandRetryTest extends MessagingTestBase {

    @MockitoBean
    private DebitWalletUseCase debitWallet;

    @Test
    void Retry_technicalErrorGoesToDlqAfterThreeRetries() {
        doThrow(new IllegalStateException("wallet_db unavailable")).when(debitWallet).handle(any());
        String messageId = UUID.randomUUID().toString();
        UUID transferId = UUID.randomUUID();

        publish("wallet.commands", "debit", messageId, envelope(messageId, "DebitWallet", UUID.randomUUID(),
                transferId, "transfer-service", debitWallet(transferId, UUID.randomUUID(), "10.00")));

        Message dead = receive("wallet.commands.q.dlq", Duration.ofSeconds(40));
        assertThat(dead).as("message in the DLQ").isNotNull();
        assertThat(dead.getMessageProperties().getMessageId()).isEqualTo(messageId);
        assertThat(dead.getMessageProperties().<String>getHeader("x-wallet-error"))
                .isEqualTo("IllegalStateException: wallet_db unavailable");
        assertThat(rejectedCount(dead)).isEqualTo(3);
        verify(debitWallet, times(4)).handle(any());
    }

    @SuppressWarnings("unchecked")
    private static long rejectedCount(Message message) {
        List<Map<String, ?>> xDeath = (List<Map<String, ?>>) message.getMessageProperties().getHeaders()
                .get("x-death");
        return xDeath.stream()
                .filter(entry -> "wallet.commands.q".equals(entry.get("queue"))
                        && "rejected".equals(entry.get("reason")))
                .mapToLong(entry -> ((Number) entry.get("count")).longValue())
                .sum();
    }
}
