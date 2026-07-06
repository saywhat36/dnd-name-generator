package com.dndnamegen.namegen.config;

import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

@Configuration
public class PromptTemplateConfig {

    /**
     * Kept in sync with the template's filename (see docs/DECISIONS.md,
     * "Prompt version encoded in template filename") so generation_log.prompt_version
     * always reflects the prompt that was actually sent.
     */
    public static final String NAME_GENERATION_PROMPT_VERSION = "v1";

    @Bean
    public PromptTemplate nameGenerationPromptTemplate(
            @Value("classpath:/prompts/name-generation-v1.st") Resource nameGenerationPromptResource) {
        return PromptTemplate.builder().resource(nameGenerationPromptResource).build();
    }
}
