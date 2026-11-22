package com.votivo.user_service.config;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;

import static org.junit.jupiter.api.Assertions.*;

class RabbitConfigurationTest {
    @Test
    void topologyIsDurableRealmScopedAndDeadLettered() {
        var topology = new RabbitConfiguration().identityUserTopology(new IdentityProperties(
                "http://identity", "votivo.test", "sync", "secret", 100, Duration.ofHours(1)));
        var exchanges = topology.getDeclarablesByType(TopicExchange.class);
        assertEquals(2, exchanges.size());
        assertTrue(exchanges.stream().allMatch(exchange -> exchange.isDurable() && !exchange.isAutoDelete()));
        var queues = topology.getDeclarablesByType(Queue.class);
        assertEquals(2, queues.size());
        assertTrue(queues.stream().allMatch(Queue::isDurable));
        var events = queues.stream().filter(queue -> queue.getName().equals(RabbitConfiguration.QUEUE))
                .findFirst().orElseThrow();
        assertEquals(RabbitConfiguration.DEAD_LETTER_EXCHANGE, events.getArguments().get("x-dead-letter-exchange"));
        var bindings = topology.getDeclarablesByType(Binding.class);
        assertEquals(3, bindings.size());
        assertTrue(bindings.stream().anyMatch(binding -> binding.getRoutingKey()
                .equals("KK.EVENT.CLIENT.votivotest.SUCCESS.*.*")));
        assertTrue(bindings.stream().anyMatch(binding -> binding.getRoutingKey()
                .equals("KK.EVENT.ADMIN.votivotest.SUCCESS.*.*")));
        assertTrue(bindings.stream().anyMatch(binding -> binding.getRoutingKey().equals("#")
                && binding.getDestination().equals(RabbitConfiguration.DEAD_LETTER_QUEUE)));
    }
}
