package com.example.ttsreader.service;

import com.example.ttsreader.config.TtsProperties;
import com.example.ttsreader.service.http.HttpTtsClient;
import com.example.ttsreader.service.mock.MockAudioGenerator;
import com.example.ttsreader.service.mock.MockTtsClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class TtsClientConfiguration {

    @Bean
    public TtsClient ttsClient(TtsProperties properties) {
        if ("http".equalsIgnoreCase(properties.mode())) {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofSeconds(properties.http().connectTimeoutSeconds()));
            factory.setReadTimeout(Duration.ofSeconds(properties.http().readTimeoutSeconds()));
            RestClient restClient = RestClient.builder()
                    .requestFactory(factory)
                    .baseUrl(properties.http().baseUrl())
                    .build();
            return new HttpTtsClient(restClient, properties);
        }
        return new MockTtsClient(properties, new MockAudioGenerator(properties.mock()));
    }
}
