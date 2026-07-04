package com.anvil.web;

import com.anvil.ai.Assistant;
import com.anvil.web.dto.ChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatController.class)
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private Assistant assistant;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void chat_returnsAssistantReply() throws Exception {
        when(assistant.chat("hello")).thenReturn("hi there");

        mockMvc.perform(post("/api/chat")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new ChatRequest("hello"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("hi there"));
    }
}
