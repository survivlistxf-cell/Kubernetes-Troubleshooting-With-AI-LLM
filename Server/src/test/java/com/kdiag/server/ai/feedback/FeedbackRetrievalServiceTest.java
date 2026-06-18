package com.kdiag.server.ai.feedback;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FeedbackRetrievalServiceTest {

    @Test
    void parseSourceUrls_handlesNullBlankAndCapsAtFive() {
        assertEquals(List.of(), FeedbackRetrievalService.parseSourceUrls(null));
        assertEquals(List.of(), FeedbackRetrievalService.parseSourceUrls("   "));

        String sourceUrls = String.join("\n",
                "http://one.example/a",
                "https://two.example/b",
                "ftp://skip.example/c",
                "  http://three.example/d  ",
                "not-a-url",
                "http://four.example/e",
                "http://five.example/f",
                "http://six.example/g");

        assertEquals(List.of(
                "http://one.example/a",
                "https://two.example/b",
                "http://three.example/d",
                "http://four.example/e",
                "http://five.example/f"), FeedbackRetrievalService.parseSourceUrls(sourceUrls));
    }
}