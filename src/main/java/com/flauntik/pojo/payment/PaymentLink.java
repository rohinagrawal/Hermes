package com.flauntik.pojo.payment;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PaymentLink {
    private String referenceId;
    private String url;
}
