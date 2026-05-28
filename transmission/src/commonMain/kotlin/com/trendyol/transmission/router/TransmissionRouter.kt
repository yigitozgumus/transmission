package com.trendyol.transmission.router

import com.trendyol.transmission.Transmission
import com.trendyol.transmission.router.builder.TransmissionRouterBuilderScope
import com.trendyol.transmission.router.loader.TransformerSetLoader
import com.trendyol.transmission.transformer.Transformer
import com.trendyol.transmission.transformer.checkpoint.CheckpointTracker
import com.trendyol.transmission.transformer.request.Contract
import com.trendyol.transmission.transformer.request.QueryHandler
import com.trendyol.transmission.transformer.request.QueryResult
import com.trendyol.transmission.transformer.request.QueryType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.reflect.KClass

/**
 * Central routing service that manages signal processing and data flow in Transmission applications.
 * 
 * TransmissionRouter acts as the coordination hub for all transformers in the system. It receives
 * [Transmission.Signal]s from UI components, routes them to appropriate transformers, manages
 * [Transmission.Effect] propagation between transformers, and streams [Transmission.Data] back to observers.
 * 
 * Key responsibilities:
 * - Route incoming signals to registered transformers
 * - Manage effect propagation between transformers
 * - Coordinate data streaming to observers
 * - Handle transformer lifecycle and cleanup
 * - Provide query-based communication between transformers
 * 
 * The router operates asynchronously using coroutines and provides backpressure handling
 * through configurable channel capacities.
 * 
 * @param identity Unique identifier for this router instance
 * @param transformerSetLoader Optional loader for transformer initialization
 * @param autoInitialization Whether to automatically initialize transformers on creation
 * @param capacity Buffer capacity for internal channels
 * @param dispatcher Coroutine dispatcher for router operations
 * 
 * @throws IllegalStateException when supplied [Transformer] set is empty during initialization
 * 
 * Example usage:
 * ```kotlin
 * val router = TransmissionRouter {
 *     addTransformerSet(setOf(userTransformer, dataTransformer))
 *     setCapacity(Capacity.Custom(128))
 * }
 * 
 * // Process signals
 * router.process(UserSignal.Login(credentials))
 * 
 * // Observe data
 * launch {
 *     router.streamData<UserData>().collect { userData ->
 *         updateUI(userData)
 *     }
 * }
 * ```
 * 
 * @see Transformer for implementing business logic
 * @see streamData for observing data streams
 * @see process for sending signals and effects
 */
class TransmissionRouter internal constructor(
    identity: Contract.Identity,
    internal val transformerSetLoader: TransformerSetLoader? = null,
    internal val autoInitialization: Boolean = true,
    internal val capacity: Capacity = Capacity.Default,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val registerToGlobalRouter: Boolean = true,
    private val validateGlobalContracts: Boolean = false,
): StreamOwner {

    internal companion object {
        internal const val EMPTY_TRANSFORMER_SET_MESSAGE =
            "TransformerSetLoader or non-empty transformerSet is required when autoInitialization is enabled."
    }

    private val exceptionHandler = CoroutineExceptionHandler { _, _ -> }
    private val routerScope = CoroutineScope(SupervisorJob() + dispatcher + exceptionHandler)

    private val _transformerSet: LinkedHashSet<Transformer> = linkedSetOf()
    internal val transformerSet: Set<Transformer> = _transformerSet
    private val signalInputs: MutableMap<Transformer, Channel<TransmissionEnvelope<Transmission.Signal>>> = mutableMapOf()
    private val effectInputs: MutableMap<Transformer, Channel<TransmissionEnvelope<Transmission.Effect>>> = mutableMapOf()
    private var signalRoutes: Map<KClass<out Transmission.Signal>, List<SendChannel<TransmissionEnvelope<Transmission.Signal>>>> = emptyMap()
    private var effectRoutes: Map<KClass<out Transmission.Effect>, List<Transformer>> = emptyMap()
    private var effectRoutingJob: Job? = null

    internal val routerName: String = identity.key

    private var globalEffectBridgeJob: Job? = null

    private val _isInitialized = MutableStateFlow(false)
    private val _initializationError = MutableStateFlow<Throwable?>(null)

    /**
     * Emits true after the router has successfully loaded and bound transformers.
     */
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    /**
     * Contains the latest initialization failure, or null when initialization has not failed.
     */
    val initializationError: StateFlow<Throwable?> = _initializationError.asStateFlow()

    private val transmissionBus = TransmissionBus(
        scope = routerScope,
        capacity = capacity,
    )

    private val checkpointTracker = CheckpointTracker()

    internal val dataEnvelopeStream = transmissionBus.dataStream

    override val dataStream = transmissionBus.dataPayloadStream

    override val effectStream: SharedFlow<Transmission.Effect> = transmissionBus.effectPayloadStream

    private val _queryManager = QueryManager(
        queryScope = routerScope,
        routerRef = this@TransmissionRouter,
        capacity = capacity,
    )

    /**
     * Provides access to the query system for inter-transformer communication.
     * 
     * The query helper allows transformers to communicate with each other through
     * type-safe queries, enabling computations and executions across transformer boundaries.
     * 
     * @see QueryHandler for available query operations
     * @see com.trendyol.transmission.transformer.request.Contract for defining query contracts
     */
    val queryHelper: QueryHandler = _queryManager.handler

    init {
        if (registerToGlobalRouter) {
            GlobalTransmissionRouter.register(this)
            startGlobalEffectBridge()
        }
        if (autoInitialization) {
            initializeInternal(transformerSetLoader)
        }
    }

    /**
     * Manually initializes the router with the specified [TransformerSetLoader].
     * 
     * This method is only available when auto-initialization is disabled via 
     * [TransmissionRouterBuilderScope.overrideInitialization]. It allows for deferred
     * initialization of transformers, which can be useful for dependency injection
     * scenarios or when transformers need to be loaded dynamically.
     * 
     * @param loader The transformer set loader containing the transformers to initialize
     * 
     * @throws IllegalStateException if auto-initialization is enabled
     * 
     * Example usage:
     * ```kotlin
     * val router = TransmissionRouter {
     *     overrideInitialization()
     * }
     * 
     * // Later, when transformers are ready
     * router.initialize(MyTransformerSetLoader())
     * ```
     * 
     * @see TransmissionRouterBuilderScope.overrideInitialization
     * @see TransformerSetLoader
     */
    fun initialize(loader: TransformerSetLoader): Job {
        check(!autoInitialization) {
            "TransmissionRouter is configured to initialize automatically."
        }
        return initializeInternal(loader)
    }

    /**
     * Processes a [Transmission.Signal] by routing it to all registered transformers.
     * 
     * Signals represent user interactions or external events that need to be processed
     * by the application. This method broadcasts the signal to all transformers that
     * have registered handlers for the signal type.
     * 
     * The processing is asynchronous and non-blocking. Signals are queued in an internal
     * channel with the configured capacity.
     * 
     * @param signal The signal to process
     * 
     * Example usage:
     * ```kotlin
     * router.process(UserSignal.Login(username, password))
     * router.process(DataSignal.Refresh)
     * ```
     * 
     * @see Transmission.Signal
     * @see com.trendyol.transmission.transformer.handler.onSignal
     */
    fun process(signal: Transmission.Signal) {
        routerScope.launch {
            routeSignal(TransmissionEnvelope(payload = signal))
        }
    }

    /**
     * Processes a [Transmission.Effect] by routing it to appropriate transformers.
     * 
     * Effects represent side effects or inter-transformer communications that need to be
     * processed. This method broadcasts the effect to transformers that have registered
     * handlers for the effect type.
     * 
     * The processing is asynchronous and non-blocking. Effects are queued in an internal
     * channel with the configured capacity.
     * 
     * @param effect The effect to process
     * 
     * Example usage:
     * ```kotlin
     * router.process(NetworkEffect.ConnectionLost)
     * router.process(CacheEffect.Invalidate("user-data"))
     * ```
     * 
     * @see Transmission.Effect
     * @see com.trendyol.transmission.transformer.handler.onEffect
     */
    fun process(effect: Transmission.Effect) {
        routerScope.launch {
            transmissionBus.send(effect)
        }
    }

    private fun initializeInternal(transformerSetLoader: TransformerSetLoader?): Job {
        return routerScope.launch {
            _isInitialized.update { false }
            _initializationError.update { null }
            try {
                transformerSetLoader?.load()?.let { _transformerSet.addAll(it) }
                validateLocalContracts(transformerSet)
                initializeTransformers(transformerSet)
            } catch (throwable: Throwable) {
                _initializationError.update { throwable }
                if (registerToGlobalRouter) {
                    GlobalTransmissionRouter.unregister(this@TransmissionRouter)
                }
                throw throwable
            }
        }
    }

    private fun initializeTransformers(transformerSet: Set<Transformer>) {
        check(transformerSet.isNotEmpty()) {
            EMPTY_TRANSFORMER_SET_MESSAGE
        }
        if (registerToGlobalRouter && validateGlobalContracts) {
            GlobalTransmissionRouter.validateContracts(this)
        }
        buildRoutingIndexes(transformerSet)
        startEffectRouter()
        transformerSet.forEach { transformer ->
            transformer.run {
                bindCheckpointTracker(checkpointTracker)
                startSignalCollection(incoming = signalInputs.getValue(transformer).receiveAsFlow())
                startDataPublishing(data = transmissionBus.dataProducer)
                startEffectProcessing(
                    producer = transmissionBus.effectProducer,
                    incoming = effectInputs.getValue(transformer).receiveAsFlow(),
                )
                startQueryProcessing(
                    incomingQuery = _queryManager.incomingQueryResponse,
                    outGoingQuery = _queryManager.outGoingQuery
                )
            }
        }
        _isInitialized.update { true }
    }

    private fun buildRoutingIndexes(transformerSet: Set<Transformer>) {
        signalInputs.clear()
        effectInputs.clear()
        transformerSet.forEach { transformer ->
            signalInputs[transformer] = Channel(capacity = capacity.value)
            effectInputs[transformer] = Channel(capacity = capacity.value)
        }
        signalRoutes = transformerSet
            .flatMap { transformer ->
                transformer.handlerRegistry.signalTypes().map { type -> type to signalInputs.getValue(transformer) }
            }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })
        effectRoutes = transformerSet
            .flatMap { transformer ->
                transformer.handlerRegistry.effectTypes().map { type -> type to transformer }
            }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })
    }

    private suspend fun routeSignal(envelope: TransmissionEnvelope<Transmission.Signal>) {
        signalRoutes[envelope.payload::class].orEmpty().forEach { input ->
            input.send(envelope)
        }
    }

    private fun startEffectRouter() {
        effectRoutingJob?.cancel()
        effectRoutingJob = routerScope.launch {
            transmissionBus.effectStream.collect { envelope ->
                routeEffect(envelope)
            }
        }
    }

    private suspend fun routeEffect(envelope: TransmissionEnvelope<Transmission.Effect>) {
        effectRoutes[envelope.payload::class]
            .orEmpty()
            .asSequence()
            .filter { transformer -> envelope.target == null || envelope.target == transformer.identity }
            .forEach { transformer ->
                effectInputs.getValue(transformer).send(envelope)
            }
    }

    private fun startGlobalEffectBridge() {
        globalEffectBridgeJob = routerScope.launch {
            transmissionBus.effectStream.collect { envelope ->
                if (envelope.originRouter == null || envelope.originRouter == routerName) {
                    GlobalTransmissionRouter.publishEffect(
                        sourceRouter = this@TransmissionRouter,
                        envelope = envelope,
                    )
                }
            }
        }
    }

    internal fun receiveGlobalEffect(envelope: TransmissionEnvelope<Transmission.Effect>) {
        routerScope.launch {
            transmissionBus.send(envelope)
        }
    }

    internal fun containsTransformer(identity: Contract.Identity): Boolean {
        return transformerSet.any { transformer -> transformer.identity == identity }
    }

    internal fun canResolve(query: QueryType): Boolean {
        return when (query) {
            is QueryType.Data<*> -> transformerSet.any { transformer ->
                transformer.storage.isHolderStateInitialized() && transformer.storage.isHolderDataDefined(query.key)
            }
            is QueryType.Computation<*> -> transformerSet.any { transformer ->
                transformer.storage.hasComputation(query.key)
            }
            is QueryType.ComputationWithArgs<*, *> -> transformerSet.any { transformer ->
                transformer.storage.hasComputation(query.key)
            }
            is QueryType.Execution -> transformerSet.any { transformer ->
                transformer.storage.hasExecution(query.key)
            }
            is QueryType.ExecutionWithArgs<*> -> transformerSet.any { transformer ->
                transformer.storage.hasExecution(query.key)
            }
        }
    }

    internal fun receiveGlobalQuery(query: QueryType) {
        _queryManager.processGlobalQuery(query)
    }

    internal fun receiveGlobalQueryResult(result: QueryResult) {
        _queryManager.receiveGlobalQueryResult(result)
    }

    internal fun contractConflictsWith(other: TransmissionRouter): List<String> {
        val dataHolderConflicts = globalDataHolderKeys().intersect(other.globalDataHolderKeys())
            .map { key -> "dataHolder:$key with ${other.routerName}" }
        val computationConflicts = globalComputationKeys().intersect(other.globalComputationKeys())
            .map { key -> "computation:$key with ${other.routerName}" }
        val executionConflicts = globalExecutionKeys().intersect(other.globalExecutionKeys())
            .map { key -> "execution:$key with ${other.routerName}" }
        return (dataHolderConflicts + computationConflicts + executionConflicts).sorted()
    }

    private fun validateLocalContracts(transformerSet: Set<Transformer>) {
        val conflicts = listOf(
            duplicateContractMessages("dataHolder", transformerSet.flatMap { transformer -> transformer.storage.holderKeys() }),
            duplicateContractMessages("computation", transformerSet.flatMap { transformer -> transformer.storage.computationKeys() }),
            duplicateContractMessages("execution", transformerSet.flatMap { transformer -> transformer.storage.executionKeys() }),
        ).flatten()

        check(conflicts.isEmpty()) {
            "Duplicate local router contracts found for $routerName: ${conflicts.joinToString()}"
        }
    }

    private fun duplicateContractMessages(type: String, keys: List<String>): List<String> {
        return keys
            .groupingBy { it }
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
            .sorted()
            .map { key -> "$type:$key" }
    }

    private fun globalDataHolderKeys(): Set<String> {
        return transformerSet.flatMap { transformer -> transformer.storage.holderKeys() }.toSet()
    }

    private fun globalComputationKeys(): Set<String> {
        return transformerSet.flatMap { transformer -> transformer.storage.computationKeys() }.toSet()
    }

    private fun globalExecutionKeys(): Set<String> {
        return transformerSet.flatMap { transformer -> transformer.storage.executionKeys() }.toSet()
    }

    /**
     * Clears the router and all its transformers, releasing all resources.
     * 
     * This method should be called when the router is no longer needed to ensure
     * proper cleanup of resources. It performs the following operations:
     * 1. Clears all registered transformers
     * 2. Cancels the router's coroutine scope
     * 3. Closes all internal channels
     * 
     * After calling this method, the router should not be used anymore.
     * 
     * Example usage:
     * ```kotlin
     * // In a ViewModel or similar lifecycle-aware component
     * override fun onCleared() {
     *     super.onCleared()
     *     router.clear()
     * }
     * ```
     * 
     * @see Transformer.clear
     */
    fun clear() {
        if (registerToGlobalRouter) {
            GlobalTransmissionRouter.unregister(this)
        }
        globalEffectBridgeJob?.cancel()
        transformerSet.forEach { it.clear() }
        routerScope.cancel()
    }
}

