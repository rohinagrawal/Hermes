package com.flauntik.service.payment;

import com.flauntik.pojo.payment.PaymentLink;
import com.flauntik.pojo.payment.PaymentRequest;
import com.google.inject.Singleton;
import io.vertx.core.Future;
import lombok.extern.log4j.Log4j2;

import java.util.UUID;

/**
 * Default PaymentProvider binding - generates a fake link so PAYMENT flow steps are
 * fully wired and testable before a real gateway account exists. Replace the
 * HermesModule binding with a real implementation (Razorpay/Stripe) to go live.
 */
@Log4j2
@Singleton
public class MockPaymentProvider implements PaymentProvider {

    @Override
    public Future<PaymentLink> createPaymentLink(PaymentRequest request) {
        String referenceId = UUID.randomUUID().toString();
        String url = "https://pay.mock.local/" + referenceId
                + "?amount=" + request.getAmount()
                + "&currency=" + request.getCurrency();
        log.warn("Using MockPaymentProvider - not a real payment link. tenant={} user={} amount={} {}",
                request.getTenantId(), request.getUserId(), request.getAmount(), request.getCurrency());
        return Future.succeededFuture(new PaymentLink(referenceId, url));
    }
}
