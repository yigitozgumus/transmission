# Global Router Implementation Plan

## Goal

Create a global router/registry that connects individual `TransmissionRouter` instances.

Desired behavior:

- Each `TransmissionRouter` registers itself to the global router by default.
- Registration is controlled by a builder flag, defaulting to `true`.
- Registered routers can listen and react to effects emitted by other routers.
- Registered routers can participate in cross-router queries.
- When a router is cleared, it unregisters from the global router.
- Existing single-router behavior should remain unchanged unless multiple routers are registered globally.

---

## High-Level Design

Introduce a lightweight global coordinator, not a full `TransmissionRouter` instance.

Suggested new type:

```kotlin
object GlobalTransmissionRouter
```

Responsibilities:

```kotlin
GlobalTransmissionRouter
 ├── register(router)
 ├── unregister(router)
 ├── publishEffect(sourceRouter, envelope)
 ├── routeQuery(sourceRouter, query)
 └── routeQueryResult(result)
```

Each normal `TransmissionRouter` keeps its own local bus, query manager, transformers, and lifecycle. The global router only bridges communication between routers.

---

## Public API

Add a builder flag:

```kotlin
TransmissionRouter {
    addTransformerSet(transformers)
    registerToGlobalRouter(true) // default
}
```

Opt out:

```kotlin
TransmissionRouter {
    addTransformerSet(transformers)
    registerToGlobalRouter(false)
}
```

Add to `TransmissionRouterBuilderScope`:

```kotlin
fun registerToGlobalRouter(enabled: Boolean = true)
```

Add builder state:

```kotlin
internal var registerToGlobalRouter: Boolean = true
```

Pass it into `TransmissionRouter` constructor.

---

## Router Lifecycle

### Register

In `TransmissionRouter.init`:

```kotlin
if (registerToGlobalRouter) {
    GlobalTransmissionRouter.register(this)
}
```

Because router initialization is asynchronous, global routing should only consider routers that can actually resolve work. Practically this means either:

```kotlin
router.isInitialized.value == true
```

or simply rely on:

```kotlin
router.canResolve(query)
```

for queries and matching transformer targets for effects.

### Unregister

In `TransmissionRouter.clear()`:

```kotlin
GlobalTransmissionRouter.unregister(this)
globalEffectBridgeJob?.cancel()
transformerSet.forEach { it.clear() }
routerScope.cancel()
```

Unregister before cancelling the router scope to avoid stale references.

---

## Global Registry Storage

Use a KMP-safe state holder instead of JVM-only concurrent collections.

Suggested implementation:

```kotlin
private val routers = MutableStateFlow<Map<String, TransmissionRouter>>(emptyMap())
```

Register:

```kotlin
routers.update { current ->
    check(router.routerName !in current) {
        "Router with identity ${router.routerName} is already registered."
    }
    current + (router.routerName to router)
}
```

Unregister:

```kotlin
routers.update { current ->
    current - router.routerName
}
```

Duplicate router identities should fail fast.

---

## Cross-Router Effects

Current local flow:

```text
Transformer
  -> effectChannel
  -> local TransmissionBus.effectProducer
  -> local transformers
```

To bridge routers, the global router should forward effect envelopes between router buses.

`TransmissionRouter` already has access to:

```kotlin
transmissionBus.effectStream: SharedFlow<TransmissionEnvelope<Transmission.Effect>>
```

### Add a Global Effect Bridge

Inside `TransmissionRouter`:

```kotlin
private var globalEffectBridgeJob: Job? = null
```

When global registration is enabled:

```kotlin
globalEffectBridgeJob = routerScope.launch {
    transmissionBus.effectStream.collect { envelope ->
        GlobalTransmissionRouter.publishEffect(
            sourceRouter = this@TransmissionRouter,
            envelope = envelope
        )
    }
}
```

The global router forwards to other routers:

```kotlin
registeredRouters
    .filterNot { it == sourceRouter }
    .forEach { router ->
        router.receiveGlobalEffect(envelope)
    }
```

Add internal API to `TransmissionRouter`:

```kotlin
internal fun receiveGlobalEffect(
    envelope: TransmissionEnvelope<Transmission.Effect>
)
```

This should inject the effect into the receiving router's local bus:

```kotlin
routerScope.launch {
    transmissionBus.send(envelope)
}
```

Add to `TransmissionBus`:

```kotlin
suspend fun send(envelope: TransmissionEnvelope<Transmission.Effect>) {
    effectBroadcast.producer.send(envelope)
}
```

---

## Effect Loop Prevention

Loop prevention is required.

Without a guard:

1. Router A emits effect.
2. Global router forwards to router B.
3. Router B injects into its local bus.
4. Router B's bridge sees it and forwards globally again.
5. Infinite loop.

### Recommended Solution

Add metadata to `TransmissionEnvelope`:

```kotlin
originRouter: String? = null
```

When a local router publishes globally, set:

```kotlin
originRouter = sourceRouter.routerName
```

Bridge rule:

```kotlin
Only publish an envelope globally when:
- envelope.originRouter == null, or
- envelope.originRouter == this.routerName
```

When router B receives an envelope from router A, the envelope keeps:

```kotlin
originRouter = A.routerName
```

So router B handles it locally but does not republish globally.

---

## Effect Targeting Rules

Current targeted effects use transformer identities:

```kotlin
send(effect, identity)
```

Global behavior should preserve this model.

Recommended rules:

- Broadcast effect (`target == null`) goes to all other globally registered routers.
- Targeted effect (`target != null`) goes only to routers that contain a transformer with that identity.

This requires an internal helper:

```kotlin
internal fun containsTransformer(identity: Contract.Identity): Boolean
```

or inline lookup in the global router:

```kotlin
router.transformerSet.any { it.identity == envelope.target }
```

---

## Cross-Router Queries

Current query flow:

```text
Transformer
  -> TransformerQueryDelegate
  -> router QueryManager.outGoingQuery
  -> QueryManager searches local transformerSet
  -> result returns to local waiter
```

The global router should be a fallback when the local router cannot resolve a query.

### Recommended Approach: Local-First Fallback

Modify `QueryManager` processing:

1. Try local resolution.
2. If a local owner exists, process exactly as today.
3. If no local owner exists, route query through `GlobalTransmissionRouter`.

Example:

```kotlin
val localOwner = routerRef.transformerSet.find { it.storage.hasComputation(query.key) }

if (localOwner != null) {
    processLocally(query, localOwner)
} else {
    GlobalTransmissionRouter.routeQuery(routerRef, query)
}
```

This preserves existing local semantics.

---

## Query Routing APIs

Add internal APIs to `TransmissionRouter`:

```kotlin
internal fun canResolve(query: QueryType): Boolean
internal fun receiveGlobalQuery(query: QueryType)
internal suspend fun receiveGlobalQueryResult(result: QueryResult)
```

`canResolve`:

```kotlin
internal fun canResolve(query: QueryType): Boolean {
    return when (query) {
        is QueryType.Data<*> ->
            transformerSet.any { it.storage.isHolderDataDefined(query.key) }

        is QueryType.Computation<*> ->
            transformerSet.any { it.storage.hasComputation(query.key) }

        is QueryType.ComputationWithArgs<*, *> ->
            transformerSet.any { it.storage.hasComputation(query.key) }

        is QueryType.Execution ->
            transformerSet.any { it.storage.hasExecution(query.key) }

        is QueryType.ExecutionWithArgs<*> ->
            transformerSet.any { it.storage.hasExecution(query.key) }
    }
}
```

`GlobalTransmissionRouter.routeQuery`:

```kotlin
internal fun routeQuery(
    sourceRouter: TransmissionRouter,
    query: QueryType,
) {
    val targetRouter = routers.value.values
        .filterNot { it == sourceRouter }
        .firstOrNull { it.canResolve(query) }
        ?: return

    targetRouter.receiveGlobalQuery(query)
}
```

---

## Query Result Routing

When a target router processes a query whose `sender` is another router name, it should return the result through the global router.

Current `QueryManager` branches like this:

```kotlin
if (query.sender == routerRef.routerName) {
    routerQueryResultChannel.emit(result)
} else {
    queryResultChannel.send(result)
}
```

Update to:

```kotlin
if (query.sender == routerRef.routerName) {
    routerQueryResultChannel.emit(result)
} else if (GlobalTransmissionRouter.isRegistered(query.sender)) {
    GlobalTransmissionRouter.routeQueryResult(result)
} else {
    queryResultChannel.send(result)
}
```

Global router:

```kotlin
internal fun routeQueryResult(result: QueryResult) {
    routers.value[result.owner]?.receiveGlobalQueryResult(result)
}
```

`receiveGlobalQueryResult` should emit into the source router's waiting query result channel.

This likely requires adding an internal method to `QueryManager`:

```kotlin
internal suspend fun receiveGlobalQueryResult(result: QueryResult) {
    routerQueryResultChannel.emit(result)
}
```

---

## Files to Modify

### New files

```text
transmission/src/commonMain/kotlin/com/trendyol/transmission/router/GlobalTransmissionRouter.kt
```

### Existing files

```text
transmission/src/commonMain/kotlin/com/trendyol/transmission/router/TransmissionRouter.kt
transmission/src/commonMain/kotlin/com/trendyol/transmission/router/TransmissionBus.kt
transmission/src/commonMain/kotlin/com/trendyol/transmission/router/TransmissionEnvelope.kt
transmission/src/commonMain/kotlin/com/trendyol/transmission/router/QueryManager.kt
transmission/src/commonMain/kotlin/com/trendyol/transmission/router/builder/TransmissionRouterBuilder.kt
transmission/src/commonMain/kotlin/com/trendyol/transmission/router/builder/TransmissionRouterBuilderScope.kt
transmission/src/commonMain/kotlin/com/trendyol/transmission/router/builder/TransmissionRouterBuilderScopeImpl.kt
```

---

## Tests to Add

Add tests for:

1. Router auto-registers globally by default.
2. Router does not register when `registerToGlobalRouter(false)` is used.
3. Router unregisters when `clear()` is called.
4. Effect emitted in router A is handled by router B.
5. Cross-router effect does not loop indefinitely.
6. Targeted effect only reaches the router containing the target transformer identity.
7. Data query from router A resolves a data holder in router B.
8. Computation query from router A resolves computation in router B.
9. Computation-with-args query from router A resolves computation in router B.
10. Execution query from router A triggers execution in router B.
11. Execution-with-args query from router A triggers execution in router B.
12. Duplicate router identity fails registration.
13. Clearing a target router removes it from future global routing.

---

## Edge Cases

### Duplicate Contract Ownership

If two globally registered routers can resolve the same contract, routing is ambiguous.

Initial v1 behavior can be:

```text
first registered router wins
```

But this should be documented.

Future improvement:

```kotlin
validateGlobalContracts()
```

or explicit owner-aware query APIs:

```kotlin
compute(contract, owner = AuthRouterIdentity)
```

### Duplicate Router Identities

Should fail fast during registration.

### Query Timeout

Current local unresolved queries may already suspend indefinitely. Cross-router unresolved queries would have the same issue.

Do not solve this in the first implementation unless required. Consider a later API:

```kotlin
queryTimeout(duration)
```

### Router Initialization Timing

Routers may register before initialization completes. Global query resolution should use `canResolve(query)`, which naturally returns false until transformers are initialized.

### Target Identity Uniqueness

Global targeted effects assume transformer identities are globally unique. If not, multiple routers may receive a targeted effect.

Document this expectation.

---

## Suggested Implementation Phases

### Phase 1: Global effects

Implement:

- builder flag
- global registration/unregistration
- effect bridge
- loop prevention
- targeted effect filtering
- effect tests

This is lower risk because it mainly touches the bus layer.

### Phase 2: Global queries

Implement:

- query fallback from local router to global router
- query result return path
- data/computation/execution cross-router support
- query tests

This is higher risk because it touches `QueryManager` internals.

### Phase 3: Safety and ergonomics

Implement optional improvements:

- duplicate global contract validation
- owner-aware query APIs
- query timeouts
- debug logging/inspection APIs for registered routers

---

## Preferred First PR Scope

Keep the first PR focused:

- Add global registration flag.
- Add `GlobalTransmissionRouter` registry.
- Add cross-router effects with loop prevention.
- Add tests for registration, unregistering, broadcast effects, targeted effects, and loop prevention.

Then follow up with cross-router query support in a second PR.
