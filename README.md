# Spring Boot Kafka Avro -- Consumer

A beginner-friendly Spring Boot application that **reads Employee data from Apache Kafka**, deserialized from **Avro** binary format using **Schema Registry**.

> **New to Kafka?** Read the [Producer project README](https://github.com/yunussid/springboot-kafka-avro-producer) first -- it explains Kafka from scratch.

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
| **Kafka** | A message broker -- a middleman that stores messages so producers and consumers do not need to talk directly |
| **Topic** | A named channel (like `employee-avro-topic`) where messages live |
| **Partition** | A topic is split into partitions so multiple consumers can read in parallel |
| **Consumer Group** | A team of consumers sharing the work. Each message goes to only ONE consumer in the group |
| **Offset** | A bookmark -- tracks which messages you have already read |
| **Avro** | A compact binary data format (smaller & faster than JSON) |
| **Schema Registry** | Stores the "shape" of the data so producer and consumer always agree on the format |

> For a deeper explanation with diagrams, see the [Producer README](https://github.com/yunussid/springboot-kafka-avro-producer).

---

## Code Reading Guide -- Follow This Path

### Step 1: The Data Contract

**File:** `src/main/avro/employee.avsc`

This file is **identical** to the one in the Producer project. Both sides must agree on what an `Employee` looks like. The schema defines 5 required fields (`id`, `name`, `email`, `department`, `salary`) and 2 optional fields (`phoneNumber`, `address`).

> If the producer adds a new optional field tomorrow, this consumer will still work -- it simply ignores fields it does not know about. That is schema evolution.

---

### Step 2: The Generated Class -- DO NOT EDIT

**File:** `src/main/java/com/kafkaPrac/kafka/avro/Employee.java`

Auto-generated from `employee.avsc` by the Avro Maven Plugin. Gives you type-safe getters like `employee.getName()` and `employee.getSalary()`. Never edit this file manually.

---

### Step 3: Consumer Configuration -- THE CORE

**File:** `src/main/java/com/kafkaPrac/kafka/config/KafkaAvroConsumerConfig.java`

**This is the most important file in the project.** It configures HOW messages are read:

```
consumerConfigs()                -- settings map:
  BOOTSTRAP_SERVERS              --> localhost:9092 (where is Kafka?)
  GROUP_ID                       --> avro-consumer-group (who am I?)
  KEY_DESERIALIZER               --> StringDeserializer (keys are strings)
  VALUE_DESERIALIZER             --> KafkaAvroDeserializer (values are Avro binary)
  SCHEMA_REGISTRY_URL            --> http://localhost:8081 (where to fetch schemas)
  SPECIFIC_AVRO_READER           --> true (give me Employee.java, not GenericRecord)
  AUTO_OFFSET_RESET              --> "earliest" (new group? start from first message)
            |
            v
consumerFactory()                -- creates Kafka consumer instances using above config
            |
            v
kafkaListenerContainerFactory()  -- wraps @KafkaListener methods, manages threads
  concurrency = 3                -- 3 threads (one per partition)
```

**Key setting -- SPECIFIC_AVRO_READER:**

| Value | You get | Access fields with |
|-------|---------|-------------------|
| `false` (default) | `GenericRecord` | `record.get("name")` -- no type safety |
| `true` | `Employee.java` | `employee.getName()` -- type-safe, IDE autocomplete |

Always set it to `true`.

---

### Step 4: The Kafka Listener -- Where messages arrive

**File:** `src/main/java/com/kafkaPrac/kafka/consumer/KafkaAvroMessageConsumer.java`

This is where the magic happens. The `@KafkaListener` annotation tells Spring:

> "Whenever a new message appears on `employee-avro-topic`, call my `consumeEmployee()` method with the deserialized data."

```
@KafkaListener(
    topics = "${app.kafka.topics.employee}",        <-- topic from application.yaml
    groupId = "${app.kafka.consumer.group-id}",     <-- consumer group from application.yaml
    concurrency = "3"                               <-- 3 threads (match partition count)
)
public void consumeEmployee(ConsumerRecord<String, Employee> record) {
    Employee employee = record.value();        <-- already deserialized to Employee.java
    log.info("Name: {}", employee.getName());
    log.info("Salary: {}", employee.getSalary());
    processEmployee(employee);                 <-- your business logic goes here
}
```

The `processEmployee()` method at the bottom is where you would add real business logic: save to DB, send email, update HR system, etc.

---

### Step 5: Application Config

**File:** `src/main/resources/application.yaml`

```yaml
server.port: 9494                                    # This app runs on port 9494

spring.kafka.bootstrap-servers: localhost:9092        # Where Kafka is running
schema.registry.url: http://localhost:8081            # Where Schema Registry is running

app.kafka.topics.employee: employee-avro-topic       # Topic to listen on
app.kafka.consumer.group-id: avro-consumer-group     # Consumer group name
```

Every `@Value(...)` and `${...}` in the Java code pulls from this file.

---

### Step 6: Dependencies

**File:** `pom.xml` (project root)

Key dependencies:
- `spring-boot-starter-web` -- REST API (for health checks etc.)
- `spring-kafka` -- Kafka integration
- `avro` -- Avro data format
- `kafka-avro-serializer` -- includes the `KafkaAvroDeserializer`
- `kafka-schema-registry-client` -- talks to Schema Registry
- `avro-maven-plugin` -- generates `Employee.java` from `employee.avsc`

---

## The Complete Runtime Flow -- File by File

```
Producer sends Avro binary to Kafka topic "employee-avro-topic"
               |
               v
  Kafka Broker (localhost:9092)
    |  stores message in Partition 1, Offset 42
               |
               v
  Spring Kafka polling loop (runs in background)
    |  detects: "new message at offset 42!"
    |  configured by...
               |
               v
  KafkaAvroConsumerConfig.java                    [config/]
    |  kafkaListenerContainerFactory
    |  --> consumerFactory --> consumerConfigs
    |  VALUE_DESERIALIZER = KafkaAvroDeserializer
    |  SCHEMA_REGISTRY_URL = http://localhost:8081
    |  SPECIFIC_AVRO_READER = true
               |
               v
  KafkaAvroDeserializer (Confluent library, not your code)
    |  Step A: reads schema ID from the Avro binary header
    |  Step B: fetches the schema from Schema Registry
    |  Step C: deserializes binary bytes into Employee.java object
               |
               v
  KafkaAvroMessageConsumer.java                   [consumer/]
    |  @KafkaListener method is called with ConsumerRecord<String, Employee>
    |  Employee employee = record.value()
    |  Logs: partition, offset, id, name, email, department, salary, phone, address
    |  Calls processEmployee(employee) for business logic
               |
               v
  Done. Offset 42 is committed. Kafka won't send this message again.
```

---

## What Happens at App Startup (Before Any Message)

```
Spring Boot starts
       |
       v
  KafkaAvroConsumerConfig.java       creates consumerFactory + listenerContainerFactory
       |
       v
  application.yaml                   provides all config values via @Value / ${...}
       |
       v
  @KafkaListener is registered       Spring starts 3 background polling threads
       |                              (one per partition, concurrency = 3)
       v
  App is ready on port 9494          threads are polling Kafka for new messages
```

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

**Rule:** `concurrency` should be <= number of partitions. Extra threads just sit idle.

---

## Schema Evolution -- Why Avro is Powerful

What if the Producer adds a new field like `joiningDate`?

| Scenario | What happens to this Consumer? |
|----------|-------------------------------|
| Producer ADDS an optional field (with default) | Consumer ignores it -- **no code change needed** |
| Producer REMOVES an optional field | Consumer gets the default value (null) -- **no crash** |
| Producer CHANGES a field type (int to string) | Schema Registry **rejects it** -- data integrity protected |

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

## The Full Picture -- Both Projects Together

```
  Terminal 1: docker-compose up -d              (start Kafka + Schema Registry)
  Terminal 2: cd producer && mvn spring-boot:run (start Producer on port 9393)
  Terminal 3: cd consumer && mvn spring-boot:run (start Consumer on port 9494)
  Terminal 4: curl POST to localhost:9393        (send an Employee)

  Then watch Terminal 3 -- the consumer logs the Employee automatically!
```

---

## Related Project

- **Producer:** [springboot-kafka-avro-producer](https://github.com/yunussid/springboot-kafka-avro-producer) -- sends Employee messages to Kafka with full Kafka beginner guide
