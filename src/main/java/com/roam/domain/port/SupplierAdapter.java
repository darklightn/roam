package com.roam.domain.port;

import com.roam.domain.shared.FailureReason;
import com.roam.domain.shared.Money;
import com.roam.domain.workflow.IntentType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Supplier boundary modelled on Tuniu's flight, hotel, and train MCP capabilities. */
public interface SupplierAdapter {

    List<SupplierOffer> search(SearchRequest request);

    QuoteCheck reprice(String supplierQuoteRef);

    SupplierOrderResult createOrder(SupplierOrderCommand command);

    SupplierCancelResult cancelOrder(SupplierCancelCommand command);

    SupplierOrderDetail orderDetail(String supplierOrderId, IntentType type);

    record SearchRequest(
            IntentType type,
            String origin,
            String destination,
            LocalDate startDate,
            LocalDate endDate) {}

    record SupplierOffer(
            String offerId,
            String supplierRef,
            String description,
            int durationMinutes,
            int preferenceScore,
            Money price,
            Instant validUntil,
            Map<String, String> supplierRefs) {}

    record QuoteCheck(Status status, Money currentPrice, Instant validUntil, String detail) {
        public enum Status {
            STILL_VALID,
            EXPIRED,
            CHANGED
        }
    }

    record SupplierOrderCommand(
            String orderId,
            IntentType type,
            String quoteRef,
            Money price,
            Map<String, String> supplierRefs,
            String sessionId,
            String intentId) {}

    record SupplierOrderResult(
            boolean success,
            String supplierOrderId,
            Map<String, String> rawReferences,
            FailureReason failure,
            boolean uncertain) {

        public static SupplierOrderResult success(
                String supplierOrderId, Map<String, String> rawReferences) {
            return new SupplierOrderResult(true, supplierOrderId, rawReferences, null, false);
        }
    }

    record SupplierCancelCommand(
            String orderId, IntentType type, String supplierOrderId, boolean paid) {}

    record SupplierCancelResult(
            boolean success, boolean alreadyCancelled, FailureReason failure) {

        public static SupplierCancelResult cancelled(boolean already) {
            return new SupplierCancelResult(true, already, null);
        }
    }

    record SupplierOrderDetail(
            String supplierOrderId, String orderStatus, String paymentStatus, Instant observedAt) {}

    /** A timeout may have created a supplier-side order whose identifier we never received. */
    class SupplierTimeoutException extends RuntimeException {
        private final boolean mayHaveSucceeded;

        public SupplierTimeoutException(String message, boolean mayHaveSucceeded) {
            super(message);
            this.mayHaveSucceeded = mayHaveSucceeded;
        }

        public boolean mayHaveSucceeded() {
            return mayHaveSucceeded;
        }
    }
}
