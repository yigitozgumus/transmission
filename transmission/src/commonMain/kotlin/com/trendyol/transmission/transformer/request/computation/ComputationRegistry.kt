package com.trendyol.transmission.transformer.request.computation

import com.trendyol.transmission.transformer.Transformer
import com.trendyol.transmission.transformer.request.QueryHandler

internal class ComputationRegistry internal constructor(private val transformer: Transformer) {

    internal fun clear() {
        transformer.storage.clearComputations()
    }

    internal fun <T : Any?> buildWith(
        key: String,
        useCache: Boolean = false,
        debugName: String? = null,
        computation: suspend QueryHandler.() -> T
    ) {
        transformer.storage.registerComputation(
            key = key,
            debugName = debugName,
            delegate = ComputationDelegate(useCache = useCache, computation = computation)
        )
    }

    internal fun <A : Any, T : Any?> buildWith(
        key: String,
        useCache: Boolean = false,
        debugName: String? = null,
        computation: suspend QueryHandler.(args: A) -> T
    ) {
        transformer.storage.registerComputation(
            key = key,
            debugName = debugName,
            delegate = ComputationDelegateWithArgs(useCache = useCache, computation = computation)
        )
    }
}
