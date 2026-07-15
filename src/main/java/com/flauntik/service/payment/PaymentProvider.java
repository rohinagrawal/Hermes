package com.flauntik.service.payment;

import com.flauntik.pojo.payment.PaymentLink;
import com.flauntik.pojo.payment.PaymentRequest;
import io.vertx.core.Future;

/**
 * Abstraction over a real payment gateway (Razorpay, Stripe, ...). Swap the Guice
 * binding in HermesModule to plug in a real provider without touching FlowManager.
 */
public interface PaymentProvider {
    Future<PaymentLink> createPaymentLink(PaymentRequest request);
}
