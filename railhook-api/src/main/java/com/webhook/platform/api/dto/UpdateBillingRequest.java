package com.webhook.platform.api.dto;

import com.webhook.platform.api.dto.validation.DeliverableEmail;
import jakarta.validation.constraints.Email;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateBillingRequest {
    @Email
    @DeliverableEmail
    private String billingEmail;
}
