package com.flauntik.pojo.payment;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PaymentRequest {
    private String tenantId;
    private String userId;
    private String amount;
    private String currency;
    private String description;
}
