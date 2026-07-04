package com.anvil.ai;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.spring.AiService;

@AiService
public interface Assistant {

    @SystemMessage("You are Anvil, a helpful assistant. Answer questions clearly and concisely. " +
            "If retrieved context is provided, ground your answer in it.")
    String chat(String message);
}
