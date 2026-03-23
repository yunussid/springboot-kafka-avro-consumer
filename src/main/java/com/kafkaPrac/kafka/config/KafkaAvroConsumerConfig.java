package com.kafkaPrac.kafka.config;

import com.kafkaPrac.kafka.avro.Employee;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka Consumer Configuration with Avro Deserialization
 * 
 * KEY CONCEPTS:
 * 1. KafkaAvroDeserializer - Deserializes Avro binary to Java objects
 * 2. Schema Registry URL - Fetches schema to deserialize data
 * 3. SPECIFIC_AVRO_READER - Returns generated class instead of GenericRecord
 * 
 * FLOW:
 * Kafka (binary data) → Schema Registry (fetch schema) → KafkaAvroDeserializer → Employee.java
 * 
 * SCHEMA EVOLUTION:
 * - Consumer can read data even if producer's schema is different (within compatibility rules)
 * - Schema Registry validates compatibility between versions
 * - Backward compatible: Consumer can read old data with new schema
 * - Forward compatible: Old consumer can read new data
 */
@Configuration
public class KafkaAvroConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${app.kafka.consumer.group-id}")
    private String groupId;

    @Value("${schema.registry.url}")
    private String schemaRegistryUrl;

    /**
     * Consumer configuration for Avro deserialization
     */
    @Bean
    public Map<String, Object> consumerConfigs() {
        Map<String, Object> props = new HashMap<>();
        
        // Kafka broker address
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        
        // Consumer group ID
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        
        // Key deserializer - String
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        
        // Value deserializer - Avro (converts Avro binary to Java objects)
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class);
        
        // Schema Registry URL - where to fetch schemas for deserialization
        props.put(KafkaAvroDeserializerConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
        
        // IMPORTANT: Return specific Avro class (Employee.class) instead of GenericRecord
        // true  = Returns Employee.java (generated class)
        // false = Returns GenericRecord (requires field access by name)
        props.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);
        
        // Start from earliest offset if no committed offset exists
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        
        return props;
    }

    @Bean
    public ConsumerFactory<String, Employee> consumerFactory() {
        return new DefaultKafkaConsumerFactory<>(consumerConfigs());
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Employee> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Employee> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setConcurrency(3);
        return factory;
    }
}
