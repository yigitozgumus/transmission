package com.trendyol.transmission.transformer.request.execution

import com.trendyol.transmission.transformer.Transformer
import com.trendyol.transmission.transformer.request.QueryHandler

class ExecutionRegistry internal constructor(private val transformer: Transformer) {

    internal fun clear() {
        transformer.storage.clearExecutions()
    }

    internal fun buildWith(
        key: String,
        debugName: String? = null,
        execution: suspend QueryHandler.() -> Unit
    ) {
        transformer.storage.registerExecution(
            key = key,
            debugName = debugName,
            delegate = ExecutionDelegate(execution)
        )
    }

    internal fun <A : Any> buildWith(
        key: String,
        debugName: String? = null,
        execution: suspend QueryHandler.(args: A) -> Unit
    ) {
        transformer.storage.registerExecution(
            key = key,
            debugName = debugName,
            delegate = ExecutionDelegateWithArgs(execution)
        )
    }
}
