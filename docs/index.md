# Transmission

Transmission is an experimental asynchronous communication library for Kotlin Multiplatform projects. It helps business-logic components communicate through typed **Signals**, **Effects**, **Data**, and contract-based queries without holding direct references to each other.

## Quick Start

```kotlin
import com.trendyol.transmission.Transmission
import com.trendyol.transmission.router.builder.TransmissionRouter
import com.trendyol.transmission.router.streamData
import com.trendyol.transmission.transformer.Transformer
import com.trendyol.transmission.transformer.configure

object Increment : Transmission.Signal
data class CounterData(val count: Int) : Transmission.Data

class CounterTransformer : Transformer() {
    private var count = 0

    init {
        configure {
            onSignal<Increment> {
                count += 1
                send(CounterData(count))
            }
        }
    }
}

val router = TransmissionRouter {
    addTransformerSet(setOf(CounterTransformer()))
}

router.process(Increment)

router.streamData<CounterData>().collect { data ->
    println(data.count)
}
```

## Core Concepts

- **Signals**: inputs from UI or external events.
- **Effects**: asynchronous messages between transformers. Effects can be broadcast with `publish(effect)` or targeted with `send(effect, identity)`.
- **Data**: output values observed from router streams.
- **Transformers**: business-logic units that handle signals/effects, update data holders, and expose computations or executions.
- **TransmissionRouter**: coordinates transformers, streams data/effects, and routes queries.
- **Contracts**: typed keys for identities, data holders, computations, executions, and checkpoints.

## Documentation

- **[Getting Started](how_to_use.md)** - Learn the basics and start using Transmission
- **[Setup Guide](setup.md)** - Installation and configuration instructions
- **[Transmissions](transmissions.md)** - Signal, Effect, and Data types
- **[Transformers](transformer.md)** - Core business-logic components
- **[TransmissionRouter](router.md)** - Router configuration and global routing
- **[DataHolder](dataholder.md)** - Transformer-owned state
- **[Handlers](handlers.md)** - Signal/effect handlers
- **[Contracts](contracts.md)** - Type-safe inter-transformer contracts
- **[Transformer Communication](transformer_communication.md)** - Effects, computations, executions, and checkpoints
- **[Testing](testing.md)** - Testing strategies and utilities

## API Reference

📚 **[API Documentation](api/0.x/)** - Generated Dokka reference for public classes and methods.
