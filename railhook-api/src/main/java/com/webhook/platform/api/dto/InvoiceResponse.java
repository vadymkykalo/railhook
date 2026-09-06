package com.webhook.platform.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceResponse {
    private String id;
    private String status;
    private int amountCents;
    private String currency;
    private String planName;
    private Instant periodStart;
    private Instant periodEnd;
    private Instant paidAt;
    private String invoiceUrl;
}
