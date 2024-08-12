package com.techie.microservices.notification;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.spring.GreenMailBean;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.techie.microservices.order.event.OrderPlacedEvent;
import jakarta.mail.internet.MimeMessage;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
@TestPropertySource(
    properties = {
        "spring.kafka.consumer.auto-offset-reset=earliest"
    }
)
@Testcontainers
class NotificationServiceApplicationTests {
  private static final Network NETWORK = Network.newNetwork();
  @Container
  static final KafkaContainer KAFKA_CONTAINER = new KafkaContainer(
      DockerImageName.parse("confluentinc/cp-kafka:7.6.1")).withNetwork(NETWORK);
  @Container
  private static final GenericContainer<?> SCHEMA_REGISTRY =
      new GenericContainer<>(DockerImageName.parse("confluentinc/cp-schema-registry:7.5.2"))
          .withNetwork(NETWORK)
          .withExposedPorts(8081)
          .withEnv("SCHEMA_REGISTRY_HOST_NAME", "schema-registry")
          .withEnv("SCHEMA_REGISTRY_LISTENERS", "http://0.0.0.0:8081")
          .withEnv("SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS",
              "PLAINTEXT://" + KAFKA_CONTAINER.getNetworkAliases().get(0) + ":9092")
          .waitingFor(Wait.forHttp("/subjects").forStatusCode(200));

  @RegisterExtension
  static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP)
      .withConfiguration(GreenMailConfiguration.aConfig().withUser("duke", "springboot"))
      .withPerMethodLifecycle(false);

  @Autowired
  private KafkaTemplate<String, OrderPlacedEvent> kafkaTemplate;

  @DynamicPropertySource
  static void overrideProperties(DynamicPropertyRegistry registry) {
    Supplier<Object> schemaRegistryUrl = () -> "http://" + SCHEMA_REGISTRY.getHost() + ":" + SCHEMA_REGISTRY.getFirstMappedPort();
    registry.add("spring.kafka.bootstrap-servers", KAFKA_CONTAINER::getBootstrapServers);
    registry.add("spring.kafka.template.default-topic", () -> "order-placed");
    //Producer
    registry.add("spring.kafka.producer.properties.schema.registry.url", schemaRegistryUrl);
    registry.add("spring.kafka.producer.key-serializer", () -> "org.apache.kafka.common.serialization.StringSerializer");
    registry.add("spring.kafka.producer.value-serializer", () -> "io.confluent.kafka.serializers.KafkaAvroSerializer");
    //Consumer
    registry.add("spring.kafka.consumer.group-id", () -> "notification-service");
    registry.add("spring.kafka.consumer.properties.specific.avro.reader", () -> "true");
    registry.add("spring.kafka.consumer.properties.schema.registry.url", schemaRegistryUrl);
    registry.add("spring.kafka.consumer.auto-offset-reset", () -> "latest");
    registry.add("spring.kafka.consumer.key-deserializer", () -> "org.apache.kafka.common.serialization.StringDeserializer");
    registry.add("spring.kafka.consumer.value-deserializer", () -> "io.confluent.kafka.serializers.KafkaAvroDeserializer");

  }

  @Test
  void shouldReceiveOrderNotification() {
    // Send the message to Kafka Topic
    OrderPlacedEvent orderPlacedEvent = new OrderPlacedEvent();
    orderPlacedEvent.setOrderNumber("Order12345");
    orderPlacedEvent.setEmail("test@mail.com");
    orderPlacedEvent.setFirstName("Tom");
    orderPlacedEvent.setLastName("Hardy");
    kafkaTemplate.send("order-placed", orderPlacedEvent);

    Awaitility.await().pollInterval(500, TimeUnit.MILLISECONDS).atLeast(200, TimeUnit.MILLISECONDS)
        .atMost(10000, TimeUnit.MILLISECONDS).untilAsserted(() -> {
          final MimeMessage[] receivedMessages = greenMail.getReceivedMessages();
          final MimeMessage receivedMessage = receivedMessages[0];
          String emailBody = GreenMailUtil.getBody(receivedMessage);
          assertTrue(emailBody.contains("Your order with order number Order12345 is now placed successfully"));
        });
  }

}