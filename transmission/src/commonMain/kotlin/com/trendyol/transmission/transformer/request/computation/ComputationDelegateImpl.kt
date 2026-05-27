package com.trendyol.transmission.transformer.request.computation

import com.trendyol.transmission.transformer.request.QueryHandler
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class ComputationDelegate(
    private val useCache: Boolean = false,
    private val computation: (suspend QueryHandler.() -> Any?)? = null,
) : ComputationOwner.Default {

    private var hasResult: Boolean = false
    private var result: Any? = null

    private val lock = Mutex()

    override suspend fun getResult(scope: QueryHandler, invalidate: Boolean): Any? {
        return lock.withLock {
            when {
                useCache.not() -> computation?.invoke(scope)
                hasResult && invalidate.not() -> result
                else -> computation?.invoke(scope).also {
                    result = it
                    hasResult = true
                }
            }
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
                invalidate -> computation(scope, args).also { results[args] = it }
                results.containsKey(args) -> results[args]
                else -> computation(scope, args).also { results[args] = it }
            }
        }
    }
}
