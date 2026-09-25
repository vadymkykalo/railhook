package com.webhook.platform.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PublicContactRequest {

    /** Used only as Reply-To, never as a recipient. */
    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    @Size(max = 254, message = "Email must be at most 254 characters")
    private String email;

    @Size(max = 100, message = "Name must be at most 100 characters")
    private String name;

    /** Empty means other. */
    @Pattern(regexp = "^(sales|support|other)?$", message = "Topic must be sales, support or other")
    private String topic;

    @NotBlank(message = "Message is required")
    @Size(max = 5000, message = "Message must be at most 5000 characters")
    private String message;

    @Size(max = 300, message = "Page must be at most 300 characters")
    private String page;

    private String captchaToken;
}
