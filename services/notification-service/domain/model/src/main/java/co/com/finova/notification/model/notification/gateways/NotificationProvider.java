package co.com.finova.notification.model.notification.gateways;

import co.com.finova.notification.model.notification.Notification;

/**
 * Output port: the external Email / SMS / Push provider (spec §1.1). Only Notification talks to it.
 */
public interface NotificationProvider {

    /**
     * Hands the notification to the provider.
     *
     * @throws co.com.finova.notification.model.exceptions.NotificationDeliveryException
     *         if the provider did not accept it (down, timeout or error answer)
     */
    void send(Notification notification);
}
