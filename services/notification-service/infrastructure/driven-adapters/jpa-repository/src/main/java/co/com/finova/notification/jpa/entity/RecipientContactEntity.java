package co.com.finova.notification.jpa.entity;

import co.com.finova.notification.model.notification.Channel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "recipient_contacts")
@Getter
@Setter
@NoArgsConstructor
public class RecipientContactEntity {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "phone")
    private String phone;

    @Column(name = "push_token")
    private String pushToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "preferred_channel", nullable = false)
    private Channel preferredChannel;
}
