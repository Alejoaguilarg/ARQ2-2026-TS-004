package co.com.finova.transfer.model.message.payload;

import java.util.UUID;

/** A Wallet reply to a saga command (spec §4.4), consumed from transfer.wallet-events.q. */
public interface WalletResult {

    UUID transferId();
}
