package co.com.finova.notification.consumer;

import co.com.finova.notification.model.exceptions.NotificationDeliveryException;
import co.com.finova.notification.model.notification.Channel;
import co.com.finova.notification.model.notification.Notification;
import co.com.finova.notification.model.notification.TransferEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class HttpNotificationProviderTest {

    private MockRestServiceServer server;
    private HttpNotificationProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://provider");
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new HttpNotificationProvider(builder.build());
    }

    @Test
    void postsTheNotificationToTheEndpointOfItsChannel() {
        Notification notification = notification(Channel.EMAIL, "usuario.a@finova.test");

        server.expect(requestTo("http://provider/email"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", notification.notificationId().toString()))
                .andExpect(header("X-Correlation-Id", "corr-1"))
                .andExpect(jsonPath("$.to").value("usuario.a@finova.test"))
                .andExpect(jsonPath("$.eventType").value("TransferCompleted"))
                .andExpect(jsonPath("$.amount").value("150000.00"))
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        provider.send(notification);

        server.verify();
    }

    @Test
    void usesTheSmsAndPushEndpoints() {
        server.expect(requestTo("http://provider/sms")).andRespond(withStatus(HttpStatus.ACCEPTED));
        server.expect(requestTo("http://provider/push")).andRespond(withStatus(HttpStatus.ACCEPTED));

        provider.send(notification(Channel.SMS, "+573000000003"));
        provider.send(notification(Channel.PUSH, "push-token-usuario-d"));

        server.verify();
    }

    @Test
    void anErrorAnswerIsADeliveryFailure() {
        server.expect(requestTo("http://provider/email")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> provider.send(notification(Channel.EMAIL, "falla@finova.test")))
                .isInstanceOf(NotificationDeliveryException.class);
    }

    private static Notification notification(Channel channel, String to) {
        return new Notification(UUID.randomUUID(), channel, to, TransferEventType.TRANSFER_COMPLETED,
                UUID.randomUUID(), "150000.00", "COP", null, "Asunto", "Mensaje", "corr-1");
    }
}
