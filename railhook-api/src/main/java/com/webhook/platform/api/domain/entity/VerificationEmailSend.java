package com.webhook.platform.api.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/** One mail that asked an address to prove itself, kept only to be counted against the daily cap. */
@Entity
@Table(name = "verification_email_sends")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VerificationEmailSend {

    public static final String REGISTER = "REGISTER";
    public static final String RESEND = "RESEND";
    public static final String EMAIL_CHANGE = "EMAIL_CHANGE";
    public static final String EMAIL_CHANGE_RESEND = "EMAIL_CHANGE_RESEND";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 32)
    private String reason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
