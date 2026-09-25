package com.webhook.platform.api.dto;

import com.webhook.platform.api.dto.validation.DeliverableEmail;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * An unverified account answers a CAPTCHA, since knowing its password proves nothing. A verified
 * account re-enters its password, or, with no password, must have signed in recently.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChangeEmailRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    @DeliverableEmail
    @Size(max = 255)
    private String newEmail;

    @ToString.Exclude
    @Size(max = 128)
    private String currentPassword;

    @Size(max = 4096)
    private String captchaToken;
}
