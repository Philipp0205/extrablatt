package com.kindlerss.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Body of the inbound newsletter webhook. Field names match Postmark's inbound
 * webhook payload (https://postmarkapp.com/developer/webhooks/inbound-webhook),
 * which most inbound e-mail providers can be adapted to with a small forwarding
 * function if they do not already speak it natively.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InboundEmailPayload(
        @JsonProperty("From") String from,
        @JsonProperty("FromName") String fromName,
        @JsonProperty("To") String to,
        @JsonProperty("ToFull") List<Recipient> toFull,
        @JsonProperty("Cc") String cc,
        @JsonProperty("CcFull") List<Recipient> ccFull,
        @JsonProperty("OriginalRecipient") String originalRecipient,
        @JsonProperty("Subject") String subject,
        @JsonProperty("MessageID") String messageId,
        @JsonProperty("Date") String date,
        @JsonProperty("TextBody") String textBody,
        @JsonProperty("HtmlBody") String htmlBody
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Recipient(@JsonProperty("Email") String email, @JsonProperty("Name") String name) {}
}
