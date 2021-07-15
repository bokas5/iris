package id.global.event.messaging.runtime.consumer;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import javax.enterprise.context.ApplicationScoped;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import id.global.event.messaging.runtime.configuration.AmqpConfiguration;
import id.global.event.messaging.runtime.context.AmqpContext;
import id.global.event.messaging.runtime.context.MethodHandleContext;

@ApplicationScoped
public class AmqpConsumerContainer {
    private static final Logger LOG = LoggerFactory.getLogger(AmqpConsumerContainer.class);
    private Connection connection;
    private final ObjectMapper objectMapper;
    private final AmqpConfiguration config;

    private int retryCount = 0;

    private final Map<String, AmqpConsumer> consumerMap;

    public AmqpConsumerContainer(AmqpConfiguration config, ObjectMapper objectMapper) {
        consumerMap = new HashMap<>();

        this.config = config;
        this.objectMapper = objectMapper;
    }

    public void initConsumer() {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(config.getUrl());
        factory.setPort(config.getPort());

        if (config.isAuthenticated()) {
            factory.setUsername(config.getUsername());
            factory.setPassword(config.getPassword());
        }

        while (!createConnection(factory)) {
            retryCount++;
            if (retryCount >= 10) {
                break;
            }
            try {
                Thread.sleep(retryCount * 500L);
            } catch (InterruptedException e) {
                LOG.error("Wait for retry interrupted", e);
            }
        }
    }

    private boolean createConnection(ConnectionFactory factory) {
        try {
            connection = factory.newConnection();

            if (!consumerMap.isEmpty()) {
                consumerMap.forEach((queueName, consumer) -> {
                    try {
                        consumer.initChannel(connection);
                    } catch (IOException e) {
                        LOG.error("Exception initializing consumer channel", e);
                    }
                });
            }
            return true;
        } catch (IOException | TimeoutException e) {
            LOG.error("Exception while initializing consumers", e);
            return false;
        }
    }

    public void addConsumer(MethodHandle methodHandle, MethodHandleContext methodHandleContext, AmqpContext amqpContext,
            Object eventHandlerInstance) {
        consumerMap.put(UUID.randomUUID().toString(), new AmqpConsumer(
                methodHandle,
                methodHandleContext,
                amqpContext,
                eventHandlerInstance,
                objectMapper));
    }

    public int getNumOfConsumers() {
        return consumerMap.size();
    }
}
