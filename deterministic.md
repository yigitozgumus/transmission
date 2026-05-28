# Deterministic Concurrency and Performance Improvements

Goal: improve Transmission's concurrency performance while keeping behavior deterministic across runs.

## Principles

- Determinism is the default: same inputs should produce the same observable data/effect/query ordering.
- Prefer concurrency across independent transformers over uncontrolled concurrency inside a single transformer.
- Make ordering explicit with stable registration order and sequence numbers where needed.
- Avoid scheduler-dependent behavior in user-visible outputs.
- Optimize hot paths without adding speculative complexity.

## Current Risks

### Per-message coroutine launches inside transformers

`Transformer.startSignalCollection` and `Transformer.startEffectProcessing` currently launch a new coroutine per incoming message. This can make handler completion order scheduler-dependent when multiple signals/effects arrive quickly.

Risk:

- nondeterministic data emission order
- nondeterministic effect emission order
- races when handlers update shared transformer state

### Broadcast-to-all routing

Signals/effects are broadcast to all transformers, and each transformer checks whether it has a handler. This is simple but inefficient when many transformers exist.

Risk:

- unnecessary flow collection work
- unnecessary dispatch lookups
- lower throughput as transformer count grows

### Query result filtering per request

`QueryManager` waits for results by creating flow filters per query:

```kotlin
routerQueryResultChannel
    .filterIsInstance<...>()
    .filter { it.resultIdentifier == queryIdentifier && it.key == contract.key }
    .first()
```

Risk:

- extra allocations
- O(number of active queries) filtering behavior
- more coroutine/flow overhead than needed

### Mutable storage without synchronization

`TransformerStorage` uses mutable maps. This is safe only if access is single-writer or otherwise externally serialized.

Risk:

- races if transformer handlers run concurrently
- nondeterministic query/data results

### DataHolder update/read split

`TransmissionDataHolderImpl.update` currently updates the `MutableStateFlow`, then reads `holder.value` separately.

Risk:

- another update can occur between update and read
- storage/data publishing may observe a later value than the updater produced

## Recommended Architecture

## 1. Deterministic per-transformer mailbox

Each transformer should process its own messages in receive order.

Model:

```text
Router
 ├─ Transformer A mailbox: msg1 -> msg2 -> msg3
 ├─ Transformer B mailbox: msg1 -> msg2 -> msg3
 └─ Transformer C mailbox: msg1 -> msg2 -> msg3
```

Benefits:

- deterministic state updates inside each transformer
- deterministic emissions from each transformer
- concurrency still exists across transformers
- `TransformerStorage` remains effectively single-writer for handler-driven access

Implementation direction:

- Replace per-message `launch` in signal/effect collection with ordered mailbox processing.
- Keep one processing coroutine per transformer mailbox.
- If signals and effects share state, use one combined mailbox to preserve total per-transformer order.
- If signal/effect ordering can remain independent by design, use two separate mailboxes but document the semantics.

Default should be deterministic sequential processing inside each transformer.

## 2. Route only to interested transformers

Build routing indexes during router initialization:

```kotlin
Map<KClass<out Transmission.Signal>, List<Transformer>>
Map<KClass<out Transmission.Effect>, List<Transformer>>
```

Then dispatch a signal/effect only to transformers that registered matching handlers.

Benefits:

- avoids broadcasting every message to every transformer
- improves throughput when transformer count grows
- keeps routing order stable if transformer registration order is stable

Requirements:

- expose registered signal/effect route keys from `HandlerRegistry`/`RouteRegistry`
- preserve transformer registration order
- define deterministic behavior for duplicate handlers and multiple matching transformers

## 3. Stable transformer registration order

Use an ordered collection internally for transformers.

Current public type can stay as `Set<Transformer>`, but initialization/routing should preserve insertion order.

Benefits:

- deterministic routing order
- deterministic query resolver selection
- deterministic conflict reporting

Implementation options:

- use `LinkedHashSet` internally
- or store an internal `List<Transformer>` after loading

## 4. Validate duplicate contracts deterministically

Queries currently use first matching transformer for computations/executions/data holders. If duplicates exist, first-match behavior can become surprising.

Recommended:

- validate duplicate local contracts during initialization
- keep existing global validation option, but consider enabling deterministic validation by default in a new deterministic mode
- sort conflict messages for stable errors

Benefits:

- removes ambiguous query resolution
- makes failures reproducible and easier to debug

## 5. Replace query result flow filtering with pending request map

Use a pending-query table keyed by query identifier:

```kotlin
private val pendingQueries = mutableMapOf<String, CompletableDeferred<QueryResult>>()
```

Flow:

1. Create `CompletableDeferred`.
2. Store it by query identifier.
3. Send query.
4. Complete deferred when result arrives.
5. Remove pending entry in `finally`.

Benefits:

- O(1) result delivery
- fewer flow operators and allocations
- deterministic direct matching by query identifier

Need to protect the pending map with either:

- single query manager actor/mailbox, or
- `Mutex`

Prefer actor/mailbox if we want deterministic ordering.

## 6. Atomic data holder update

Change `TransmissionDataHolderImpl.update` from update-then-read to `updateAndGet`.

Current pattern:

```kotlin
holder.update(updater)
val holderData = holder.value ?: return
```

Recommended:

```kotlin
val holderData = holder.updateAndGet(updater) ?: return
```

Benefits:

- publishes exactly the value produced by this update
- avoids a race between update and read
- reduces extra state reads

## 7. Add lower-overhead process APIs

Current `process` launches a coroutine for every signal/effect send.

Recommended additions:

```kotlin
suspend fun send(signal: Transmission.Signal)
suspend fun send(effect: Transmission.Effect)
fun tryProcess(signal: Transmission.Signal): Boolean
fun tryProcess(effect: Transmission.Effect): Boolean
```

Keep existing `process` for compatibility.

Benefits:

- lets hot paths avoid extra coroutine allocation
- gives callers explicit backpressure choices
- preserves current API behavior

## 8. Optional deterministic parallel mode

After deterministic sequential processing is solid, add opt-in parallel deterministic processing.

Idea:

- assign monotonically increasing sequence numbers to incoming envelopes
- run independent work concurrently
- buffer outputs
- release outputs in sequence order

This can improve performance for CPU-heavy independent handlers while preserving observable order.

Do not make this the default initially. It is more complex and should be backed by stress tests.

## Testing Plan

Add deterministic stress tests that run the same scenario many times and compare outputs.

Test categories:

1. Concurrent `router.process(signal)` calls produce stable final data.
2. Concurrent signals produce stable data emission order.
3. Effects emitted by multiple transformers are routed in stable order.
4. DataHolder updates publish the exact updated value.
5. Query results resolve to the same transformer every run.
6. Duplicate contracts fail with stable error messages.
7. Global router effect forwarding remains deterministic.
8. High transformer count routing avoids dispatching to uninterested transformers.

Performance tests/benchmarks:

1. Broadcast-to-all vs indexed routing.
2. Flow-filter query matching vs pending deferred map.
3. Per-message coroutine launch vs per-transformer mailbox.
4. Existing `process` vs suspend/try process APIs.

## Suggested Implementation Order

### Phase 1: Low-risk correctness and small performance wins

- Use `updateAndGet` in `TransmissionDataHolderImpl.update`.
- Preserve stable transformer registration order internally.
- Add duplicate local contract validation with deterministic messages.
- Add stress tests for data holder updates and query resolution.

### Phase 2: Deterministic transformer processing

- Introduce per-transformer mailbox processing.
- Remove uncontrolled per-message handler launches.
- Add tests for repeated concurrent signal/effect processing.

### Phase 3: Indexed routing

- Expose handler route keys from route registries.
- Build signal/effect routing indexes during router initialization.
- Route only to interested transformers.
- Add performance tests for many-transformer scenarios.

### Phase 4: Query manager optimization

- Replace query result flow filtering with pending deferred map or actor.
- Add concurrent query stress tests.

### Phase 5: Optional advanced parallelism

- Add sequence numbers to envelopes.
- Add opt-in deterministic parallel processing mode.
- Gate emitted data/effects by sequence order.
- Benchmark against default sequential mailbox mode.

## Non-goals

- Do not make handler execution nondeterministic for raw throughput.
- Do not change public behavior without compatibility wrappers.
- Do not introduce complex parallel scheduling until deterministic sequential processing is verified.
- Do not optimize visualizer/sample modules as part of the core concurrency work.
