package com.trendyol.transmission.transformer.request.computation

import com.trendyol.transmission.transformer.request.QueryHandler
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class ComputationDelegate(
    private val useCache: Boolean = false,
    private val computation: (suspend QueryHandler.() -> Any?)? = null,
) : ComputationOwner.Default {

    private var result: Any? = null

    private val lock = Mutex()

    override suspend fun getResult(scope: QueryHandler, invalidate: Boolean): Any? {
        return if (useCache && invalidate.not()) {
            result ?: lock.withLock { computation?.invoke(scope) }.also { result = it }
        } else {
            result = null
            lock.withLock { computation?.invoke(scope) }
        }
    }
}

internal class ComputationDelegateWithArgs<A : Any>(
    private val useCache: Boolean = false,
    private val computation: suspend QueryHandler.(args: A) -> Any?,
) : ComputationOwner.WithArgs<A> {

    private val results: MutableMap<A, Any?> = mutableMapOf()
    private val lock = Mutex()

    override suspend fun getResult(scope: QueryHandler, invalidate: Boolean, args: A): Any? {
        return lock.withLock {
            when {
                useCache.not() -> computation(scope, args)
                invalidate -> {
                    results.remove(args)
                    computation(scope, args)
                }
                results.containsKey(args) -> results[args]
                else -> computation(scope, args).also { results[args] = it }
            }
        }
    }
}
