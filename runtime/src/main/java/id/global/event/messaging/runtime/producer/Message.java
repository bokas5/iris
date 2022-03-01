package id.global.event.messaging.runtime.producer;

import com.rabbitmq.client.AMQP;

import id.global.common.annotations.amqp.Scope;

public record Message(Object message, String exchange, String routingKey, Scope scope, String userId,
        AMQP.BasicProperties properties) {
}
