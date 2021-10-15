package id.global.event.messaging.it.sync;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import id.global.asyncapi.spec.annotations.FanoutMessageHandler;
import id.global.asyncapi.spec.annotations.MessageHandler;
import id.global.asyncapi.spec.annotations.ProducedEvent;
import id.global.asyncapi.spec.annotations.TopicMessageHandler;
import id.global.asyncapi.spec.enums.ExchangeType;
import id.global.event.messaging.it.events.Event;
import id.global.event.messaging.it.events.LoggingEvent;
import id.global.event.messaging.runtime.producer.AmqpProducer;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class AnnotatedEventsTestIT {
    // TODO this looks more like it should be a unit test for AmqpProducer.
    /*
     * That means only checking if the produce method returns expected results according to inputs (correctly/incorrectly
     * annotated
     * events, null/non-null message bodies, etc.
     *
     * That requires decoupling all the rabbit ConnectionFactory, Connection, Channel etc. logic from the AmqpProducer so it can
     * be easily mocked.
     *
     * Currently checking of annotationService results will be removed, as this will only test returns from amqpProducer publish
     */

    private static final String ANNOTATED_QUEUE = "annotated-queue";
    private static final String ANNOTATED_EXCHANGE = "annotated-exchange";
    private static final String ANNOTATED_EXCHANGE_FANOUT = "annotated-exchange-fanout";
    private static final String TOPIC_EXCHANGE = "topic-exchange";
    private static final String SOMETHING_NOTHING = "#.nothing";
    private static final String SOMETHING = "something.#";
    private static final String ANNOTATED_QUEUE_FANOUT = "annotated-queue-fanout";
    private static final String EMPTY = "";

    @Inject
    AmqpProducer testProducer;

    @Inject
    AnnotationService annotationService;

    @Inject
    TopicService topicService;

    @BeforeEach
    public void setUp() {
        annotationService.reset();
        topicService.reset();
    }

    @Test
    @DisplayName("Event without annotations should not be published successfully.")
    void publishMessageWithoutAnnotations() {
        boolean isPublished = testProducer.publish(new Event("name", 1L));
        assertThat(isPublished, is(false));
    }

    @Test
    @DisplayName("Published correctly annotated event to DIRECT exchange should succeed")
    void publishDirect() {
        DirectEvent publishedEvent = new DirectEvent("name", 1L);
        boolean isPublished = testProducer.publish(publishedEvent);
        assertThat(isPublished, is(true));
    }

    @Test
    @DisplayName("Published annotated event with missing exchange name to DIRECT exchange should fail")
    void publishDirectMissingExchange() {
        boolean isPublished = testProducer.publish(new DirectEventEmptyExchange("name", 1L));
        assertThat(isPublished, is(false));
    }

    @Test
    @DisplayName("Published annotated event with missing routing key to DIRECT exchange should fail")
    void publishDirectNoRoutingKey() {
        boolean isPublished = testProducer.publish(new DirectEventEmptyRoutingKey("name", 1L));
        assertThat(isPublished, is(false));
    }

    @Test
    @DisplayName("Published correctly annotated event to FANOUT exchange should succeed")
    void publishFanout() {
        boolean isPublished = testProducer.publish(new FanoutEvent("name", 1L));
        assertThat(isPublished, is(true));
    }

    @Test
    @DisplayName("Published annotated event with missing exchange name to FANOUT exchange should fail")
    void publishFanoutMissingExchange() {
        boolean isPublished = testProducer.publish(new FanoutEventWrongEmptyExchange("name", 1L));
        assertThat(isPublished, is(false));
    }

    @Test
    @DisplayName("Published annotated event with routing key to FANOUT exchange should succeed")
    void publishFanoutWithRoutingKey() {
        // Publish should ignore routing key in case of FANOUT exchange
        // TODO check if there is a warning logged in this case
        boolean isPublished = testProducer.publish(new FanoutEventWrongRoutingKey("name", 1L));
        assertThat(isPublished, is(true));
    }

    @Test
    @DisplayName("Published correctly annotated events to TOPIC exchange should succeed")
    void publishTopic() {
        boolean isPublished = testProducer.publish(new TopicEventOne("name_one", 1L)); //will not be received
        boolean isPublishedTwo = testProducer.publish(new TopicEventTwo("name_two", 1L)); //will be received once
        boolean isPublishedThree = testProducer.publish(new TopicEventThree("name_three", 1L)); //will be received twice
        assertThat(isPublished, is(true));
        assertThat(isPublishedTwo, is(true));
        assertThat(isPublishedThree, is(true));
    }

    @Test
    @DisplayName("Published annotated event with missing exchange name to TOPIC exchange should fail")
    void publishTopicMissingExchange() {
        boolean isPublished = testProducer.publish(new TopicEventWrongEmptyExchange("name", 1L));
        assertThat(isPublished, is(false));
    }

    @Test
    @DisplayName("Published annotated event without routing/binding key to TOPIC exchange should fail")
    void publishTopicMissingRoutingKey() {
        boolean isPublished = testProducer.publish(new TopicEventWrongRoutingKey("name", 1L));
        assertThat(isPublished, is(false));
    }

    @SuppressWarnings("unused")
    @ApplicationScoped
    public static class AnnotationService {

        private CompletableFuture<DirectEvent> handledEvent = new CompletableFuture<>();
        private final AtomicInteger count = new AtomicInteger(0);

        @Inject
        public AnnotationService() {
        }

        @MessageHandler(queue = ANNOTATED_QUEUE, exchange = ANNOTATED_EXCHANGE)
        public void handle(DirectEvent event) {
            count.incrementAndGet();
            handledEvent.complete(event);
        }

        public CompletableFuture<DirectEvent> getHandledEvent() {
            return handledEvent;
        }

        public void reset() {
            handledEvent = new CompletableFuture<>();
            count.set(0);
        }

        public int getCount() {
            return count.get();
        }

    }

    @SuppressWarnings("unused")
    @ApplicationScoped
    public static class FirstFanoutService {

        private final CompletableFuture<String> future = new CompletableFuture<>();

        @FanoutMessageHandler(exchange = ANNOTATED_EXCHANGE_FANOUT)
        public void handleLogEvents(LoggingEvent event) {
            future.complete(event.log());
        }

        public CompletableFuture<String> getFuture() {
            return future;
        }

    }

    @SuppressWarnings("unused")
    @ApplicationScoped
    public static class SecondFanoutService {

        private final CompletableFuture<String> future = new CompletableFuture<>();

        @FanoutMessageHandler(exchange = ANNOTATED_EXCHANGE_FANOUT)
        public void handleLogEvents(LoggingEvent event) {
            future.complete(event.log());
        }

        public CompletableFuture<String> getFuture() {
            return future;
        }

    }

    private record ReceivedEvent(String name, long age) {

    }

    @SuppressWarnings("unused")
    @ApplicationScoped
    public static class TopicService {

        private CompletableFuture<String> futureOne = new CompletableFuture<>();
        private CompletableFuture<String> futureTwo = new CompletableFuture<>();
        private final AtomicInteger count = new AtomicInteger(0);

        private CountDownLatch countDownLatch = new CountDownLatch(99);

        @TopicMessageHandler(exchange = TOPIC_EXCHANGE, bindingKeys = SOMETHING_NOTHING)
        public void handleLogEventsOne(ReceivedEvent event) {
            count.incrementAndGet();
            futureOne.complete(event.name());
        }

        @TopicMessageHandler(exchange = TOPIC_EXCHANGE, bindingKeys = SOMETHING)
        public void handleLogEventsTwo(ReceivedEvent event) {
            count.incrementAndGet();
            countDownLatch.countDown();
            if ((countDownLatch.getCount()) == 0) {
                futureTwo.complete(event.name());
            }
        }

        public CompletableFuture<String> getFutureOne() {
            return futureOne;
        }

        public CompletableFuture<String> getFutureTwo() {
            return futureTwo;
        }

        public void reset() {
            futureOne = new CompletableFuture<>();
            futureTwo = new CompletableFuture<>();
            count.set(0);
            countDownLatch = new CountDownLatch(2);
        }

        public int getCount() {
            return count.get();
        }

    }

    @ProducedEvent(exchange = ANNOTATED_EXCHANGE, queue = ANNOTATED_QUEUE)
    private record DirectEvent(String name, Long age) {
    }

    @ProducedEvent(queue = ANNOTATED_QUEUE)
    private record DirectEventEmptyExchange(String name, Long age) {
    }

    @ProducedEvent(exchange = ANNOTATED_EXCHANGE, queue = EMPTY)
    private record DirectEventEmptyRoutingKey(String name, Long age) {
    }

    @ProducedEvent(queue = ANNOTATED_QUEUE, exchangeType = ExchangeType.TOPIC)
    private record TopicEventWrongEmptyExchange(String name, Long age) {
    }

    @ProducedEvent(exchange = TOPIC_EXCHANGE, queue = "nothing.a.nothing", exchangeType = ExchangeType.TOPIC)
    private record TopicEventOne(String name, Long age) {
    }

    @ProducedEvent(exchange = TOPIC_EXCHANGE, queue = "something.a.b", exchangeType = ExchangeType.TOPIC)
    private record TopicEventTwo(String name, Long age) {
    }

    @ProducedEvent(exchange = TOPIC_EXCHANGE, queue = "something.a.everything", exchangeType = ExchangeType.TOPIC)
    private record TopicEventThree(String name, Long age) {
    }

    @ProducedEvent(exchange = ANNOTATED_EXCHANGE_FANOUT, queue = ANNOTATED_QUEUE_FANOUT, exchangeType = ExchangeType.FANOUT)
    private record FanoutEventWrongRoutingKey(String name, Long age) {
    }

    @ProducedEvent(exchange = ANNOTATED_EXCHANGE_FANOUT, queue = EMPTY, exchangeType = ExchangeType.FANOUT)
    private record FanoutEvent(String name, Long age) {
    }

    @ProducedEvent(queue = ANNOTATED_QUEUE_FANOUT, exchangeType = ExchangeType.FANOUT)
    private record FanoutEventWrongEmptyExchange(String name, Long age) {
    }

    @ProducedEvent(exchange = ANNOTATED_EXCHANGE, queue = EMPTY, exchangeType = ExchangeType.TOPIC)
    private record TopicEventWrongRoutingKey(String name, Long age) {
    }
}