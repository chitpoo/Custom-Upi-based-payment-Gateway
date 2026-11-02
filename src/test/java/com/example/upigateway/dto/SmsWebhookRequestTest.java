package com.example.upigateway.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

// Forwarder apps name their JSON fields differently; the webhook must accept them all.
class SmsWebhookRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsPrimaryFieldNames() throws Exception {
        SmsWebhookRequest request = objectMapper.readValue(
                "{\"from\":\"VM-HDFCBK\",\"text\":\"Rs.100.37 credited\"}", SmsWebhookRequest.class);
        assertEquals("VM-HDFCBK", request.from());
        assertEquals("Rs.100.37 credited", request.text());
    }

    @Test
    void acceptsAliasFieldNames() throws Exception {
        SmsWebhookRequest request = objectMapper.readValue(
                "{\"sender\":\"VM-HDFCBK\",\"message\":\"Rs.100.37 credited\"}", SmsWebhookRequest.class);
        assertEquals("VM-HDFCBK", request.from());
        assertEquals("Rs.100.37 credited", request.text());

        request = objectMapper.readValue(
                "{\"address\":\"VM-HDFCBK\",\"body\":\"Rs.100.37 credited\"}", SmsWebhookRequest.class);
        assertEquals("VM-HDFCBK", request.from());
        assertEquals("Rs.100.37 credited", request.text());

        request = objectMapper.readValue(
                "{\"number\":\"+911234567890\",\"sms\":\"Rs.100.37 credited\"}", SmsWebhookRequest.class);
        assertEquals("+911234567890", request.from());
        assertEquals("Rs.100.37 credited", request.text());
    }

    @Test
    void senderIsOptional() throws Exception {
        SmsWebhookRequest request = objectMapper.readValue(
                "{\"text\":\"Rs.100.37 credited\"}", SmsWebhookRequest.class);
        assertNull(request.from());
        assertEquals("Rs.100.37 credited", request.text());
    }

    @Test
    void lenientParseHandlesValidJson() {
        SmsWebhookRequest request = SmsWebhookRequest.lenientParse(
                "{\"from\":\"VM-HDFCBK\",\"text\":\"Rs.100.37 credited\"}", objectMapper);
        assertEquals("VM-HDFCBK", request.from());
        assertEquals("Rs.100.37 credited", request.text());
    }

    @Test
    void lenientParseHandlesRawQuotesNewlinesAndBackslash() {
        // MacroDroid pastes the SMS raw: this is NOT valid JSON (raw " and newline).
        SmsWebhookRequest request = SmsWebhookRequest.lenientParse(
                "{\"from\":\"VM-HDFCBK\",\"text\":\"Rs.100.37 \"credited\" to you\nUTR 482910573641\"}",
                objectMapper);
        assertEquals("VM-HDFCBK", request.from());
        assertEquals("Rs.100.37 \"credited\" to you UTR 482910573641", request.text());

        request = SmsWebhookRequest.lenientParse(
                "{\"from\":\"VM-HDFCBK\",\"text\":\"UPI\\Ref 482910573641\"}", objectMapper);
        assertEquals("UPI\\Ref 482910573641", request.text());
    }

    @Test
    void lenientParseHandlesAliasKeysWithRawContent() {
        SmsWebhookRequest request = SmsWebhookRequest.lenientParse(
                "{\"sender\":\"VM-HDFCBK\",\"message\":\"Rs.100.37 \"credited\"\"}", objectMapper);
        assertEquals("VM-HDFCBK", request.from());
        assertEquals("Rs.100.37 \"credited\"", request.text());
    }

    @Test
    void lenientParseTreatsPlainTextBodyAsSms() {
        SmsWebhookRequest request = SmsWebhookRequest.lenientParse(
                "Rs.100.37 credited via UTR 482910573641", objectMapper);
        assertNull(request.from());
        assertEquals("Rs.100.37 credited via UTR 482910573641", request.text());
    }

    @Test
    void lenientParseReturnsNullForMissingBody() {
        assertNull(SmsWebhookRequest.lenientParse(null, objectMapper));
        assertNull(SmsWebhookRequest.lenientParse("   ", objectMapper));
    }
}
