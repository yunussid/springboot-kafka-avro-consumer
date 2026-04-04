# Spring Boot Kafka Avro -- Consumer

A beginner-friendly Spring Boot application that **reads Employee data from Apache Kafka**, deserialized from **Avro** binary format using **Schema Registry**.

> **New to Kafka?** Read the [Producer project README](https://github.com/yunussid/springboot-kafka-avro-producer) first -- it explains Kafka from scratch with glossary, diagrams, and 3-port explanation.

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

> For the full explanation with diagrams, see the [Producer README](https://github.com/yunussid/springboot-kafka-avro-producer).

### What is KRaft? (Quick Version)

Older Kafka setups needed a separate system called **Zookeeper** to manage metadata (which broker is alive, who leads each partition). Starting with Kafka 3.x, Kafka handles this itself using a built-in protocol called **KRaft (Kafka Raft)**. Zookeeper was **fully removed in Kafka 4.0**.

```
Old way:  Zookeeper + Kafka + Schema Registry  (3 systems)
New way:  Kafka (KRaft) + Schema Registry       (2 systems -- what this project uses)
```

**This does not affect your consumer code at all.** Your app connects to `localhost:9092` regardless of whether the broker uses KRaft or Zookeeper. But it is important to know because:
- Interview question -- "What is KRaft?"
- If you see old tutorials with Zookeeper, they are outdated
- The `docker-compose.yml` (in the Producer project) configures `KAFKA_PROCESS_ROLES: broker,controller` -- that is the KRaft setting

> For the full KRaft deep-dive with diagrams and every docker-compose setting explained, see the [Producer README](https://github.com/yunussid/springboot-kafka-avro-producer).

---

## Understanding the 3 Ports (Same Concept as Producer)

```yaml
# From application.yaml:
server:
  port: 9494                              # Port 1: THIS consumer app

spring:
  kafka:
    bootstrap-servers: localhost:9092      # Port 2: Kafka broker

schema:
  registry:
    url: http://localhost:8081             # Port 3: Schema Registry
```

These are **3 completely separate servers**:

```
+-------------------------------------------------------------------+
|                        YOUR MACHINE                                |
|                                                                    |
|   +---------------------+                                         |
|   | THIS CONSUMER APP    |   This is YOUR Java application.       |
|   | Port 9494            |   It has NO REST endpoints.            |
|   | (application server) |   It LISTENS to Kafka in the           |
|   |                      |   background using @KafkaListener.     |
|   |                      |   You wrote this code.                 |
|   +----------+-----------+                                         |
|              |                                                     |
|              | "give me new messages from employee-avro-topic"     |
|              v                                                     |
|   +---------------------+                                         |
|   | KAFKA BROKER         |   The MESSAGE STORAGE.                  |
|   | Port 9092            |   It stores messages in partitions      |
|   | (bootstrap-servers)  |   and delivers them to consumers.       |
|   |                      |   Runs inside Docker.                   |
|   +----------+-----------+                                         |
|              |                                                     |
|              | "how do I deserialize this Avro binary?"             |
|              v                                                     |
|   +---------------------+                                         |
|   | SCHEMA REGISTRY      |   The SCHEMA STORAGE.                   |
|   | Port 8081            |   Consumer fetches the schema here      |
|   | (schema.registry.url)|   to know how to read the binary data.  |
|   |                      |   Runs inside Docker.                   |
|   +---------------------+                                         |
+-------------------------------------------------------------------+
```

#### Key Difference from the Producer

| | Producer (port 9393) | Consumer (port 9494) |
|-|---------------------|---------------------|
| Has REST endpoints? | Yes -- you POST data to it | **No** -- no REST endpoints at all |
| How is it triggered? | You send a curl/Postman request | **Automatic** -- Spring polls Kafka in background threads |
| Direction | Sends TO Kafka | Reads FROM Kafka |
| Why does it even have a port? | To serve REST APIs | Spring Boot always starts an embedded server. It is used for health checks, actuator, etc. |

#### Where Each Port is Configured in Code

| Port | Defined in | Used by |
|------|-----------|---------|
| `9494` | `application.yaml` -> `server.port` | Spring Boot embedded Tomcat |
| `9092` | `application.yaml` -> `spring.kafka.bootstrap-servers` | `KafkaAvroConsumerConfig.java` reads via `@Value("${spring.kafka.bootstrap-servers}")` and passes to `ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG` |
| `8081` | `application.yaml` -> `schema.registry.url` | `KafkaAvroConsumerConfig.java` reads via `@Value("${schema.registry.url}")` and passes to `KafkaAvroDeserializerConfig.SCHEMA_REGISTRY_URL_CONFIG` |

#### What Happens If One Is Down?

| Scenario | What breaks? |
|----------|-------------|
| Consumer App (9494) is down | Messages pile up in Kafka. When you restart, it picks up from where it left off (thanks to offsets) -- **no data lost** |
| Kafka (9092) is down | Consumer cannot poll for new messages. It retries and logs connection errors until Kafka is back |
| Schema Registry (8081) is down | Consumer cannot deserialize Avro binary -- it does not know the schema. Messages stay unprocessed until the registry is back |

---

## Code Reading Guide -- Follow This Path

### Step 1: The Data Contract

**File:** `src/main/avro/employee.avsc`

This file is **identical** to the one in the Producer project. Both sides must agree on what an `Employee` looks like. The schema defines 5 required fields (`id`, `name`, `email`, `department`, `salary`) and 2 optional fields (`phoneNumber`, `address`).

```
Fields defined here:
  id          : int       (required)
  name        : string    (required)
  email       : string    (required)
  department  : string    (required)
  salary      : double    (required)
  phoneNumber : string    (OPTIONAL -- can be null)
  address     : string    (OPTIONAL -- can be null)
```

> If the producer adds a new optional field tomorrow, this consumer will still work -- it simply ignores fields it does not know about. That is schema evolution.

---

### Step 2: The Generated Class -- DO NOT EDIT

**File:** `src/main/java/com/kafkaPrac/kafka/avro/Employee.java`

Auto-generated from `employee.avsc` by the Avro Maven Plugin during `mvn compile`. Gives you type-safe getters like `employee.getName()` and `employee.getSalary()`. Never edit this file manually.

---

### Step 3: Consumer Configuration -- THE CORE

**File:** `src/main/java/com/kafkaPrac/kafka/config/KafkaAvroConsumerConfig.java`

**This is the most important file in the project.** It configures HOW messages are read. It creates 3 beans, each wrapping the previous one (same pattern as the Producer's KafkaTemplate):

```
LAYER 1: consumerConfigs()                -- a Map of settings (what to do)
  |
  |  Settings like:
  |    - where is Kafka?                  (localhost:9092)
  |    - who am I?                        (avro-consumer-group)
  |    - how to deserialize the key?      (StringDeserializer)
  |    - how to deserialize the value?    (KafkaAvroDeserializer)
  |    - where is Schema Registry?        (http://localhost:8081)
  |    - give me Employee.java objects?   (SPECIFIC_AVRO_READER = true)
  |    - where to start reading?          (AUTO_OFFSET_RESET = "earliest")
  |
  v
LAYER 2: consumerFactory()               -- creates Kafka consumer instances
  |
  |  new DefaultKafkaConsumerFactory<>(consumerConfigs())
  |  This factory uses your settings to create actual Kafka consumer connections.
  |
  v
LAYER 3: kafkaListenerContainerFactory() -- the thing that powers @KafkaListener
  |
  |  Wraps your @KafkaListener methods in managed containers.
  |  Starts background polling threads (concurrency = 3).
  |  This is the CONSUMER equivalent of KafkaTemplate.
  |
  v
YOUR CODE: @KafkaListener methods are called automatically when messages arrive
```

In the actual Java code (`KafkaAvroConsumerConfig.java`):

```java
@Bean
public Map<String, Object> consumerConfigs() {           // LAYER 1: settings
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class);
    props.put(KafkaAvroDeserializerConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
    props.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    return props;
}

@Bean
public ConsumerFactory<String, Employee> consumerFactory() {   // LAYER 2: factory
    return new DefaultKafkaConsumerFactory<>(consumerConfigs());
}

@Bean
public ConcurrentKafkaListenerContainerFactory<String, Employee>
        kafkaListenerContainerFactory() {                      // LAYER 3: listener factory
    ConcurrentKafkaListenerContainerFactory<String, Employee> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(consumerFactory());
    factory.setConcurrency(3);  // 3 threads -- one per partition
    return factory;
}
```

---

### Deep Dive: Every Config Setting Explained

```java
// WHERE is Kafka?
props.put(BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
```
The "front door" to the Kafka cluster. Your consumer connects here first, then Kafka tells it about all brokers.

```java
// WHO am I?
props.put(GROUP_ID_CONFIG, "avro-consumer-group");
```
Consumer group name. All consumers with the SAME group-id share the work (load balancing). See the "Consumer Groups" section below.

```java
// HOW do I read the key?
props.put(KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
```
The producer sent keys like `"1"`, `"2"`, `"3"` as strings. This deserializer converts the raw bytes back to Java `String`.

```java
// HOW do I read the value?
props.put(VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class);
```
The producer sent Employee objects as Avro binary. This deserializer contacts Schema Registry to fetch the schema, then converts the binary bytes back into a Java object.

```java
// WHERE are the schemas?
props.put(SCHEMA_REGISTRY_URL_CONFIG, "http://localhost:8081");
```
The `KafkaAvroDeserializer` needs this URL to fetch the schema. Every Avro message contains a schema ID in its header. The deserializer sends that ID to Schema Registry and gets back the full schema definition.

```java
// GIVE ME Employee.java, not a raw map
props.put(SPECIFIC_AVRO_READER_CONFIG, true);
```
This is the most misunderstood setting:

| Value | You get | Access fields with |
|-------|---------|-------------------|
| `false` (default) | `GenericRecord` | `record.get("name")` -- returns Object, no type safety |
| `true` | `Employee.java` | `employee.getName()` -- returns String, IDE autocomplete works |

Without this set to `true`, your `@KafkaListener` method would receive a generic map-like object instead of the typed `Employee` class. Always set it to `true`.

```java
// WHERE should a brand-new consumer start reading?
props.put(AUTO_OFFSET_RESET_CONFIG, "earliest");
```
This ONLY matters when your consumer group has **no previously committed offset** (first time running, or group-id changed):

| Value | Behavior | When to use |
|-------|----------|-------------|
| `"earliest"` | Start from the FIRST message ever in the topic | You want to process all historical data |
| `"latest"` | Start from NOW -- only see NEW messages | You only care about future messages |

After the first run, Kafka remembers your offset. This setting is ignored on subsequent runs.

---

### Deep Dive: What is ConcurrentKafkaListenerContainerFactory?

**Where it is created:** `src/main/java/com/kafkaPrac/kafka/config/KafkaAvroConsumerConfig.java`
**Where it is used:** Automatically by Spring -- it powers every `@KafkaListener` in the app.

#### The Problem it Solves

The Producer has `KafkaTemplate` (you call `.send()` to push messages). The Consumer needs the opposite -- something that **automatically pulls** messages and calls your code.

Without Spring, you would need to:
1. Create a `KafkaConsumer` with 10+ settings
2. Call `consumer.subscribe("employee-avro-topic")`
3. Write an infinite polling loop: `while (true) { records = consumer.poll(); ... }`
4. Process each record manually
5. Commit offsets manually
6. Handle rebalances, errors, and thread management yourself

**ConcurrentKafkaListenerContainerFactory wraps all of that.** You just annotate a method with `@KafkaListener` and Spring handles the rest.

#### Plain English

```
Raw KafkaConsumer  =  Standing at the post office, asking "any mail?" every second, manually
ConcurrentKafkaListenerContainerFactory  =  Hiring a team of mail carriers who deliver to your door
```

#### The "Concurrent" Part -- Why 3 Threads?

```java
factory.setConcurrency(3);  // in KafkaAvroConsumerConfig.java
```

This creates **3 independent polling threads**, one per partition:

```
Topic: employee-avro-topic  (3 partitions)

  Partition 0 -----> [ Thread 1 ] -----> calls consumeEmployee()
  Partition 1 -----> [ Thread 2 ] -----> calls consumeEmployee()
  Partition 2 -----> [ Thread 3 ] -----> calls consumeEmployee()
```

Each thread runs its own `poll()` loop independently. Messages from different partitions are processed in **parallel**.

| Concurrency | Partitions | What happens |
|------------|------------|-------------|
| 1 | 3 | One thread handles ALL 3 partitions (slow) |
| 3 | 3 | One thread per partition (maximum speed) |
| 5 | 3 | 3 threads work, 2 sit idle (waste of memory) |

**Rule:** `concurrency` should be <= number of partitions.

#### KafkaTemplate vs ConcurrentKafkaListenerContainerFactory

| | Producer: KafkaTemplate | Consumer: ListenerContainerFactory |
|-|------------------------|-----------------------------------|
| Purpose | Send messages | Receive messages |
| You call it? | Yes -- `kafkaTemplate.send()` | No -- Spring calls YOUR method |
| Direction | Your code -> Kafka | Kafka -> Your code |
| Threads | Uses the calling thread | Creates its own background threads |
| How it works | You push | It polls and pushes to you |

---

### Deep Dive: What is @KafkaListener?

**File:** `src/main/java/com/kafkaPrac/kafka/consumer/KafkaAvroMessageConsumer.java`

#### The Problem it Solves

You need to tell Spring: "whenever a new message arrives on this topic, call this method." That is what `@KafkaListener` does.

#### How it Works -- Step by Step

```java
@KafkaListener(
    topics = "${app.kafka.topics.employee}",        // "employee-avro-topic"
    groupId = "${app.kafka.consumer.group-id}",     // "avro-consumer-group"
    concurrency = "3"                               // 3 threads
)
public void consumeEmployee(ConsumerRecord<String, Employee> record) {
    Employee employee = record.value();
    // ... process it
}
```

When Spring Boot starts, it sees `@KafkaListener` and does this:

```
1. Reads the annotation: topic = "employee-avro-topic", groupId = "avro-consumer-group"

2. Uses kafkaListenerContainerFactory (from KafkaAvroConsumerConfig.java)
   to create a "listener container"

3. The container starts 3 background threads (concurrency = 3)

4. Each thread runs an infinite loop:
     while (running) {
         records = consumer.poll(Duration.ofMillis(100));  // ask Kafka for new messages
         for (record : records) {
             consumeEmployee(record);  // call YOUR method
         }
         consumer.commitOffsets();  // tell Kafka "I processed these"
     }

5. You never see this loop -- Spring hides it. You just write consumeEmployee().
```

#### What is ConsumerRecord?

When Spring calls your `@KafkaListener` method, it passes a `ConsumerRecord<String, Employee>`. This object contains the message **plus** metadata:

```java
public void consumeEmployee(ConsumerRecord<String, Employee> record) {

    // THE DATA:
    String key = record.key();              // "1" (the employee ID as string)
    Employee employee = record.value();     // the deserialized Employee object

    // THE METADATA (useful for debugging/logging):
    String topic = record.topic();          // "employee-avro-topic"
    int partition = record.partition();      // 0, 1, or 2
    long offset = record.offset();          // 42 (sequential ID within partition)
    long timestamp = record.timestamp();    // when the producer sent it
}
```

In this project, the consumer logs all of this:

```java
log.info("Partition    : {}", record.partition());
log.info("Offset       : {}", record.offset());
log.info("Key          : {}", record.key());
log.info("ID           : {}", employee.getId());
log.info("Name         : {}", employee.getName());
log.info("Email        : {}", employee.getEmail());
log.info("Department   : {}", employee.getDepartment());
log.info("Salary       : {}", employee.getSalary());
log.info("Phone Number : {}", employee.getPhoneNumber());   // may be null
log.info("Address      : {}", employee.getAddress());       // may be null
```

#### What About Offsets? (How Kafka Knows You Already Read a Message)

Every message in a partition has a sequential number called an **offset**:

```
Partition 0:   [msg-0] [msg-1] [msg-2] [msg-3] [msg-4] [msg-5]
                                         ^
                                         |
                              committed offset = 3
                              (consumer has processed 0, 1, 2)
                              (next poll returns msg-3, 4, 5)
```

After your `consumeEmployee()` method returns, Spring automatically commits the offset. This tells Kafka: "I have processed this message, do not send it again."

If your consumer crashes at offset 3, when it restarts it resumes from offset 3 -- **no messages are lost and no messages are duplicated** (at-least-once delivery).

---

### Step 4: The Kafka Listener -- Where Messages Arrive

**File:** `src/main/java/com/kafkaPrac/kafka/consumer/KafkaAvroMessageConsumer.java`

The actual code:

```java
@Service
public class KafkaAvroMessageConsumer {

    @KafkaListener(
        topics = "${app.kafka.topics.employee}",
        groupId = "${app.kafka.consumer.group-id}",
        concurrency = "3"
    )
    public void consumeEmployee(ConsumerRecord<String, Employee> record) {
        Employee employee = record.value();

        log.info("Partition    : {}", record.partition());
        log.info("Offset       : {}", record.offset());
        log.info("ID           : {}", employee.getId());
        log.info("Name         : {}", employee.getName());
        // ... logs all fields

        processEmployee(employee);  // your business logic
    }

    private void processEmployee(Employee employee) {
        // Example business logic:
        // - Save to database
        // - Send welcome email
        // - Update HR system

        // Handle optional fields gracefully (schema evolution):
        if (employee.getPhoneNumber() != null) {
            log.debug("Has phone: {}", employee.getPhoneNumber());
        }
        if (employee.getAddress() != null) {
            log.debug("Has address: {}", employee.getAddress());
        }
    }
}
```

**Why no REST controller?** The Producer has a controller because it needs HTTP endpoints for you to send data. The Consumer does not -- it is **event-driven**. Spring's background threads poll Kafka and call your `@KafkaListener` method automatically. There is nothing for you to call.

---

### Step 5: Application Config

**File:** `src/main/resources/application.yaml`

```yaml
server:
  port: 9494                                  # This app runs on port 9494

spring:
  kafka:
    bootstrap-servers: localhost:9092          # Where Kafka is running

schema:
  registry:
    url: http://localhost:8081                 # Where Schema Registry is running

app:
  kafka:
    topics:
      employee: employee-avro-topic           # Topic to listen on
    consumer:
      group-id: avro-consumer-group           # Consumer group name

logging:
  level:
    com.kafkaPrac.kafka: DEBUG                # See detailed logs from your code
```

Every `@Value(...)` and `${...}` in the Java code pulls from this file.

---

### Step 6: Dependencies

**File:** `pom.xml` (project root)

Key dependencies:
- `spring-boot-starter-web` -- Embedded server (for health checks, actuator)
- `spring-kafka` -- Kafka integration + `@KafkaListener` support
- `avro` -- Avro data format
- `kafka-avro-serializer` -- includes `KafkaAvroDeserializer`
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
    |  One of the 3 threads created by kafkaListenerContainerFactory
    |  calls consumer.poll() and detects: "new message at offset 42!"
    |  configured by...
               |
               v
  KafkaAvroConsumerConfig.java                    [config/]
    |  kafkaListenerContainerFactory (LAYER 3)
    |    --> consumerFactory (LAYER 2)
    |      --> consumerConfigs (LAYER 1)
    |  VALUE_DESERIALIZER = KafkaAvroDeserializer
    |  SCHEMA_REGISTRY_URL = http://localhost:8081
    |  SPECIFIC_AVRO_READER = true
               |
               v
  KafkaAvroDeserializer (Confluent library, not your code)
    |  Step A: reads schema ID from the first 5 bytes of the Avro binary
    |  Step B: sends GET http://localhost:8081/schemas/ids/{id} to Schema Registry
    |  Step C: gets back the schema definition (fields, types, defaults)
    |  Step D: uses the schema to deserialize binary bytes into Employee.java object
               |
               v
  KafkaAvroMessageConsumer.java                   [consumer/]
    |  Spring calls consumeEmployee(ConsumerRecord<String, Employee> record)
    |  Employee employee = record.value()
    |  Logs: partition=1, offset=42, id=1, name=John Doe, ...
    |  Calls processEmployee(employee) for business logic
               |
               v
  Spring commits offset 42.
  Kafka marks: "avro-consumer-group has processed partition 1 up to offset 42."
  This message will NOT be delivered again.
```

---

## What Happens at App Startup (Before Any Message)

```
Spring Boot starts
       |
       v
  KafkaAvroConsumerConfig.java
    |  creates consumerConfigs (settings map)
    |  creates consumerFactory (connection factory)
    |  creates kafkaListenerContainerFactory (thread manager)
       |
       v
  application.yaml
    |  provides all config values:
    |    bootstrap-servers = localhost:9092
    |    schema.registry.url = http://localhost:8081
    |    app.kafka.topics.employee = employee-avro-topic
    |    app.kafka.consumer.group-id = avro-consumer-group
       |
       v
  Spring scans for @KafkaListener annotations
    |  finds consumeEmployee() in KafkaAvroMessageConsumer.java
    |  creates a "listener container" for it
       |
       v
  Listener container starts 3 background polling threads
    |  Thread-1 -> assigned Partition 0 -> polls every 100ms
    |  Thread-2 -> assigned Partition 1 -> polls every 100ms
    |  Thread-3 -> assigned Partition 2 -> polls every 100ms
       |
       v
  App is ready on port 9494
  Threads are silently polling Kafka, waiting for messages
  (no REST request needed -- it is fully automatic)
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

#### Real-World Example

```
Scenario: You deploy 2 instances of this consumer app, both with group-id "avro-consumer-group"

  Instance 1 (Thread 1) ----> Partition 0
  Instance 1 (Thread 2) ----> Partition 1
  Instance 2 (Thread 1) ----> Partition 2

Kafka automatically rebalances partitions across all instances in the same group.
Each message goes to exactly ONE consumer. No duplicates.
```

---

## Schema Evolution -- Why Avro is Powerful

What if the Producer adds a new field like `joiningDate`?

| Scenario | What happens to this Consumer? |
|----------|-------------------------------|
| Producer ADDS an optional field (with default) | Consumer ignores it -- **no code change needed** |
| Producer REMOVES an optional field | Consumer gets the default value (null) -- **no crash** |
| Producer CHANGES a field type (int to string) | Schema Registry **rejects it** -- data integrity protected |

This is called **schema evolution** and it is the #1 reason enterprises use Avro over JSON.

In this project, `phoneNumber` and `address` are optional fields. The `processEmployee()` method handles them gracefully:

```java
if (employee.getPhoneNumber() != null) {
    log.debug("Has phone: {}", employee.getPhoneNumber());
}
```

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
