# Transmission

[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/Trendyol/transmission/badge)](https://scorecard.dev/viewer/?uri=github.com/Trendyol/transmission)
![Maven Central Version](https://img.shields.io/maven-central/v/com.trendyol/transmission)

Transmission is a Kotlin Multiplatform library for router-based asynchronous communication between business-logic components.

## Minimal Example

```kotlin
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

## Documentation

- **Online Documentation**: <https://trendyol.github.io/transmission/>
- **Getting Started**: [docs/api-overview.md](docs/api-overview.md)
- **Setup Guide**: [docs/setup.md](docs/setup.md)
- **Generate Locally**: `./gradlew generateDocs`

## License

MIT License. See [LICENSE](LICENSE).
