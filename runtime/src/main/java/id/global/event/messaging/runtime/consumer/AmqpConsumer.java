package id.global.event.messaging.runtime.consumer;

import static id.global.event.messaging.runtime.Headers.QueueDeclarationHeaders.X_DEAD_LETTER_EXCHANGE;
import static id.global.event.messaging.runtime.Headers.QueueDeclarationHeaders.X_DEAD_LETTER_ROUTING_KEY;
import static id.global.event.messaging.runtime.Headers.QueueDeclarationHeaders.X_MESSAGE_TTL;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DeliverCallback;
import com.rabbitmq.client.Delivery;
import com.rabbitmq.client.ShutdownSignalException;

import id.global.common.annotations.amqp.ExchangeType;
import id.global.common.annotations.amqp.Scope;
import id.global.event.messaging.runtime.InstanceInfoProvider;
import id.global.event.messaging.runtime.channel.ChannelService;
import id.global.event.messaging.runtime.auth.GidAuthenticationRequestContext;
import id.global.event.messaging.runtime.context.AmqpContext;
import id.global.event.messaging.runtime.context.EventContext;
import id.global.event.messaging.runtime.context.MethodHandleContext;
import id.global.event.messaging.runtime.exception.AmqpRuntimeException;
import id.global.event.messaging.runtime.exception.AmqpSendException;
import id.global.event.messaging.runtime.exception.AmqpTransactionException;
import id.global.event.messaging.runtime.exception.AmqpTransactionRuntimeException;
import id.global.event.messaging.runtime.producer.AmqpProducer;
import io.quarkus.arc.Arc;
import io.quarkus.security.identity.request.TokenAuthenticationRequest;
import io.quarkus.smallrye.jwt.runtime.auth.JsonWebTokenCredential;
import io.quarkus.smallrye.jwt.runtime.auth.MpJwtValidator;
import id.global.event.messaging.runtime.requeue.MessageRequeueHandler;
import id.global.event.messaging.runtime.requeue.RetryQueues;

public class AmqpConsumer {
    private static final Logger log = LoggerFactory.getLogger(AmqpConsumer.class);
    private static final int FRONT_MESSAGE_TTL = 15000;
    private static final String FRONTEND_DEAD_LETTER_QUEUE = "dead-letter-frontend";
    public static final String FRONTEND_MESSAGE_EXCHANGE = "frontend";
    private static final String DEAD_LETTER = "dead-letter";
    protected static final String AUTHORIZATION_HEADER = "Authorization";

    private final ObjectMapper objectMapper;
    private final MethodHandle methodHandle;
    private final MethodHandleContext methodHandleContext;
    private final AmqpContext context;
    private final ChannelService channelService;
    private final Object eventHandlerInstance;
    private final EventContext eventContext;
    private final AmqpProducer producer;
    private final InstanceInfoProvider instanceInfoProvider;
    private final MpJwtValidator mpJwtValidator;
    private final DeliverCallback callback;
    private final MessageRequeueHandler retryEnqueuer;
    private final RetryQueues retryQueues;

    private String channelId;

    public AmqpConsumer(
            final ObjectMapper objectMapper,
            final MethodHandle methodHandle,
            final MethodHandleContext methodHandleContext,
            final AmqpContext context,
            final ChannelService channelService,
            final Object eventHandlerInstance,
            final EventContext eventContext,
            final AmqpProducer producer,
            final InstanceInfoProvider instanceInfoProvider,
            final MessageRequeueHandler retryEnqueuer,
            final RetryQueues retryQueues,
            final MpJwtValidator mpJwtValidator) {

        this.objectMapper = objectMapper;
        this.methodHandle = methodHandle;
        this.methodHandleContext = methodHandleContext;
        this.context = context;
        this.channelService = channelService;
        this.eventHandlerInstance = eventHandlerInstance;
        this.eventContext = eventContext;
        this.producer = producer;
        this.instanceInfoProvider = instanceInfoProvider;
        this.mpJwtValidator = mpJwtValidator;
        this.channelId = UUID.randomUUID().toString();
        this.callback = createDeliverCallback();
        this.retryEnqueuer = retryEnqueuer;
        this.retryQueues = retryQueues;
    }

    public void initChannel() throws IOException {
        final var exchangeType = getExchangeType();
        validateBindingKeys(context.getBindingKeys(), exchangeType);
        final var channel = channelService.getOrCreateChannelById(this.channelId);
        createQueues(channel, exchangeType);
    }

    public DeliverCallback getCallback() {
        return callback;
    }

    protected AmqpContext getContext() {
        return context;
    }

    private DeliverCallback createDeliverCallback() {
        return (consumerTag, message) -> {
            final var currentContextMap = MDC.getCopyOfContextMap();
            MDC.clear();
            Channel channel = channelService.getOrCreateChannelById(this.channelId);
            try {
                Arc.container().requestContext().activate();
                final var properties = message.getProperties();
                this.eventContext.setAmqpBasicProperties(properties);
                if (mpJwtValidator != null) {
                    log.info("MpJwtValidator found, trying to validate jwt if present.");
                    final var bearerToken = getBearerToken();
                    bearerToken.ifPresent(token -> {
                        log.info("JWT found...validating: {}", token);
                        final var jwtToken = new TokenAuthenticationRequest(new JsonWebTokenCredential(token));
                        final var authenticationRequestContext = new GidAuthenticationRequestContext();
                        final var await = mpJwtValidator.authenticate(jwtToken, authenticationRequestContext).await().atMost(
                                Duration.ofSeconds(5));
                        log.info("is anonymous {}", await.isAnonymous());
                    });
                }

                final var handlerClassInstance = methodHandleContext.getHandlerClass().cast(eventHandlerInstance);
                final var messageObject = objectMapper.readValue(message.getBody(), methodHandleContext.getEventClass());

                final var invocationResult = methodHandle.invoke(handlerClassInstance, messageObject);

                final var optionalReturnEventClass = Optional.ofNullable(methodHandleContext.getReturnEventClass());
                optionalReturnEventClass.ifPresent(returnEventClass -> forwardMessage(invocationResult, returnEventClass));
                channel.basicAck(message.getEnvelope().getDeliveryTag(), false);
            } catch (Throwable throwable) {
                final var bindingKeysString = Optional.ofNullable(this.context.getBindingKeys())
                        .map(bindingKeys -> "[" + String.join(", ", bindingKeys) + "]")
                        .orElse("[]");
                log.error(String.format("Could not invoke method handler for bindingKey(s) %s", bindingKeysString), throwable);
                handleProcessingFailed(message, channel);
            } finally {
                MDC.setContextMap(currentContextMap);
            }
        };
    }

    private void handleProcessingFailed(Delivery message, Channel channel) throws IOException {
        // get retry count from event context
        int retryCount = eventContext.getRetryCount();

        // TODO move headers..
        if (retryCount >= retryQueues.getMaxRetryCount()) {
            channel.basicNack(message.getEnvelope().getDeliveryTag(), false, false);
        } else {
            channel.basicAck(message.getEnvelope().getDeliveryTag(), false);
            // Requeue with backoff
            retryEnqueuer.enqueueWithBackoff(message, retryCount);
        }
    }

    private void forwardMessage(final Object invocationResult, final Class<?> returnEventClass) {
        final var returnClassInstance = returnEventClass.cast(invocationResult);
        try {
            producer.send(returnClassInstance);
        } catch (AmqpSendException e) {
            log.error("Exception forwarding event.", e);
            throw new AmqpRuntimeException("Exception forwarding event.", e);
        } catch (AmqpTransactionException e) {
            log.error("Exception completing send transaction when sending forwarded event.", e);
            throw new AmqpTransactionRuntimeException("Exception completing send transaction when sending forwarded event.", e);
        }
    }

    private void createQueues(Channel channel, final ExchangeType exchangeType) throws IOException {
        final Map<String, Object> queueDeclarationArgs = new HashMap<>();
        final var exchange = isFrontendMessage() ? FRONTEND_MESSAGE_EXCHANGE : context.getName();
        final boolean consumerOnEveryInstance = context.isConsumerOnEveryInstance();
        final var queueName = getQueueName(context.getName(), exchangeType, consumerOnEveryInstance);

        // set prefetch count "quality of service"
        final int prefetchCount = context.getPrefetch();
        channel.basicQos(prefetchCount);

        // time to leave of queue
        final long ttl = getTtl();
        if (ttl >= 0) {
            queueDeclarationArgs.put(X_MESSAGE_TTL, ttl);
        }

        // setup dead letter queue
        final var deadLetterQueue = getDeadLetterQueueName();
        if (!deadLetterQueue.isBlank()) {
            final var deadLetterQueuePrefixed = "dead." + deadLetterQueue;
            declareAndBindDeadLetterQueue(channel, deadLetterQueuePrefixed);
            queueDeclarationArgs.put(X_DEAD_LETTER_ROUTING_KEY, "dead." + queueName);
            queueDeclarationArgs.put(X_DEAD_LETTER_EXCHANGE, deadLetterQueuePrefixed);
        }

        // declare queue & exchange
        declareQueue(channel, consumerOnEveryInstance, queueName, queueDeclarationArgs);
        declareExchange(channel, exchange, exchangeType);

        // bind queues
        final var bindingKeys = getBindingKeys(exchangeType);
        for (String bindingKey : bindingKeys) {
            channel.queueBind(queueName, exchange, bindingKey);
        }

        // start consuming
        channel.basicConsume(queueName, false, this.callback,
                consumerTag -> log.warn("Channel canceled for {}", queueName),
                (consumerTag, sig) -> reInitChannel(sig, queueName, consumerTag));

        log.info("consumer started on queue '{}' --> {} binding key(s): {}", queueName, exchange,
                String.join(", ", bindingKeys));
    }

    private void reInitChannel(ShutdownSignalException sig, String queueName, String consumerTag) {
        log.warn("Channel shut down for with signal:{}, queue: {}, consumer: {}", sig, queueName, consumerTag);
        try {
            this.channelService.removeChannel(this.channelId);
            this.channelId = UUID.randomUUID().toString();
            initChannel();
        } catch (IOException e) {
            log.error(String.format("Could not re-initialize channel for queue %s", queueName), e);
        }
    }

    private void declareExchange(final Channel channel, final String exchange, final ExchangeType exchangeType)
            throws IOException {

        if (isFrontendMessage()) {
            channel.exchangeDeclare(exchange, BuiltinExchangeType.TOPIC, false);
        } else {
            final var type = BuiltinExchangeType.valueOf(exchangeType.name());
            channel.exchangeDeclare(exchange, type, true);
        }
    }

    private void declareQueue(final Channel channel, final boolean consumerOnEveryInstance, final String queueName,
            final Map<String, Object> args) throws IOException {

        final boolean durable = getDurable();
        final boolean autoDelete = context.isAutoDelete() && !consumerOnEveryInstance;
        try {
            AMQP.Queue.DeclareOk declareOk = channel.queueDeclare(queueName, durable, false, autoDelete, args);
            log.info("queue: {}, consumers: {}, message count: {}", declareOk.getQueue(), declareOk.getConsumerCount(),
                    declareOk.getMessageCount());
        } catch (IOException e) {
            long msgCount = channel.messageCount(queueName);
            if (msgCount <= 0) {
                channel.queueDelete(queueName, false, true);
                channel.queueDeclare(queueName, durable, false, autoDelete, args);
            } else {
                log.error("The new settings of queue was not set, because was not empty! queue={}", queueName, e);
            }
        }
    }

    private void declareAndBindDeadLetterQueue(final Channel channel, final String deadLetterQueue) throws IOException {
        channel.exchangeDeclare(deadLetterQueue, BuiltinExchangeType.TOPIC, true);
        channel.queueDeclare(deadLetterQueue, true, false, false, null);
        channel.queueBind(deadLetterQueue, deadLetterQueue, "#");
    }

    private String getQueueName(final String name, final ExchangeType exchangeType, final boolean onEveryInstance) {
        final var applicationName = instanceInfoProvider.getApplicationName();
        final var instanceName = instanceInfoProvider.getInstanceName();

        StringBuilder stringBuffer = new StringBuilder()
                .append(applicationName)
                .append(".")
                .append(name);

        if (onEveryInstance && Objects.nonNull(instanceName) && !instanceName.isBlank()) {
            stringBuffer.append(".").append(instanceName);
        }

        if (exchangeType == ExchangeType.DIRECT || exchangeType == ExchangeType.TOPIC) {
            final var bindingKeys = String.join("-", context.getBindingKeys());
            stringBuffer.append(".").append(bindingKeys);
        }

        return stringBuffer.toString();
    }

    private String getDeadLetterQueueName() {
        final var deadLetterQueue = context.getDeadLetterQueue();
        final var isDefaultDeadLetterQueue = deadLetterQueue.trim().equals(DEAD_LETTER);
        if (isFrontendMessage() && isDefaultDeadLetterQueue) {
            return FRONTEND_DEAD_LETTER_QUEUE;
        }

        return deadLetterQueue;
    }

    private List<String> getBindingKeys(final ExchangeType exchangeType) {
        final var name = context.getName();
        if (isFrontendMessage()) {
            return List.of("#." + name);
        }

        return switch (exchangeType) {
            case DIRECT, TOPIC -> context.getBindingKeys();
            case FANOUT -> List.of("#." + name);
        };
    }

    private boolean getDurable() {
        if (isFrontendMessage()) {
            return false;
        }

        return context.isDurable();
    }

    private long getTtl() {
        final var ttl = context.getTtl();
        final var isDefaultTtl = ttl == -1;
        if (isFrontendMessage() && isDefaultTtl) {
            return FRONT_MESSAGE_TTL;
        }

        return ttl;
    }

    private boolean isFrontendMessage() {
        return context.getScope() == Scope.FRONTEND;
    }

    private ExchangeType getExchangeType() {
        return Optional.ofNullable(context.getExchangeType()).orElse(ExchangeType.FANOUT);
    }

    private static void validateBindingKeys(final List<String> bindingKeys, final ExchangeType exchangeType) {
        if (exchangeType == ExchangeType.FANOUT) {
            return;
        }

        if (bindingKeys == null || bindingKeys.size() == 0) {
            throw new IllegalArgumentException("Binding key(s) are required when declaring a "
                    + exchangeType.name() + " type exchange.");
        }

        if (exchangeType == ExchangeType.DIRECT && bindingKeys.size() > 1) {
            throw new IllegalArgumentException("Exactly one binding key is required when declaring a direct type exchange.");
        }
    }

    public Optional<String> getBearerToken() {
        return Optional.ofNullable(eventContext.getHeaders().get(AUTHORIZATION_HEADER))
                .map(Object::toString);
    }
}
