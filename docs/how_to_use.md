# How To Use

This guide shows the current public API for a small Transmission setup.

## 1. Define Transmissions

```kotlin
import com.trendyol.transmission.Transmission

object IncrementCounterSignal : Transmission.Signal
data class UpdateTextSignal(val text: String) : Transmission.Signal

data class LoggingEffect(val message: String) : Transmission.Effect
object RefreshUIEffect : Transmission.Effect

data class CounterData(val count: Int) : Transmission.Data
data class TextData(val text: String) : Transmission.Data
```

## 2. Create Transformers

`configure { ... }` is the recommended composition API for new examples. Subclass overrides such as `override val handlers = handlers { ... }` still work, but they are deprecated in favor of colocating configuration in one block.

```kotlin
import com.trendyol.transmission.transformer.Transformer
import com.trendyol.transmission.transformer.configure
import com.trendyol.transmission.transformer.request.Contract

class CounterTransformer : Transformer() {
    private var count = 0

    private val counterDataHolder = dataHolder(
        initialValue = CounterData(0),
        contract = counterDataContract,
    )

    init {
        configure {
            onSignal<IncrementCounterSignal> {
                count += 1
                publish(LoggingEffect("Counter incremented to $count"))
                counterDataHolder.update { CounterData(count) }
            }

            onEffect<RefreshUIEffect> {
                counterDataHolder.update { CounterData(count) }
            }
        }
    }

    companion object {
        val counterDataContract = Contract.dataHolder<CounterData>()
    }
}

class TextTransformer : Transformer() {
    private val textDataHolder = dataHolder(
        initialValue = TextData(""),
        contract = textDataContract,
    )

    init {
        configure {
            onSignal<UpdateTextSignal> { signal ->
                textDataHolder.update { TextData(signal.text) }
                publish(LoggingEffect("Text updated to ${signal.text}"))
            }
        }
    }

    companion object {
        val textDataContract = Contract.dataHolder<TextData>()
    }
}

class LoggingTransformer : Transformer() {
    init {
        configure {
            onEffect<LoggingEffect> { effect ->
                println("Log: ${effect.message}")
            }
        }
    }
}
```

For small examples and tests, you can also create an anonymous transformer:

```kotlin
val loggingTransformer = transformer {
    onEffect<LoggingEffect> { effect -> println(effect.message) }
}
```

## 3. Set Up `TransmissionRouter`

```kotlin
import com.trendyol.transmission.router.builder.TransmissionRouter

val router = TransmissionRouter {
    addTransformerSet(
        setOf(
            CounterTransformer(),
            TextTransformer(),
            LoggingTransformer(),
        )
    )
}
```

## 4. Process Inputs and Observe Outputs

```kotlin
router.process(IncrementCounterSignal)
router.process(UpdateTextSignal("Hello"))

lifecycleScope.launch {
    router.streamData<CounterData>().collect { data ->
        countTextView.text = "Count: ${data.count}"
    }
}

lifecycleScope.launch {
    router.streamData<TextData>().collect { data ->
        outputTextView.text = data.text
    }
}
```

## 5. Use RouterViewModel When Appropriate

```kotlin
class MainViewModel : RouterViewModel(
    setOf(
        CounterTransformer(),
        TextTransformer(),
        LoggingTransformer(),
    )
) {
    val counterState = streamDataAsState(CounterData(0))
    val textState = streamDataAsState(TextData(""))

    fun incrementCounter() {
        processSignal(IncrementCounterSignal)
    }

    fun updateText(text: String) {
        processSignal(UpdateTextSignal(text))
    }

    fun refreshUI() {
        processEffect(RefreshUIEffect)
    }
}
```

## 6. Query Other Transformers with Contracts

```kotlin
val counterValueContract = Contract.computation<Int>()

class CounterTransformer : Transformer() {
    private var count = 0

    init {
        configure {
            computation(counterValueContract) {
                count
            }
        }
    }
}

class AnotherTransformer : Transformer() {
    init {
        configure {
            onSignal<SomeSignal> {
                val currentCount = compute(counterValueContract)
                send(SomeData(currentCount))
            }
        }
    }
}
```

## Notes

- `publish(effect)` broadcasts an effect.
- `send(effect, identity)` sends an effect to a transformer with a matching `Contract.Identity`.
- `send(data)` emits data to router data streams.
- Router capacity is configured with `Capacity.Default`, `Capacity.Custom(value)`, or `Capacity.Unlimited`.
