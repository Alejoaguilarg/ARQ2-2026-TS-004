package co.com.finova.wallet.rabbitmqconsumer;

import co.com.finova.wallet.messagejson.MessageJsonMapper;
import co.com.finova.wallet.model.common.ContractViolationException;
import co.com.finova.wallet.model.message.MessageEnvelope;
import co.com.finova.wallet.model.message.payload.CreditWallet;
import co.com.finova.wallet.model.message.payload.DebitWallet;
import co.com.finova.wallet.model.message.payload.ReverseDebit;
import co.com.finova.wallet.usecase.creditwallet.CreditWalletUseCase;
import co.com.finova.wallet.usecase.debitwallet.DebitWalletUseCase;
import co.com.finova.wallet.usecase.reversedebit.ReverseDebitUseCase;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Saga commands from Transfer on wallet.commands.q (spec §4.2, §4.7): reads the envelope and hands it to its
 * use case, which owns every business rule. The queue already exists; nothing is declared here.
 */
@Component
public class WalletCommandListener {

    static final String QUEUE = "wallet.commands.q";

    private final MessageJsonMapper json;
    private final DeliveryOutcomeHandler outcomes;
    private final DebitWalletUseCase debitWallet;
    private final CreditWalletUseCase creditWallet;
    private final ReverseDebitUseCase reverseDebit;

    public WalletCommandListener(MessageJsonMapper json, DeliveryOutcomeHandler outcomes,
                                 DebitWalletUseCase debitWallet, CreditWalletUseCase creditWallet,
                                 ReverseDebitUseCase reverseDebit) {
        this.json = json;
        this.outcomes = outcomes;
        this.debitWallet = debitWallet;
        this.creditWallet = creditWallet;
        this.reverseDebit = reverseDebit;
    }

    @RabbitListener(queues = QUEUE)
    public void onMessage(Message message, Channel channel) throws IOException {
        try (MessageLogContext logContext = MessageLogContext.open(message)) {
            outcomes.process(message, channel, QUEUE, () -> dispatch(message, logContext));
        }
    }

    // readInbound returns the payload class that matches the type, so the casts are safe.
    @SuppressWarnings("unchecked")
    private void dispatch(Message message, MessageLogContext logContext) {
        MessageEnvelope<?> envelope = json.readInbound(new String(message.getBody(), StandardCharsets.UTF_8));
        logContext.fill(envelope);
        switch (envelope.type()) {
            case DEBIT_WALLET -> debitWallet.handle((MessageEnvelope<DebitWallet>) envelope);
            case CREDIT_WALLET -> creditWallet.handle((MessageEnvelope<CreditWallet>) envelope);
            case REVERSE_DEBIT -> reverseDebit.handle((MessageEnvelope<ReverseDebit>) envelope);
            default -> throw new ContractViolationException(
                    envelope.type().contractName() + " does not belong to " + QUEUE);
        }
    }
}
