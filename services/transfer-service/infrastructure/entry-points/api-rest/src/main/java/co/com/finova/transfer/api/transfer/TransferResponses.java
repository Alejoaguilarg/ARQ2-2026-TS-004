package co.com.finova.transfer.api.transfer;

import co.com.finova.transfer.model.transfer.Transfer;

/** The bodies of spec §3.8 (202) and §3.9 (TransferView: no key, hash, attempts, correlationId or command data). */
final class TransferResponses {

    private TransferResponses() {
    }

    record Accepted(String transferId, String status, String statusUrl, String createdAt) {

        static Accepted of(Transfer transfer) {
            return new Accepted(transfer.transferId().toString(), transfer.status().name(), url(transfer),
                    transfer.createdAt().toString());
        }
    }

    record View(String transferId, String senderUserId, String recipientUserId, String amount, String currency,
                String description, String status, String failureReason, String createdAt, String updatedAt,
                String completedAt) {

        static View of(Transfer t) {
            return new View(t.transferId().toString(), t.senderUserId().toString(), t.recipientUserId().toString(),
                    t.amount().toString(), t.currency().name(), t.description(), t.status().name(),
                    t.failureReason() == null ? null : t.failureReason().name(), t.createdAt().toString(),
                    t.updatedAt().toString(), t.completedAt() == null ? null : t.completedAt().toString());
        }
    }

    static String url(Transfer transfer) {
        return "/transfers/" + transfer.transferId();
    }
}
