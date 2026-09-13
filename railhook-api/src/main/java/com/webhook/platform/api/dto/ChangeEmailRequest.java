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
 * A new address for the signed-in account.
 *
 * <p>Which of the other two fields matters depends on the account. An unverified account proves
 * nothing by knowing its password — whoever registered it chose that — so it answers the same
 * CAPTCHA registration asks for. A verified account re-enters its password, or, when it has none
 * because it signs in with Google, has signed in within the last few minutes.
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
