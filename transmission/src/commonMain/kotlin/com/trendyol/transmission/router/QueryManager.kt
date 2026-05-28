package com.trendyol.transmission.router

import com.trendyol.transmission.Transmission
import com.trendyol.transmission.identifier.IdentifierGenerator
import com.trendyol.transmission.transformer.request.Contract
import com.trendyol.transmission.transformer.request.QueryHandler
import com.trendyol.transmission.transformer.request.QueryResult
import com.trendyol.transmission.transformer.request.QueryType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class QueryManager(
    private val queryScope: CoroutineScope,
    private val routerRef: TransmissionRouter,
    private val capacity: Capacity = Capacity.Default,
) {

    private val pendingRouterQueries = mutableMapOf<String, CompletableDeferred<QueryResult>>()
    private val pendingRouterQueriesLock = Mutex()

    val outGoingQuery: Channel<QueryType> = Channel(capacity = capacity.value)
    private val queryResultChannel: Channel<QueryResult> = Channel(capacity = capacity.value)

    val incomingQueryResponse = queryResultChannel.receiveAsFlow()
        .shareIn(queryScope, SharingStarted.Lazily)

    init {
        queryScope.launch {
            outGoingQuery.consumeAsFlow().collect { processQuery(it) }
        }
    }

    private suspend fun awaitRouterQueryResult(
        queryIdentifier: String,
        sendQuery: suspend () -> Unit,
    ): QueryResult {
        val result = CompletableDeferred<QueryResult>()
        pendingRouterQueriesLock.withLock {
            pendingRouterQueries[queryIdentifier] = result
        }
        try {
            sendQuery()
            return result.await()
        } finally {
            pendingRouterQueriesLock.withLock {
                pendingRouterQueries.remove(queryIdentifier)
            }
        }
    }

    private suspend fun completeRouterQuery(result: QueryResult) {
        val queryIdentifier = when (result) {
            is QueryResult.Computation<*> -> result.resultIdentifier
            is QueryResult.Data<*> -> result.resultIdentifier
        }
        val pendingQuery = pendingRouterQueriesLock.withLock {
            pendingRouterQueries[queryIdentifier]
        }
        pendingQuery?.complete(result)
    }

    val handler = object : QueryHandler {

        override suspend fun <D : Transmission.Data?> getData(contract: Contract.DataHolder<D>): D {
            val queryIdentifier = IdentifierGenerator.generateIdentifier()
            val result = awaitRouterQueryResult(queryIdentifier) {
                outGoingQuery.send(
                    QueryType.Data(
                        sender = routerRef.routerName,
                        contract = contract,
                        queryIdentifier = queryIdentifier
                    )
                )
            } as QueryResult.Data<D>
            return result.data
        }

        override suspend fun <D : Any?> compute(
            contract: Contract.Computation<D>,
            invalidate: Boolean,
        ): D {
            val queryIdentifier = IdentifierGenerator.generateIdentifier()
            val result = awaitRouterQueryResult(queryIdentifier) {
                outGoingQuery.send(
                    QueryType.Computation(
                        sender = routerRef.routerName,
                        contract = contract,
                        invalidate = invalidate,
                        queryIdentifier = queryIdentifier
                    )
                )
            } as QueryResult.Computation<D>
            return result.data
        }

        override suspend fun <A : Any, D : Any?> compute(
            contract: Contract.ComputationWithArgs<A, D>,
            args: A,
            invalidate: Boolean,
        ): D {
            val queryIdentifier = IdentifierGenerator.generateIdentifier()
            val result = awaitRouterQueryResult(queryIdentifier) {
                outGoingQuery.send(
                    QueryType.ComputationWithArgs(
                        sender = routerRef.routerName,
                        contract = contract,
                        args = args,
                        invalidate = invalidate,
                        queryIdentifier = queryIdentifier
                    )
                )
            } as QueryResult.Computation<D>
            return result.data
        }

        override suspend fun execute(contract: Contract.Execution) {
            outGoingQuery.send(
                QueryType.Execution(
                    contract = contract,
                )
            )
        }

        override suspend fun <A : Any> execute(
            contract: Contract.ExecutionWithArgs<A>,
            args: A,
        ) {
            outGoingQuery.send(
                QueryType.ExecutionWithArgs(
                    contract = contract,
                    args = args,
                )
            )
        }
    }

    // region process queries

    internal fun processGlobalQuery(query: QueryType) = processQuery(query, allowGlobalFallback = false)

    internal fun receiveGlobalQueryResult(result: QueryResult) {
        queryScope.launch {
            completeRouterQuery(result)
        }
    }

    private fun processQuery(query: QueryType, allowGlobalFallback: Boolean = true) = queryScope.launch {
        when (query) {
            is QueryType.Computation<*> -> processComputationQuery(query, allowGlobalFallback)
            is QueryType.Data<*> -> processDataQuery(query, allowGlobalFallback)
            is QueryType.ComputationWithArgs<*, *> -> processComputationQueryWithArgs(query, allowGlobalFallback)
            is QueryType.Execution -> processExecution(query, allowGlobalFallback)
            is QueryType.ExecutionWithArgs<*> -> processExecutionWithArgs(query, allowGlobalFallback)
        }
    }

    private suspend fun sendQueryResult(result: QueryResult) {
        if (result.owner == routerRef.routerName) {
            completeRouterQuery(result)
        } else if (!GlobalTransmissionRouter.routeQueryResult(result)) {
            queryResultChannel.send(result)
        }
    }

    private fun processDataQuery(
        query: QueryType.Data<*>,
        allowGlobalFallback: Boolean,
    ) = queryScope.launch {
        val dataHolder = routerRef.transformerSet
            .filter { it.storage.isHolderStateInitialized() }
            .find { it.storage.isHolderDataDefined(query.key) }
        if (dataHolder == null && allowGlobalFallback && GlobalTransmissionRouter.routeQuery(routerRef, query)) {
            return@launch
        }
        sendQueryResult(
            QueryResult.Data(
                owner = query.sender,
                key = query.key,
                data = dataHolder?.storage?.getHolderDataByKey(query.key),
                resultIdentifier = query.queryIdentifier,
            )
        )
    }

    private fun processComputationQuery(
        query: QueryType.Computation<*>,
        allowGlobalFallback: Boolean,
    ) = queryScope.launch {
        val computationHolder = routerRef.transformerSet
            .find { it.storage.hasComputation(query.key) }
        if (computationHolder == null && allowGlobalFallback && GlobalTransmissionRouter.routeQuery(routerRef, query)) {
            return@launch
        }
        val computationToSend = queryScope.async {
            val computationData = runCatching {
                computationHolder?.storage?.getComputationByKey(query.key)
                    ?.getResult(computationHolder.communicationScope, query.invalidate)
            }.onFailure {
                computationHolder?.onError(it)
            }.getOrNull()

            QueryResult.Computation(
                owner = query.sender,
                key = query.key,
                data = computationData,
                resultIdentifier = query.queryIdentifier
            )
        }
        sendQueryResult(computationToSend.await())
    }

    private fun processComputationQueryWithArgs(
        query: QueryType.ComputationWithArgs<*, *>,
        allowGlobalFallback: Boolean,
    ) = queryScope.launch {
        val computationHolder = routerRef.transformerSet
            .find { it.storage.hasComputation(query.key) }
        if (computationHolder == null && allowGlobalFallback && GlobalTransmissionRouter.routeQuery(routerRef, query)) {
            return@launch
        }
        val computationToSend = queryScope.async {
            val computationData = runCatching {
                computationHolder?.storage?.getComputationByKey<Any>(query.key)
                    ?.getResult(
                        computationHolder.communicationScope,
                        query.invalidate,
                        query.args
                    )
            }.onFailure {
                computationHolder?.onError(it)
            }.getOrNull()

            QueryResult.Computation(
                owner = query.sender,
                key = query.key,
                data = computationData,
                resultIdentifier = query.queryIdentifier
            )
        }
        sendQueryResult(computationToSend.await())
    }

    private fun processExecution(
        query: QueryType.Execution,
        allowGlobalFallback: Boolean,
    ) = queryScope.launch {
        val executionHolder = routerRef.transformerSet
            .find { it.storage.hasExecution(query.key) }
        if (executionHolder == null && allowGlobalFallback && GlobalTransmissionRouter.routeQuery(routerRef, query)) {
            return@launch
        }
        executionHolder ?: return@launch
        runCatching {
            executionHolder.storage.getExecutionByKey(query.key)
                ?.execute(executionHolder.communicationScope)
        }.onFailure(executionHolder::onError).getOrNull()
    }

    private fun <A : Any> processExecutionWithArgs(
        query: QueryType.ExecutionWithArgs<A>,
        allowGlobalFallback: Boolean,
    ) = queryScope.launch {
        val executionHolder = routerRef.transformerSet
            .find { it.storage.hasExecution(query.key) }
        if (executionHolder == null && allowGlobalFallback && GlobalTransmissionRouter.routeQuery(routerRef, query)) {
            return@launch
        }
        executionHolder ?: return@launch
        runCatching {
            executionHolder.storage.getExecutionByKey<A>(query.key)
                ?.execute(executionHolder.communicationScope, query.args)
        }.onFailure(executionHolder::onError).getOrNull()
    }

    // endregion
}
