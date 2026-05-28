package com.trendyol.transmission.transformer

import com.trendyol.transmission.Transmission
import com.trendyol.transmission.router.Capacity
import com.trendyol.transmission.router.TransmissionRouteKey
import com.trendyol.transmission.transformer.dataholder.TransmissionDataHolder
import com.trendyol.transmission.transformer.dataholder.dataHolder
import com.trendyol.transmission.transformer.handler.CommunicationScope
import com.trendyol.transmission.transformer.handler.HandlerScope
import com.trendyol.transmission.transformer.handler.onEffect
import com.trendyol.transmission.transformer.handler.onSignal
import com.trendyol.transmission.transformer.request.Contract
import com.trendyol.transmission.transformer.request.QueryHandler
import com.trendyol.transmission.transformer.request.computation.ComputationScope
import com.trendyol.transmission.transformer.request.execution.ExecutionScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Configures a [Transformer] from a single class-friendly composition block.
 *
 * This API is intended for dependency-injected transformer classes where the class structure is still valuable,
 * but handlers, computations, executions, and lifecycle hooks should be declared together.
 *
 * Example:
 * ```kotlin
 * class CounterTransformer(
 *     private val repository: CounterRepository
 * ) : Transformer() {
 *     private val state = dataHolder(CounterState())
 *
 *     init {
 *         configure {
 *             onSignal<Increment> {
 *                 state.update { it.copy(count = it.count + 1) }
 *             }
 *
 *             computation(counterContract) {
 *                 repository.calculateCount()
 *             }
 *
 *             onError { throwable ->
 *                 repository.log(throwable)
 *             }
 *         }
 *     }
 * }
 * ```
 */
fun Transformer.configure(scope: TransformerConfigurationScope.() -> Unit): Transformer {
    TransformerConfigurationScope(this).apply(scope)
    return this
}

/**
 * Creates a small anonymous [Transformer] configured with [configure].
 *
 * Prefer subclassing [Transformer] plus [configure] for production code that relies on dependency injection.
 * This function is useful for tests, examples, or simple local transformer definitions.
 */
fun transformer(
    identity: Contract.Identity = Contract.identity(),
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    capacity: Capacity = Capacity.Default,
    scope: TransformerConfigurationScope.() -> Unit,
): Transformer = Transformer(identity, dispatcher, capacity).configure(scope)

/**
 * Unified transformer definition scope used by [configure].
 */
class TransformerConfigurationScope @PublishedApi internal constructor(
    @PublishedApi internal val transformer: Transformer,
) {

    /** Creates a data holder owned by the configured transformer. */
    fun <T : Transmission.Data?> dataHolder(
        initialValue: T,
        contract: Contract.DataHolder<T>? = null,
        publishUpdates: Boolean = true,
    ): TransmissionDataHolder<T> {
        return transformer.dataHolder(
            initialValue = initialValue,
            contract = contract,
            publishUpdates = publishUpdates,
        )
    }

    /** Replaces all existing handlers. Prefer direct [onSignal] and [onEffect] calls for appending. */
    fun replaceHandlers(scope: HandlerScope.() -> Unit) {
        transformer.handlerRegistry.clear()
        HandlerScope(transformer.handlerRegistry).apply(scope)
    }

    /** Appends handlers without clearing existing handlers. */
    fun appendHandlers(scope: HandlerScope.() -> Unit) {
        HandlerScope(transformer.handlerRegistry).apply(scope)
    }

    /** Appends a signal handler without clearing existing handlers. */
    inline fun <reified T : Transmission.Signal> onSignal(
        noinline lambda: suspend CommunicationScope.(signal: T) -> Unit,
    ) {
        transformer.addHandlers {
            onSignal(lambda)
        }
    }

    /** Appends an effect handler without clearing existing handlers. */
    inline fun <reified T : Transmission.Effect> onEffect(
        noinline lambda: suspend CommunicationScope.(effect: T) -> Unit,
    ) {
        transformer.addHandlers {
            onEffect(lambda)
        }
    }

    /** Appends an effect handler with a generated or explicit route key. */
    inline fun <reified T : Transmission.Effect> onEffect(
        routeKey: TransmissionRouteKey,
        noinline lambda: suspend CommunicationScope.(effect: T) -> Unit,
    ) {
        transformer.addHandlers {
            onEffect(routeKey, lambda)
        }
    }

    /** Appends a signal handler with a generated or explicit route key. */
    inline fun <reified T : Transmission.Signal> onSignal(
        routeKey: TransmissionRouteKey,
        noinline lambda: suspend CommunicationScope.(signal: T) -> Unit,
    ) {
        transformer.addHandlers {
            onSignal(routeKey, lambda)
        }
    }

    /** Replaces all existing computations. Prefer direct [computation] calls for appending. */
    fun replaceComputations(scope: ComputationScope.() -> Unit) {
        transformer.computationRegistry.clear()
        ComputationScope(transformer.computationRegistry).apply(scope)
    }

    /** Appends computations without clearing existing computations. */
    fun appendComputations(scope: ComputationScope.() -> Unit) {
        ComputationScope(transformer.computationRegistry).apply(scope)
    }

    /** Appends a computation without arguments. */
    fun <T : Any?> computation(
        contract: Contract.Computation<T>,
        computation: suspend QueryHandler.() -> T,
    ) {
        transformer.registerComputation(contract, computation)
    }

    /** Appends a computation with arguments. */
    fun <A : Any, T : Any?> computation(
        contract: Contract.ComputationWithArgs<A, T>,
        computation: suspend QueryHandler.(args: A) -> T,
    ) {
        transformer.registerComputation(contract, computation)
    }

    /** Replaces all existing executions. Prefer direct [execution] calls for appending. */
    fun replaceExecutions(scope: ExecutionScope.() -> Unit) {
        transformer.executionRegistry.clear()
        ExecutionScope(transformer.executionRegistry).apply(scope)
    }

    /** Appends executions without clearing existing executions. */
    fun appendExecutions(scope: ExecutionScope.() -> Unit) {
        ExecutionScope(transformer.executionRegistry).apply(scope)
    }

    /** Appends an execution without arguments. */
    fun execution(
        contract: Contract.Execution,
        execution: suspend QueryHandler.() -> Unit,
    ) {
        transformer.registerExecution(contract, execution)
    }

    /** Appends an execution with arguments. */
    fun <A : Any> execution(
        contract: Contract.ExecutionWithArgs<A>,
        execution: suspend QueryHandler.(args: A) -> Unit,
    ) {
        transformer.registerExecution(contract, execution)
    }

    /** Registers a callback invoked by [Transformer.onError]. */
    fun onError(callback: (Throwable) -> Unit) {
        transformer.registerConfiguredOnError(callback)
    }

    /** Registers a callback invoked by [Transformer.onCleared]. */
    fun onCleared(callback: () -> Unit) {
        transformer.registerConfiguredOnCleared(callback)
    }
}
