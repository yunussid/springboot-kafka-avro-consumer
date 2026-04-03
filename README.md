# Spring Boot Kafka Avro — Consumer

A beginner-friendly Spring Boot application that **reads Employee data from Apache Kafka**, deserialized from **Avro** binary format using **Schema Registry**.

> **New to Kafka?** Read the [Producer project README](https://github.com/yunussid/springboot-kafka-avro-producer) first — it explains Kafka from scratch.

---

## Where Does This Fit?

This is the **right half** of the pipeline:

```
  +-------------+         +------------------+        +-------------+
  |  PRODUCER   | ------> |   KAFKA BROKER   | -----> |  CONSUMER   |
  | (port 9393) |  Avro   | (port 9092)      |  Avro  | (this app)  |
  |  sends data |  binary | employee-avro-   |  binary| port 9494   |
  +-------------+         | topic            |        +------+------+
                           +--------+---------+               |
                                    |                         |
                           +--------+---------+               |
                           | SCHEMA REGISTRY  | <-------------+
                           | (port 8081)      |  "what shape
                           | stores the Avro  |   is this data?"
                           | schema           |
                           +------------------+
```

**In plain English:**
1. The Producer sends an `Employee` object to Kafka (as compact Avro bytes).
2. Kafka stores it in the `employee-avro-topic`.
3. **This Consumer app** automatically picks it up, asks Schema Registry "how do I read this?", deserializes it back into an `Employee` Java object, and logs / processes it.

---

## What is Kafka? (Quick Recap)

| Concept | One-liner |
|---------|-----------|
| **Kafka** | A message broker — a middleman that stores messages so producers and consumers do not need to talk directly |
| **Topic** | A named channel (like `employee-avro-topic`) where messages live |
| **Partition** | A topic is split into partitions so multiple consumers can read in parallel |
| **Consumer Group** | A team of consumers sharing the work. Each message goes to only ONE consumer in the group |
| **Offset** | A bookmark — tracks which messages you have already read |
| **Avro** | A compact binary data format (smaller & faster than JSON) |
| **Schema Registry** | Stores the "shape" of the data so producer and consumer always agree on the format |

> For a deeper explanation with diagrams, see the [Producer README](https://github.com/yunussid/springboot-kafka-avro-producer).

---

## Project Structure — Read the Code in This Order

```
springboot-kafka-avro-consumer/
  |
  |-- src/main/avro/
  |     +-- employee.avsc                        1. Same Avro schema as the producer
  |
  |-- src/main/java/com/kafkaPrac/kafka/
  |     |-- avro/
  |     |     +-- Employee.java                  2. AUTO-GENERATED from employee.avsc (do NOT edit)
  |     |
  |     |-- config/
  |     |     +-- KafkaAvroConsumerConfig.java   3. THE CORE — configures deserialization
  |     |
  |     |-- consumer/
  |     |     +-- KafkaAvroMessageConsumer.java  4. The @KafkaListener that receives messages
  |     |
  |     +-- KafkaAvroConsumerApplication.java    5. Spring Boot main class
  |
  |-- src/main/resources/
  |     +-- application.yaml                     6. Ports, broker address, topic, group-id
  |
  +-- pom.xml                                    7. Dependencies
```

### Recommended reading order explained

| Step | File | Why read it? |
|------|------|-------------|
| 1 | `employee.avsc` | The data contract. Identical to the producer. Both sides must agree on what an `Employee` looks like |
| 2 | `Employee.java` (avro/) | Auto-generated from the `.avsc`. Gives you type-safe getters: `employee.getName()`, `employee.getSalary()` |
| 3 | `KafkaAvroConsumerConfig.java` | **THE MOST IMPORTANT FILE.** Sets up: (a) broker address, (b) `KafkaAvroDeserializer` for values, (c) Schema Registry URL, (d) `SPECIFIC_AVRO_READER = true` so you get `Employee.java` objects instead of raw maps |
| 4 | `KafkaAvroMessageConsumer.java` | The `@KafkaListener` method. Spring calls this automatically every time a new message arrives. It logs partition, offset, and all employee fields |
| 5 | `application.yaml` | Broker at `localhost:9092`, schema registry at `localhost:8081`, topic `employee-avro-topic`, consumer group `avro-consumer-group` |

---

## What Happens When a Message Arrives (Step by Step)

```
1. Producer sends an Employee (Avro binary) to topic "employee-avro-topic"

2. Kafka stores it in Partition 1, Offset 42

3. This Consumer app is running with @KafkaListener on "employee-avro-topic"

4. Spring Kafka's internal polling loop detects: "new message at offset 42!"

5. KafkaAvroDeserializer (configured in KafkaAvroConsumerConfig):
     a. Reads the schema ID embedded in the Avro binary
     b. Fetches the schema from Schema Registry at http://localhost:8081
     c. Deserializes the binary bytes into an Employee.java object

6. Spring calls your method:
     consumeEmployee(ConsumerRecord<String, Employee> record)

7. Your code runs:
     Employee employee = record.value();
     log.info("Name: {}", employee.getName());
     log.info("Salary: {}", employee.getSalary());

8. The offset is committed — Kafka knows you have processed message 42
```

---

## Key Config Explained: `KafkaAvroConsumerConfig.java`

This is the file beginners struggle with most. Here is what each setting does:

```java
// WHERE is Kafka?
props.put(BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");

// WHO am I? (consumers with the same group-id share the work)
props.put(GROUP_ID_CONFIG, "avro-consumer-group");

// HOW do I read the key? (it is a String like "1", "2", "3")
props.put(KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

// HOW do I read the value? (it is Avro binary, not JSON)
props.put(VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class);

// WHERE are the schemas stored?
props.put(SCHEMA_REGISTRY_URL_CONFIG, "http://localhost:8081");

// IMPORTANT: give me Employee.java objects, not raw GenericRecord maps
props.put(SPECIFIC_AVRO_READER_CONFIG, true);

// If this is a brand-new consumer group, start reading from the very first message
props.put(AUTO_OFFSET_RESET_CONFIG, "earliest");
```

### What is `SPECIFIC_AVRO_READER`?

| Value | You get | Access fields with |
|-------|---------|-------------------|
| `false` (default) | `GenericRecord` | `record.get("name")` — no type safety |
| `true` | `Employee.java` | `employee.getName()` — type-safe, IDE autocomplete |

Always set it to `true` in real projects.

---

## What is a Consumer Group?

```
Topic: employee-avro-topic  (3 partitions)

                    Consumer Group: "avro-consumer-group"
                    +----------------------------------------+
  Partition 0 ----> | Consumer Thread 1  (handles Partition 0) |
  Partition 1 ----> | Consumer Thread 2  (handles Partition 1) |
  Partition 2 ----> | Consumer Thread 3  (handles Partition 2) |
                    +----------------------------------------+
                    concurrency = 3 (one thread per partition)
```

- **Same group-id** = messages are **split** among consumers (load balancing)
- **Different group-id** = each group gets **ALL** messages (broadcasting)

The `concurrency = 3` in the config means 3 threads. Since the topic has 3 partitions, each thread handles one partition — maximum parallelism.

**Rule:** `concurrency` should be less than or equal to the number of partitions. Extra threads just sit idle.

---

## Schema Evolution — Why Avro is Powerful

What if the Producer adds a new field like `joiningDate`?

| Scenario | What happens to this Consumer? |
|----------|-------------------------------|
| Producer ADDS an optional field (with default) | Consumer ignores it — **no code change needed** |
| Producer REMOVES an optional field | Consumer gets the default value (null) — **no crash** |
| Producer CHANGES a field type (int to string) | Schema Registry **rejects it** — data integrity protected |

This is called **schema evolution** and it is the #1 reason enterprises use Avro over JSON.

---

## Quick Start

### Prerequisites
- Java 17+
- Maven
- Kafka + Schema Registry running (use the `docker-compose.yml` from the [Producer project](https://github.com/yunussid/springboot-kafka-avro-producer))

### 1. Make sure Kafka is running
```bash
# From the producer project directory:
docker-compose up -d
docker-compose ps    # should show kafka and schema-registry as "healthy"
```

### 2. Build & Run the Consumer
```bash
mvn clean compile
mvn spring-boot:run
```
The app starts on **port 9494** and immediately begins listening for messages.

### 3. Send a message from the Producer
```bash
# In another terminal, hit the producer API:
curl -X POST http://localhost:9393/avro-producer/employee \
  -H "Content-Type: application/json" \
  -d '{
    "id": 1,
    "name": "John Doe",
    "email": "john@company.com",
    "department": "Engineering",
    "salary": 75000.00,
    "phoneNumber": "+1-555-0123",
    "address": "123 Main St"
  }'
```

### 4. Watch the Consumer logs
You should see:
```
RECEIVED EMPLOYEE (AVRO DESERIALIZED)
  Partition    : 1
  Offset       : 0
  Key          : 1
  ID           : 1
  Name         : John Doe
  Email        : john@company.com
  Department   : Engineering
  Salary       : 75000.0
  Phone Number : +1-555-0123
  Address      : 123 Main St
```

---

## The Full Picture — Both Projects Together

```
  Terminal 1: docker-compose up -d              (start Kafka + Schema Registry)
  Terminal 2: cd producer && mvn spring-boot:run (start Producer on port 9393)
  Terminal 3: cd consumer && mvn spring-boot:run (start Consumer on port 9494)
  Terminal 4: curl POST to localhost:9393        (send an Employee)

  Then watch Terminal 3 — the consumer logs the Employee automatically!
```

---

## Related Project

- **Producer:** [springboot-kafka-avro-producer](https://github.com/yunussid/springboot-kafka-avro-producer) — sends Employee messages to Kafka with full Kafka beginner guide
