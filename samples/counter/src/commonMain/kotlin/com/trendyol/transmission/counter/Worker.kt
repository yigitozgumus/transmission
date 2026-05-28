package com.trendyol.transmission.counter

import com.trendyol.transmission.transformer.Transformer
import com.trendyol.transmission.transformer.configure

class Worker(val id: String) : Transformer() {

    init {
        configure {
            onSignal<CounterSignal.Lookup> {
                send(CounterData("Transformer $id updated data to ${compute(lookUpAndReturn, id)}"))
            }
        }
    }
}
