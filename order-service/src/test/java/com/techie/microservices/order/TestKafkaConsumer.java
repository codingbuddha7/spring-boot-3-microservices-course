package com.techie.microservices.order;

import com.techie.microservices.order.event.OrderPlacedEvent;
import java.util.concurrent.ConcurrentLinkedDeque;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class TestKafkaConsumer {

  private final ConcurrentLinkedDeque<OrderPlacedEvent> stack = new ConcurrentLinkedDeque<>();

  public ConcurrentLinkedDeque<OrderPlacedEvent> getStack() {
    return stack;
  }

  @KafkaListener(topics = "order-placed", groupId = "orders", containerFactory = "kafkaListenerContainerFactory")
  public void consume(ConsumerRecord<String, OrderPlacedEvent> record) {
    OrderPlacedEvent orderPlacedEvent = record.value();
    log.info("Consumed orderPlacedEvent {}", orderPlacedEvent);
    stack.push(orderPlacedEvent);
  }
}