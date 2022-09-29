package id.global.iris.messaging.test;

import static id.global.iris.common.annotations.ExchangeType.DIRECT;

import id.global.iris.common.annotations.Message;

@Message(name = "exchange", exchangeType = DIRECT, routingKey = "event-queue-priority")
public record PriorityQueueEvent(String name, Long age) {
}