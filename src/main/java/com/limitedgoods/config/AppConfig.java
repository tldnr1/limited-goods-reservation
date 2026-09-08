package com.limitedgoods.config;
import java.time.Clock;
import java.time.Duration;
import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
@Configuration
public class AppConfig {
    @Bean public Clock clock() { return Clock.systemUTC(); }
    @Bean public HttpClient pgHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    }
}
