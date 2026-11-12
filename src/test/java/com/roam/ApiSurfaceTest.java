package com.roam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roam.domain.booking.BookingOrder;
import com.roam.domain.shared.Role;
import com.roam.infrastructure.supplier.CallbackSignatures;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The HTTP surface must enforce access and redaction like every other exposed surface. */
@AutoConfigureMockMvc
class ApiSurfaceTest extends AcceptanceTestBase {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private String intentPackageBody(String sessionId) throws Exception {
        Map<String, Object> body = Map.of(
                "packageId", "pkg-" + sessionId,
                "sessionId", sessionId,
                "contractVersion", "1.0",
                "tripRequest", Map.of(
                        "travelerName", "Li Wei",
                        "travelerIdNumber", "110101199001011234",
                        "origin", "Beijing",
                        "stops", List.of(Map.of(
                                "city", "Shanghai",
                                "arrivalDate", "2026-12-01",
                                "departureDate", "2026-12-03")),
                        "oneWay", false,
                        "tripReason", "Client meeting"),
                "intents", List.of(Map.of(
                        "id", "flight-1",
                        "type", "FLIGHT",
                        "origin", "Beijing",
                        "destination", "Shanghai",
                        "startDate", "2026-12-01",
                        "endDate", "2026-12-03",
                        "dependsOn", List.of(),
                        "attributes", Map.of())));
        return objectMapper.writeValueAsString(body);
    }

    private JsonNode executeViaHttp(String sessionId) throws Exception {
        String body = intentPackageBody(sessionId);
        String prepared = mockMvc.perform(post("/api/intent-packages/prepare")
                        .header("X-User-Id", "emp-1")
                        .header("X-Role", "EMPLOYEE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode preparation = objectMapper.readTree(prepared);
        String planId = preparation.get("planId").asText();
        String workflowId = preparation.get("workflowId").asText();

        String planJson = mockMvc.perform(get("/api/trip-requests/{id}/plan",
                        preparation.get("tripRequestId").asText())
                        .header("X-User-Id", "emp-1")
                        .header("X-Role", "EMPLOYEE"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode option = objectMapper.readTree(planJson).get("options").get(0);
        String selection = objectMapper.writeValueAsString(Map.of(
                "intentId", "flight-1",
                "optionId", option.get("id").asText(),
                "quoteId", option.get("quoteId").asText()));
        mockMvc.perform(post("/api/plans/{id}/selection", planId)
                        .header("X-User-Id", "emp-1")
                        .header("X-Role", "EMPLOYEE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selection))
                .andExpect(status().isOk());

        String executed = mockMvc.perform(post("/api/workflows/{id}/execute", workflowId)
                        .header("X-User-Id", "emp-1")
                        .header("X-Role", "EMPLOYEE"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(executed);
    }

    @Test
    void httpSurfaceRedactsIdentityPaymentLinksAndEnforcesRoleAccess() throws Exception {
        JsonNode result = executeViaHttp("session-api");
        String tripId = result.get("tripRequestId").asText();
        assertThat(result.get("workflowStatus").asText()).isEqualTo("COMPLETED");

        String tripJson = mockMvc.perform(get("/api/trip-requests/{id}", tripId)
                        .header("X-User-Id", "emp-1")
                        .header("X-Role", "EMPLOYEE"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(tripJson).doesNotContain("110101199001011234");
        assertThat(tripJson).contains("110***********1234");

        String planJson = mockMvc.perform(get("/api/trip-requests/{id}/plan", tripId)
                        .header("X-User-Id", "emp-1")
                        .header("X-Role", "EMPLOYEE"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(planJson).doesNotContain("cabinPriceId");
        assertThat(planJson).contains("OVERALL_BEST");

        BookingOrder order = store.orders().values().iterator().next();
        String orderJson = mockMvc.perform(get("/api/orders/{id}", order.id())
                        .header("X-User-Id", "emp-1")
                        .header("X-Role", "EMPLOYEE"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(orderJson).doesNotContain("https://pay.tuniu.com");
        assertThat(orderJson).doesNotContain("110101199001011234");
        assertThat(orderJson).contains("opaque:payment-link:");

        String auditJson = mockMvc.perform(get("/api/admin/audit/{id}", tripId)
                        .header("X-User-Id", "admin-1")
                        .header("X-Role", "ADMINISTRATOR"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(auditJson).doesNotContain("110101199001011234");
        assertThat(auditJson).doesNotContain("https://pay.tuniu.com");

        mockMvc.perform(get("/api/trip-requests/{id}", tripId)
                        .header("X-User-Id", "emp-2")
                        .header("X-Role", "EMPLOYEE"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/metrics")
                        .header("X-User-Id", "emp-1")
                        .header("X-Role", "EMPLOYEE"))
                .andExpect(status().isForbidden());
    }

    @Test
    void callbackEndpointValidatesQuietlyAndDoesNotChangeOrderState() throws Exception {
        executeViaHttp("session-api-callback");
        BookingOrder order = store.orders().values().iterator().next();
        String callbackBody = objectMapper.writeValueAsString(Map.of(
                "callbackId", "cb-api",
                "supplier", "TUNIU",
                "supplierOrderId", order.supplierOrderId(),
                "paymentStatus", "PAID",
                "amount", new BigDecimal("1200.00"),
                "currency", "CNY",
                "signature", CallbackSignatures.sign("cb-api"),
                "payload", Map.of("email", "li.wei@example.com", "phone", "13812345678")));

        String response = mockMvc.perform(post("/api/supplier/payment-callbacks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(callbackBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).contains("RECEIVED");
        assertThat(response).doesNotContain("li.wei@example.com");
        assertThat(response).doesNotContain("13812345678");
        assertThat(order.state().name()).isEqualTo("SUBMITTED");
        assertThat(order.paidObserved()).isTrue();
    }

    @Test
    void unsupportedContractVersionFailsExplicitly() throws Exception {
        String body = intentPackageBody("session-contract")
                .replace("\"contractVersion\":\"1.0\"", "\"contractVersion\":\"2.0\"");

        String response = mockMvc.perform(post("/api/intent-packages")
                        .header("X-User-Id", "emp-1")
                        .header("X-Role", "EMPLOYEE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).contains("contract_error");
    }
}
