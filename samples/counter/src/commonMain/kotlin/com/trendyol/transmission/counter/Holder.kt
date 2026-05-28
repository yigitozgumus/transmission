package com.trendyol.transmission.counter

import com.trendyol.transmission.Transmission
import com.trendyol.transmission.transformer.Transformer
import com.trendyol.transmission.transformer.configure
import com.trendyol.transmission.transformer.dataholder.dataHolder
import com.trendyol.transmission.transformer.request.Contract

val lookUpAndReturn = Contract.computationWithArgs<String, Int>()

class Holder : Transformer() {

    data class TestCounter(val value: Int) : Transmission.Data

    val counterData = dataHolder(TestCounter(0))

    init {
        configure {
            computation(lookUpAndReturn) { id ->
                counterData.updateAndGet { it.copy(value = it.value.plus(1)) }.value
            }
        }
    }
}
