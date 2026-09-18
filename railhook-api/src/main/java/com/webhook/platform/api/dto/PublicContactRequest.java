package com.webhook.platform.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A message from the public site's contact form. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PublicContactRequest {

    /** Where the answer goes: the Reply-To of the mail to support, never a recipient. */
    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    @Size(max = 254, message = "Email must be at most 254 characters")
    private String email;

    @Size(max = 100, message = "Name must be at most 100 characters")
    private String name;

    /** What the message is about; empty means other. */
    @Pattern(regexp = "^(sales|support|other)?$", message = "Topic must be sales, support or other")
    private String topic;

    @NotBlank(message = "Message is required")
    @Size(max = 5000, message = "Message must be at most 5000 characters")
    private String message;

    /** The page the form was sent from, so the reader knows what the visitor was looking at. */
    @Size(max = 300, message = "Page must be at most 300 characters")
    private String page;

    /** The challenge answer, when the deployment asks for one. */
    private String captchaToken;
}
