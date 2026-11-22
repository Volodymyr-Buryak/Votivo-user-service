package com.votivo.user_service.config;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.identity", name = "sync-enabled", havingValue = "true", matchIfMissing = true)
public class RabbitConfiguration {
    public static final String EXCHANGE = "identity.users.exchange";
    public static final String QUEUE = "user-service.identity.users";
    public static final String DEAD_LETTER_EXCHANGE = "identity.users.dlx";
    public static final String DEAD_LETTER_QUEUE = "user-service.identity.users.dlq";

    @Bean
    Declarables identityUserTopology(IdentityProperties properties) {
        var exchange = new TopicExchange(EXCHANGE, true, false);
        var deadLetters = new TopicExchange(DEAD_LETTER_EXCHANGE, true, false);
        var queue = QueueBuilder.durable(QUEUE).deadLetterExchange(DEAD_LETTER_EXCHANGE).build();
        var deadLetterQueue = QueueBuilder.durable(DEAD_LETTER_QUEUE).build();

        return new Declarables(exchange, deadLetters, queue, deadLetterQueue,
                BindingBuilder.bind(queue).to(exchange).with(clientPrefix(properties.realm()) + "*.*"),
                BindingBuilder.bind(queue).to(exchange).with(adminPrefix(properties.realm()) + "*.*"),
                BindingBuilder.bind(deadLetterQueue).to(deadLetters).with("#")
        );
    }

    public static String clientPrefix(String realm) {
        return "KK.EVENT.CLIENT." + realm.replace(".", "") + ".SUCCESS.";
    }

    public static String adminPrefix(String realm) {
        return "KK.EVENT.ADMIN." + realm.replace(".", "") + ".SUCCESS.";
    }

}
