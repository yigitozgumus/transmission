# Transmission Library API Overview

Transmission is a Kotlin Multiplatform library for router-based asynchronous communication between business-logic components.

## Core Module: `transmission`

The core library provides:

- **`TransmissionRouter`**: central router for processing signals/effects, streaming data/effects, and serving queries.
- **`Transformer`**: base class for business logic. Transformers handle `Transmission.Signal` and `Transmission.Effect` values and can emit `Transmission.Data`.
- **`configure { ... }` / `transformer { ... }`**: composition APIs for declaring handlers, data holders, computations, executions, and lifecycle callbacks together.
- **Handlers**: `onSignal<T>` and `onEffect<T>` route exact transmission types to suspending handler blocks.
- **Data holders**: transformer-owned state that can publish updates to router data streams.
- **Contracts**: typed keys for identities, data holders, computations, executions, and checkpoints.
- **Global router registration**: routers register globally by default so effects and unresolved queries can cross router boundaries.

## Testing Module: `transmission-test`

Testing utilities for transformer and router flows, including helpers for driving signals/effects and asserting emitted data.

## ViewModel Module: `transmission-viewmodel`

ViewModel integration around `TransmissionRouter`:

- **`RouterViewModel`**: creates and owns a router from a transformer set or `TransformerSetLoader`.
- **`RouterViewModelConfig`**: configures router capacity, dispatcher, and identity.
- **Stream helpers**: expose typed data/effect flows and data `StateFlow`s.

## Typical Flow

1. Define `Transmission.Signal`, `Transmission.Effect`, and `Transmission.Data` types.
2. Create one or more `Transformer`s with `configure { ... }`, `transformer { ... }`, or subclass overrides.
3. Install transformers in a `TransmissionRouter` with `addTransformerSet(...)` or `addLoader(...)`.
4. Send inputs with `router.process(signal)`.
5. Observe outputs with `router.streamData<T>()` and `router.streamEffect<T>()`.
