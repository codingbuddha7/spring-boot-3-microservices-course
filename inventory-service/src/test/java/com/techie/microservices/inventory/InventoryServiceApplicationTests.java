package com.techie.microservices.inventory;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MySQLContainer;

@SpringBootTest
class InventoryServiceApplicationTests {
  @ServiceConnection
  static MySQLContainer mySQLContainer = new MySQLContainer("mysql:8.3.0");

  static {
    mySQLContainer.start();
  }

  @Test
  void contextLoads() {
  }

}
