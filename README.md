# KVraft — Distributed Key-Value Store

A distributed key-value store built from scratch in Java, without any distributed systems frameworks. Designed to demonstrate production-grade concepts: consistent hashing, quorum replication, failure detection, and inter-node connection pooling.

> Closer in design philosophy to a baby **etcd** than Redis. Synchronous quorum writes guarantee durability even with node failures, unlike Redis's async replication.

---

## Table of Contents

- [Features](#features)
- [Architecture Overview](#architecture-overview)
- [Component Deep Dive](#component-deep-dive)
  - [Consistent Hashing Ring](#consistent-hashing-ring)
  - [Quorum Replication](#quorum-replication)
  - [Async Background Replication](#async-background-replication)
  - [Versioned Values & Conflict Resolution](#versioned-values--conflict-resolution)
  - [Heartbeat Failure Detection](#heartbeat-failure-detection)
  - [Connection Pool (NodeConnectionPool)](#connection-pool-nodeconnectionpool)
- [Request Lifecycle](#request-lifecycle)
  - [Write Path (PUT)](#write-path-put)
  - [Read Path (GET)](#read-path-get)
- [Project Structure](#project-structure)
- [Getting Started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Build](#build)
  - [Run a 3-Node Cluster](#run-a-3-node-cluster)
  - [Run the Benchmark Client](#run-the-benchmark-client)
- [Configuration Reference](#configuration-reference)
- [Design Decisions](#design-decisions)
- [Tradeoffs & Known Limitations](#tradeoffs--known-limitations)

---

## Features

| Feature | Details |
|---|---|
| **Consistent Hashing** | 100 virtual nodes per physical node, `TreeMap`-backed ring for O(log N) lookups |
| **Quorum Writes** | W=2, R=2, N=3 — tolerates 1 node failure without data loss |
| **Async Replication** | Background thread syncs writes to a third replica to reduce write-path latency |
| **Versioned Values** | Every value carries a timestamp; quorum reads resolve conflicts by taking the latest version |
| **Failure Detection** | Heartbeat-based monitor detects down nodes, removes them from the ring, and auto-recovers them when they return |
| **Delete Replication** | DELETE commands are replicated across the quorum, consistent with PUT semantics |
| **Persistent Inter-node Connections** | `NodeConnectionPool` maintains one TCP connection per peer node, eliminating handshake overhead and port exhaustion |
| **TCP Server** | Plain TCP with a text-line protocol; no HTTP or gRPC dependency |

---

## Architecture Overview

```
                         ┌─────────────────────────────────────────────────────────┐
                         │                   CLIENT / BENCHMARK                    │
                         └─────────────────────┬───────────────────────────────────┘
                                               │  TCP (text protocol)
                                               ▼
                         ┌─────────────────────────────────────────────────────────┐
                         │                   ANY NODE (Router)                     │
                         │                                                         │
                         │  1. Hash key  →  ConsistentHashRing.getNode(key)        │
                         │  2. If this node owns the key → handle locally          │
                         │  3. Else → NodeConnectionPool.forward(ownerNode, cmd)   │
                         └─────────────────────────────────────────────────────────┘
                                               │
                              ┌────────────────┴────────────────┐
                              │        COORDINATOR NODE         │
                              │                                 │
                              │  QuorumWriter.write(key, value) │
                              │   ├─ Write to self (replica 0)  │
                              │   ├─ Write to replica 1 (sync)  │  W = 2
                              │   └─ Write to replica 2 (async) │  background
                              └────────────────┬────────────────┘
                                               │
               ┌───────────────────────────────┼───────────────────────────────┐
               ▼                               ▼                               ▼
  ┌────────────────────┐         ┌────────────────────┐         ┌────────────────────┐
  │      NODE A        │         │      NODE B        │         │      NODE C        │
  │   localhost:7001   │         │   localhost:7002   │         │   localhost:7003   │
  │                    │         │                    │         │                    │
  │  ┌──────────────┐  │         │  ┌──────────────┐  │         │  ┌──────────────┐  │
  │  │  LocalStore  │  │         │  │  LocalStore  │  │         │  │  LocalStore  │  │
  │  │  (HashMap)   │  │         │  │  (HashMap)   │  │         │  │  (HashMap)   │  │
  │  └──────────────┘  │         │  └──────────────┘  │         │  └──────────────┘  │
  │  ┌──────────────┐  │         │  ┌──────────────┐  │         │  ┌──────────────┐  │
  │  │  Heartbeat   │  │◄────────┤  │  Heartbeat   │  │◄────────┤  │  Heartbeat   │  │
  │  │   Monitor    │  │         │  │   Monitor    │  │         │  │   Monitor    │  │
  │  └──────────────┘  │         │  └──────────────┘  │         │  └──────────────┘  │
  └────────────────────┘         └────────────────────┘         └────────────────────┘
           │                                │                               │
           └────────────────────────────────┴───────────────────────────────┘
                               NodeConnectionPool
                         (persistent TCP per peer node)
```

---

## Component Deep Dive

### Consistent Hashing Ring

```
Ring (0 ────────────────────────────── MAX_LONG)

  Virtual nodes spread evenly:
  A_0   B_0   C_0   A_1   B_1   C_1   A_2   B_2   C_2  ...  (100 vnodes each)
   │           │           │
   ▼           ▼           ▼
  Node A     Node C     Node B    ← clockwise lookup: key hashes to nearest node
```

- **Why virtual nodes?** Without them, adding/removing a physical node moves ~1/N of the keyspace. With 100 virtual nodes per node, the load distributes evenly and ring rebalancing affects only a small fraction of keys.
- **Implementation:** `TreeMap<Long, String>` where keys are hash positions. `tailMap(hash).firstKey()` gives the O(log N) clockwise successor.
- **Replication:** The coordinator for a key is its primary node. The next N-1 clockwise physical nodes are its replicas.

---

### Quorum Replication

```
Write quorum  W = 2  (must acknowledge before returning OK to client)
Read quorum   R = 2  (must respond before returning value to client)
Replication   N = 3  (total copies of each key)

Guarantee: W + R > N  →  2 + 2 > 3  ✓
           At least one node in any read quorum overlaps any write quorum.
           → Reads always see the latest committed write.
```

On a PUT, the coordinator:
1. Writes to itself (replica 0) — synchronous
2. Forwards `REPLICATE key value timestamp` to replica 1 — synchronous (waits for OK)
3. Queues a background task to forward to replica 2 — asynchronous

Steps 1+2 satisfy W=2. Step 3 improves durability without adding latency.

---

### Async Background Replication

A `ScheduledExecutorService` drains a `BlockingQueue` of pending replication tasks. This decouples the write-path latency from the third replica's availability.

```
PUT key value
      │
      ├─ sync ──► replica 0 (self)       ─┐
      ├─ sync ──► replica 1              ─┴─ W=2 satisfied → return OK to client
      │
      └─ enqueue ──► BackgroundReplicator ──► replica 2 (best effort)
```

If replica 2 is down when the task runs, the task is retried. When the heartbeat monitor detects replica 2 has recovered, it triggers a full key sync.

---

### Versioned Values & Conflict Resolution

Every stored value is wrapped with a `System.currentTimeMillis()` timestamp:

```
LocalStore entry:  key → { value: "alice", version: 1700000001234 }
```

During a quorum read (R=2), the coordinator collects responses from 2 nodes and returns the value with the **highest version**. This resolves the scenario where:

- Node B was briefly partitioned during a write
- Node B still holds a stale version of a key
- The read quorum includes both A (latest) and B (stale) → A's version wins

> This is the same "last-write-wins" conflict resolution strategy used by Cassandra.

---

### Heartbeat Failure Detection

Each node runs a background `HeartbeatMonitor` that pings all known peer nodes every 3 seconds:

```
HeartbeatMonitor (runs on every node)
  │
  ├─ Every 3s: send PING to all peers
  │
  ├─ No response after 2 missed beats → mark node DOWN
  │     └─ Remove from ConsistentHashRing
  │           └─ Future requests route around the dead node
  │
  └─ Node responds again → mark node UP
        └─ Re-add to ConsistentHashRing
              └─ Trigger key sync for keys the recovered node missed
```

---

### Connection Pool (NodeConnectionPool)

The old `Router.forward()` opened a new TCP connection per forwarded request — causing O(4) TCP packet overhead per forward and port exhaustion under benchmark load (Windows `TIME_WAIT` lasts 60s, ephemeral port range ~16K).

```
OLD:  request → [SYN] [SYN-ACK] [ACK] send recv [FIN] [FIN-ACK]  every time
NEW:  request → send recv                                          reuses socket
```

`NodeConnectionPool` keeps one `NodeConnection` (socket + reader + writer) per peer address in a `ConcurrentHashMap`. `NodeConnection.send()` is `synchronized` — only one thread sends on a connection at a time. On `IOException`, it reconnects once and retries automatically.

---

## Request Lifecycle

### Write Path (PUT)

```
Client sends:   PUT user:42 Alice

1. Any node receives the command via TCP
2. Router hashes "user:42" → finds owner node on ring (say Node B)
3. If receiver ≠ Node B → forward via NodeConnectionPool to Node B
4. Node B (coordinator):
     a. Write locally:  store.put("user:42", VersionedValue("Alice", now()))
     b. Forward REPLICATE user:42 Alice <timestamp> to Node C (sync, W=2)
     c. Enqueue REPLICATE to Node A (async background)
5. Return OK to client when a+b complete
```

### Read Path (GET)

```
Client sends:   GET user:42

1. Router routes to coordinator Node B
2. Node B issues GET to itself + Node C (quorum R=2)
3. Compare timestamps:  Node B: v=1700001  Node C: v=1700001  → same, return "Alice"
4. Conflict scenario:   Node B: v=1700002  Node C: v=1699999  → return Node B's value
```

---

## Project Structure

```
DistributedKV/
├── build.gradle
├── README.md
│
└── src/
    └── main/
        └── java/
            ├── server/
            │   ├── KVServer.java           # Entry point; starts TCP listener + background services
            │   └── CommandHandler.java     # Parses and dispatches GET/PUT/DELETE/REPLICATE/PING
            │
            ├── store/
            │   ├── LocalStore.java         # Thread-safe in-memory HashMap with versioned values
            │   └── VersionedValue.java     # Value wrapper: { data, timestamp }
            │
            ├── ring/
            │   └── ConsistentHashRing.java # TreeMap-backed ring; 100 vnodes per node
            │
            ├── router/
            │   ├── Router.java             # Routes commands to correct node via ring lookup
            │   ├── NodeConnectionPool.java # ConcurrentHashMap of persistent peer connections
            │   └── NodeConnection.java     # Single persistent TCP connection to one peer
            │
            ├── replication/
            │   ├── QuorumWriter.java       # Orchestrates W=2 sync + async background write
            │   ├── QuorumReader.java       # Collects R=2 responses, resolves by max version
            │   └── BackgroundReplicator.java # BlockingQueue consumer; retries on failure
            │
            └── health/
                └── HeartbeatMonitor.java   # Pings peers; adds/removes nodes from ring
```

---

## Getting Started

### Prerequisites

- Java 17+
- Gradle 8+
- Windows CMD (project tested on Windows)

### Build

```cmd
cd C:\Users\rahul\Downloads\Home_Project\DistributedKV
gradlew build
```

### Run a 3-Node Cluster

Open three separate CMD windows:

**Window 1 — Node A (port 7001)**
```cmd
cd C:\Users\rahul\Downloads\Home_Project\DistributedKV
gradlew run --args="7001 localhost:7002 localhost:7003"
```

**Window 2 — Node B (port 7002)**
```cmd
cd C:\Users\rahul\Downloads\Home_Project\DistributedKV
gradlew run --args="7002 localhost:7001 localhost:7003"
```

**Window 3 — Node C (port 7003)**
```cmd
cd C:\Users\rahul\Downloads\Home_Project\DistributedKV
gradlew run --args="7003 localhost:7001 localhost:7002"
```

### Manual Commands via Telnet

```cmd
telnet localhost 7001
```
```
PUT name Alice
OK
GET name
Alice
DELETE name
OK
```

### Run the Benchmark Client

```cmd
gradlew runBenchmark --args="localhost:7001 1000 10"
```
Arguments: `<node-address> <num-operations> <concurrency>`

---

## Configuration Reference

| Parameter | Default | Description |
|---|---|---|
| `N` | 3 | Total replicas per key |
| `W` | 2 | Write quorum (synchronous) |
| `R` | 2 | Read quorum |
| `VIRTUAL_NODES` | 100 | Virtual nodes per physical node on ring |
| `HEARTBEAT_INTERVAL_MS` | 3000 | How often to ping peers |
| `HEARTBEAT_MISS_THRESHOLD` | 2 | Missed pings before node marked DOWN |

---

## Design Decisions

**Why not use Zookeeper / etcd for coordination?**
Building coordination from scratch (heartbeats, ring membership, quorum) was the entire point — understanding *why* those tools exist rather than just using them.

**Why synchronous W=2 instead of fully async replication (like Redis)?**
Redis's async replication can lose acknowledged writes if the primary crashes before replication completes. KVraft's W=2 guarantees that 2 copies exist on disk before the client gets `OK`. This is the same durability guarantee as Cassandra's quorum writes.

**Why consistent hashing instead of a static partition map?**
Consistent hashing means adding or removing a node only moves ~1/N of the keyspace. A static modulo-based partition map would require rehashing *all* keys on topology changes.

**Why persistent connections instead of a full connection pool?**
A full pool (multiple connections per node) adds scheduling complexity with no benefit for this workload — inter-node forwarding is already serialized per key by the ring. One persistent connection per peer eliminates `TIME_WAIT` exhaustion while keeping the implementation simple.

**Why last-write-wins conflict resolution?**
For this store's use case (single-writer per key via consistent hash routing), LWW is correct and simple. A multi-writer scenario would require vector clocks (Dynamo-style) or CRDTs.

---

## Tradeoffs & Known Limitations

| Limitation | Explanation |
|---|---|
| **No WAL / persistence** | All data is in-memory. Node restart = data loss for keys that node owned. |
| **No leader election** | No Raft/Paxos. Coordinator is determined purely by ring position — a partitioned "coordinator" could cause split-brain. |
| **LWW conflict resolution** | Clock skew between nodes could theoretically return a stale value. Production systems use hybrid logical clocks (HLC) to mitigate this. |
| **Single connection per peer** | `NodeConnection.send()` is `synchronized` — high fan-out workloads would benefit from a true connection pool with parallel quorum write fan-out via `ExecutorService`. |
| **Static cluster membership** | Nodes are passed as startup arguments. Dynamic membership (join/leave protocol) is not implemented. |
