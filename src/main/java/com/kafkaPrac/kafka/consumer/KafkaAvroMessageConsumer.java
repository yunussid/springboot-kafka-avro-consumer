package com.kafkaPrac.kafka.consumer;

import com.kafkaPrac.kafka.avro.Employee;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/**
 * Kafka Avro Message Consumer
 * 
 * Consumes Employee objects deserialized from Avro format.
 * Schema is fetched from Schema Registry for deserialization.
 * 
 * SCHEMA EVOLUTION HANDLING:
 * - If producer adds new optional field → Consumer ignores it (backward compatible)
 * - If producer removes optional field → Consumer gets default value (forward compatible)
 * - Schema Registry ensures compatibility rules are followed
 * 
 * BENEFITS:
 * 1. No code changes needed when schema evolves (within compatibility rules)
 * 2. Type-safe - works with generated Employee class
 * 3. Automatic schema validation
 */
@Service
public class KafkaAvroMessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaAvroMessageConsumer.class);

    /**
     * Consume Employee messages from Avro topic
     * 
     * FLOW:
     * 1. Message received from Kafka (Avro binary)
     * 2. KafkaAvroDeserializer fetches schema from Schema Registry
     * 3. Deserializes binary to Employee object
     * 4. This method receives typed Employee object
     */
    @KafkaListener(
            topics = "${app.kafka.topics.employee}",
            groupId = "${app.kafka.consumer.group-id}",
            concurrency = "3"
    )
    public void consumeEmployee(ConsumerRecord<String, Employee> record) {
        Employee employee = record.value();
        
        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║           RECEIVED EMPLOYEE (AVRO DESERIALIZED)              ║");
        log.info("╠══════════════════════════════════════════════════════════════╣");
        log.info("║ Partition    : {}", record.partition());
        log.info("║ Offset       : {}", record.offset());
        log.info("║ Key          : {}", record.key());
        log.info("║ ─────────────────────────────────────────────────────────────");
        log.info("║ ID           : {}", employee.getId());
        log.info("║ Name         : {}", employee.getName());
        log.info("║ Email        : {}", employee.getEmail());
        log.info("║ Department   : {}", employee.getDepartment());
        log.info("║ Salary       : {}", employee.getSalary());
        log.info("║ Phone Number : {}", employee.getPhoneNumber());  // May be null
        log.info("║ Address      : {}", employee.getAddress());      // May be null
        log.info("╚══════════════════════════════════════════════════════════════╝");

        // Process the employee
        processEmployee(employee);
    }

    /**
     * Process Employee - business logic
     */
    private void processEmployee(Employee employee) {
        log.debug("Processing employee: {} - {}", employee.getId(), employee.getName());
        
        // Example business logic:
        // - Save to database
        // - Calculate tax based on salary
        // - Send welcome email
        // - Update HR system
        
        // Handle optional fields gracefully (schema evolution)
        if (employee.getPhoneNumber() != null) {
            log.debug("Employee has phone: {}", employee.getPhoneNumber());
        }
        
        if (employee.getAddress() != null) {
            log.debug("Employee has address: {}", employee.getAddress());
        }
    }
}
