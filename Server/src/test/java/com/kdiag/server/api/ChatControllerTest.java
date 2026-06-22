package com.kdiag.server.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

import com.kdiag.server.llm.GptChatClient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ChatControllerTest {

    @Autowired
    MockMvc mvc;

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        GptChatClient gptChatClient() {
      return new GptChatClient("http://localhost:11434/v1", "openai/gpt-oss-120b", "", 0.2, 60L, 0) {
                @Override
                public String chat(java.util.List<java.util.Map<String, String>> messages) {
                    return "OK_FROM_GPT";
                }
            };
        }
    }

    @Test
    void chat_happyPath_generatesConversationIdAndReply() throws Exception {

        String body = """
                {
                  \"protocol_version\": \"kdiag/1.0\",
                  \"message\": { \"role\": \"user\", \"text\": \"My pod is in CrashLoopBackOff\" },
                  \"artifacts\": [
                    { \"type\": \"events\", \"content\": \"Warning  BackOff  CrashLoopBackOff\" }
                  ]
                }
                """;

        mvc.perform(post("/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.protocol_version").value("kdiag/1.0"))
                .andExpect(jsonPath("$.conversation_id").isNotEmpty())
                .andExpect(jsonPath("$.assistant_message.text").value("OK_FROM_GPT"));
    }

    @Test
    void chat_rejectsWrongProtocol() throws Exception {
        String body = """
                {
                  \"protocol_version\": \"kdiag/0.9\",
                  \"message\": { \"role\": \"user\", \"text\": \"Hello\" }
                }
                """;

        mvc.perform(post("/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }
}
