package co.com.finova.notification.model.notification;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RecipientContactTest {

    private static final UUID USER = UUID.randomUUID();

    @Test
    void returnsTheAddressOfEachChannel() {
        RecipientContact contact = new RecipientContact(USER, "a@finova.test", "+573000000001", "token-a",
                Channel.EMAIL);

        assertThat(contact.addressFor(Channel.EMAIL)).contains("a@finova.test");
        assertThat(contact.addressFor(Channel.SMS)).contains("+573000000001");
        assertThat(contact.addressFor(Channel.PUSH)).contains("token-a");
    }

    @Test
    void isEmptyWhenTheChannelHasNoAddress() {
        RecipientContact contact = new RecipientContact(USER, "a@finova.test", null, "  ", Channel.PUSH);

        assertThat(contact.addressFor(Channel.SMS)).isEmpty();
        assertThat(contact.addressFor(Channel.PUSH)).isEmpty();
    }
}
