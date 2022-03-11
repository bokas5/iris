package id.global.event.messaging.runtime.context;

import static id.global.common.headers.amqp.MessagingHeaders.Message.SESSION_ID;
import static id.global.common.headers.amqp.MessagingHeaders.Message.USER_ID;
import static id.global.common.headers.amqp.MessagingHeaders.RequeueMessage.X_RETRY_COUNT;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import javax.enterprise.context.ApplicationScoped;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Envelope;

@ApplicationScoped
public class EventContext {
    private final ThreadLocal<EventContextHolder> eventContextThreadLocal;

    public EventContext() {
        this.eventContextThreadLocal = new ThreadLocal<>();
        this.eventContextThreadLocal.set(new EventContextHolder());
    }

    public void setMessageContext(AMQP.BasicProperties properties, Envelope envelope) {
        EventContextHolder eventContextHolder = this.eventContextThreadLocal.get();
        if (eventContextHolder == null) {
            eventContextHolder = new EventContextHolder();
        }
        eventContextHolder.setAmqpBasicProperties(properties);
        eventContextHolder.setEnvelope(envelope);
        this.eventContextThreadLocal.set(eventContextHolder);
    }

    public AMQP.BasicProperties getAmqpBasicProperties() {
        EventContextHolder eventContextHolder = this.eventContextThreadLocal.get();
        if (eventContextHolder == null) {
            return null;
        }
        return eventContextHolder.getAmqpBasicProperties();
    }

    public Envelope getEnvelope() {
        EventContextHolder eventContextHolder = this.eventContextThreadLocal.get();
        if (eventContextHolder == null) {
            return null;
        }
        return eventContextHolder.getEnvelope();
    }

    public String getExchange() {
        return Optional.ofNullable(getEnvelope()).map(Envelope::getExchange).orElse(null);
    }

    public String getRoutingKey() {
        return Optional.ofNullable(getEnvelope()).map(Envelope::getRoutingKey).orElse(null);
    }

    public Map<String, Object> getHeaders() {
        return Optional.ofNullable(this.eventContextThreadLocal.get())
                .map(EventContextHolder::getAmqpBasicProperties)
                .map(AMQP.BasicProperties::getHeaders)
                .orElse(Collections.emptyMap());
    }

    public Optional<String> getCorrelationId() {
        return Optional.ofNullable(this.eventContextThreadLocal.get())
                .map(EventContextHolder::getAmqpBasicProperties)
                .map(AMQP.BasicProperties::getCorrelationId);
    }

    public Optional<String> getUserId() {
        return getHeaderValue(USER_ID);
    }

    public Optional<String> getSessionId() {
        return getHeaderValue(SESSION_ID);
    }

    public Integer getRetryCount() {
        return getHeaderValue(X_RETRY_COUNT).map(Integer::valueOf).orElse(0);
    }

    private Optional<String> getHeaderValue(final String header) {
        return Optional.ofNullable(this.eventContextThreadLocal.get())
                .map(EventContextHolder::getAmqpBasicProperties)
                .map(AMQP.BasicProperties::getHeaders)
                .filter(headers -> headers.containsKey(header))
                .map(headers -> headers.get(header))
                .map(Object::toString);
    }
}
