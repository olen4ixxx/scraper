package org.example.flightsearch.app.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration
public class WebClientConfig {

    private static final String USER_AGENT = "azair-collector/1.0";

    @Bean
    public WebClient webClient() {
        HttpClient httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
            .responseTimeout(Duration.ofSeconds(10))
            .doOnConnected(conn -> 
                conn.addHandlerLast(new ReadTimeoutHandler(10, TimeUnit.SECONDS))
                   .addHandlerLast(new WriteTimeoutHandler(10, TimeUnit.SECONDS)));
        
        return WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            // Say who is calling. Some of these endpoints turn away a client that sends no
            // User-Agent or an obviously scripted one - Transavia's airport list answers 403 to
            // both, and 200 to this - so a name is needed either way, and the honest one is the
            // right choice: it identifies the caller instead of imitating a browser, and gives
            // anyone reading their logs something to recognise and get in touch about.
            .defaultHeader("User-Agent", USER_AGENT)
            // Fare responses are small, but a whole route network is not: WizzAir publishes
            // theirs as a single 666KB document, against a 256KB default that fails the request
            // after it has already been fetched successfully.
            .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
            .build();
    }
}
