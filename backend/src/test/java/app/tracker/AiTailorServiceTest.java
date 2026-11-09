package app.tracker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AiTailorServiceTest {
    private final ObjectMapper json = new ObjectMapper();

    private String message(String code, String type) {
        var error = json.createObjectNode().put("code", code).put("type", type)
                .put("message", "private provider detail: dummy-secret");
        return AiTailorService.failureMessage(json, 429, json.createObjectNode().set("error", error).toString());
    }

    @Test void exhaustedCreditsExplainTheBillingActionInsteadOfSuggestingRetries() {
        String result = message("credit_balance_exhausted", "insufficient_quota");
        assertTrue(result.contains("no OpenAI API credits"));
        assertTrue(result.contains("app owner"));
        assertFalse(result.contains("trying again"));
        assertFalse(result.contains("dummy-secret"));
    }

    @Test void specificSpendingAndUsageLimitsTakePrecedenceOverTheBroadQuotaType() {
        for (String code : new String[]{"organization_spend_limit_exceeded", "project_spend_limit_exceeded"}) {
            String result = message(code, "insufficient_quota");
            assertTrue(result.contains("spending limit"));
            assertFalse(result.contains("add credits"));
        }
        assertTrue(message("organization_usage_limit_exceeded", "insufficient_quota").contains("higher limit"));
    }

    @Test void legacyAndUnspecifiedQuotaErrorsAskTheOwnerToCheckBillingAndLimits() {
        for (String code : new String[]{"insufficient_quota", "usage_limit_exceeded", ""}) {
            String result = message(code, "insufficient_quota");
            assertTrue(result.contains("billing and limits"));
            assertFalse(result.contains("trying again"));
        }
    }

    @Test void temporaryRateLimitsSuggestWaitingWithoutClaimingTheBalanceIsEmpty() {
        for (String code : new String[]{"rate_limit_exceeded", "slow_down", ""}) {
            String result = message(code, "rate_limit_error");
            assertTrue(result.contains("wait"));
            assertFalse(result.contains("credits"));
            assertFalse(result.contains("dummy-secret"));
        }
    }

    @Test void unknownAndMalformedErrorsDoNotExposeProviderBodiesOrAssumeACause() {
        for (String body : new String[]{null, "", "private provider detail: dummy-secret",
                "{\"error\":{\"code\":\"unknown\",\"message\":\"dummy-secret\"}}"}) {
            String result = AiTailorService.failureMessage(json, 429, body);
            assertTrue(result.contains("check OpenAI billing and rate limits"));
            assertFalse(result.contains("dummy-secret"));
            assertFalse(result.contains("no OpenAI API credits"));
        }
    }

    @Test void otherHttpErrorsKeepTheirStatusWithoutExposingProviderDetails() {
        assertEquals("OpenAI returned HTTP 401", AiTailorService.failureMessage(json, 401,
                "{\"error\":{\"message\":\"dummy-secret\"}}"));
    }
}
