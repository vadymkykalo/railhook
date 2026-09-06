package com.webhook.platform.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterRequest {
    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 128, message = "Password must be 8-128 characters")
    @Pattern(
            regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&\\-_#^()+=])[A-Za-z\\d@$!%*?&\\-_#^()+=]{8,}$",
            message = "Password must contain at least one uppercase letter, one lowercase letter, one digit, and one special character"
    )
    private String password;

    @Size(max = 255, message = "Full name must be at most 255 characters")
    private String fullName;

    @NotBlank(message = "Organization name is required")
    @Size(min = 2, max = 100, message = "Organization name must be 2-100 characters")
    private String organizationName;

    /**
     * The CAPTCHA widget's token, when the deployment configured one.
     *
     * <p>Not {@code @NotBlank}: a self-hosted instance has no CAPTCHA and its clients send
     * nothing, so requiring it here would break every deployment that does not want one. The
     * requirement belongs where the answer is known — {@code AuthController} asks the verifier,
     * and the verifier for an unconfigured deployment accepts everything.
     */
    @Size(max = 4096)
    private String captchaToken;
}
