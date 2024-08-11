package com.techie.microservices.order;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.techie.microservices.order.event.OrderPlacedEvent;
import io.restassured.RestAssured;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.awaitility.Awaitility;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
@TestPropertySource(
    properties = {
        "spring.kafka.consumer.auto-offset-reset=earliest"
    }
)
@Testcontainers
class OrderServiceApplicationTests {
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
  static WireMockExtension wireMock = WireMockExtension.newInstance()
      .options(wireMockConfig().bindAddress("localhost"))
      .build();
  @ServiceConnection
  static MySQLContainer mySQLContainer = new MySQLContainer("mysql:8.3.0");

  static {
    mySQLContainer.start();
  }

  @LocalServerPort
  private Integer port;
  @Autowired
  private TestKafkaConsumer testKafkaConsumer;

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
    registry.add("spring.kafka.consumer.group-id", () -> "orders");
    registry.add("spring.kafka.consumer.properties.specific.avro.reader", () -> "true");
    registry.add("spring.kafka.consumer.properties.schema.registry.url", schemaRegistryUrl);
    registry.add("spring.kafka.consumer.auto-offset-reset", () -> "latest");
    registry.add("spring.kafka.consumer.key-deserializer", () -> "org.apache.kafka.common.serialization.StringDeserializer");
    registry.add("spring.kafka.consumer.value-deserializer", () -> "io.confluent.kafka.serializers.KafkaAvroDeserializer");

  }

  @BeforeEach
  void setup() {
    RestAssured.baseURI = "http://localhost";
    RestAssured.port = port;
  }

  @Test
  void shouldSubmitOrder() {
    String submitOrderJson = """
        {
             "skuCode": "iphone_15",
             "price": 1000,
             "quantity": 1,
             "userDetails" : {
             		"email" : "test@email.com", 
             		"firstName" : "Tom", 
             		"lastName" : "Hardy"
             }
        }
        """;
    wireMock.stubFor(get(urlEqualTo("/api/inventory?skuCode=iphone_15&quantity=1"))
        .willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("true")));

    var responseBodyString = RestAssured.given()
        .contentType("application/json")
        .body(submitOrderJson)
        .when()
        .post("/api/order")
        .then()
        .log().all()
        .statusCode(201)
        .extract()
        .body().asString();

    Awaitility.await().pollInterval(500, TimeUnit.MILLISECONDS).atLeast(200, TimeUnit.MILLISECONDS)
        .atMost(10000, TimeUnit.MILLISECONDS).untilAsserted(() -> {
          OrderPlacedEvent orderPlacedEvent = testKafkaConsumer.getStack().getLast();

          assertEquals("test@email.com", orderPlacedEvent.getEmail().toString());
          assertEquals("Tom", orderPlacedEvent.getFirstName().toString());
          assertEquals("Hardy", orderPlacedEvent.getLastName().toString());
        });

    assertThat(responseBodyString, Matchers.is("Order Placed Successfully"));
  }

  @Test
  void shouldFailOrderWhenProductIsNotInStock() {
    String submitOrderJson = """
        {
             "skuCode": "iphone_15",
             "price": 1000,
             "quantity": 1000
        }
        """;
    wireMock.stubFor(get(urlEqualTo("/api/inventory?skuCode=iphone_15&quantity=1000"))
        .willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("false")));

    RestAssured.given()
        .contentType("application/json")
        .body(submitOrderJson)
        .when()
        .post("/api/order")
        .then()
        .log().all()
        .statusCode(500);
  }
}