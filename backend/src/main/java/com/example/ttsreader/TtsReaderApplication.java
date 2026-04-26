package com.example.ttsreader;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TtsReaderApplication {

    public static void main(String[] args) {
        SpringApplication.run(TtsReaderApplication.class, args);
    }
}
