package com.trendyol.transmission.transformer.request

import com.trendyol.transmission.ExperimentalTransmissionApi
import com.trendyol.transmission.InternalTransmissionApi
import com.trendyol.transmission.Transmission
import com.trendyol.transmission.identifier.IdentifierGenerator
import com.trendyol.transmission.router.Capacity
import com.trendyol.transmission.transformer.checkpoint.CheckpointHandler
import com.trendyol.transmission.transformer.checkpoint.CheckpointTracker
import com.trendyol.transmission.transformer.checkpoint.CheckpointValidator
import com.trendyol.transmission.transformer.handler.CommunicationScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

@OptIn(InternalTransmissionApi::class)
@ExperimentalTransmissionApi
internal class TransformerQueryDelegate(
    checkpointTrackerProvider: () -> CheckpointTracker?,
    identity: Contract.Identity,
    capacity: Capacity,
    scope: CoroutineScope,
) {
    val outGoingQuery: Channel<QueryType> = Channel(capacity = capacity.value)
    private val pendingQueries = mutableMapOf<String, CompletableDeferred<QueryResult>>()
    private val pendingQueriesLock = Mutex()

    suspend fun receiveQueryResult(result: QueryResult) {
        val queryIdentifier = when (result) {
            is QueryResult.Computation<*> -> result.resultIdentifier
            is QueryResult.Data<*> -> result.resultIdentifier
        }
        val pendingQuery = pendingQueriesLock.withLock {
            pendingQueries[queryIdentifier]
        }
        pendingQuery?.complete(result)
    }

    private suspend fun awaitQueryResult(
        queryIdentifier: String,
        sendQuery: suspend () -> Unit,
    ): QueryResult {
        val result = CompletableDeferred<QueryResult>()
        pendingQueriesLock.withLock {
            pendingQueries[queryIdentifier] = result
        }
        try {
            sendQuery()
            return result.await()
        } finally {
            pendingQueriesLock.withLock {
                pendingQueries.remove(queryIdentifier)
            }
        }
    }

    val checkpointHandler: CheckpointHandler by lazy {
        object : CheckpointHandler {

            @ExperimentalTransmissionApi
            override suspend fun CommunicationScope.pauseOn(contract: Contract.Checkpoint.Default) {
                val queryIdentifier = IdentifierGenerator.generateIdentifier()
                suspendCancellableCoroutine<Unit> { continuation ->
                    val validator =
                        object : CheckpointValidator<Contract.Checkpoint.Default, Unit> {

                            override suspend fun validate(
                                contract: Contract.Checkpoint.Default,
                                args: Unit
                            ): Boolean {
                                continuation.resume(Unit)
                                return true
                            }
                        }
                    checkpointTrackerProvider()?.run {
                        registerContract(contract, queryIdentifier)
                        putOrCreate(queryIdentifier, validator)
                    }
                }
            }

            @ExperimentalTransmissionApi
            override suspend fun CommunicationScope.pauseOn(
                vararg contract: Contract.Checkpoint.Default
            ) {
                val contractList = contract.toList()
                check(contractList.isNotEmpty()) {
                    "At least one checkpoint should be provided"
                }
                check(contractList.toSet().size == contractList.size) {
                    "All Checkpoint Contracts should be unique"
                }
                val queryIdentifier = IdentifierGenerator.generateIdentifier()
                suspendCancellableCoroutine<Unit> { continuation ->
                    val validator =
                        object : CheckpointValidator<Contract.Checkpoint.Default, Unit> {
                            private val lock = Mutex()
                            private val contractMap =
                                mutableMapOf<Contract.Checkpoint.Default, Boolean>()
                                    .apply { putAll(contractList.map { it to false }) }

                            override suspend fun validate(
                                contract: Contract.Checkpoint.Default,
                                args: Unit
                            ): Boolean {
                                lock.withLock { contractMap.put(contract, true) }
                                if (contractMap.values.all { it }) {
                                    continuation.resume(Unit)
                                    return true
                                } else return false
                            }
                        }
                    checkpointTrackerProvider()?.run {
                        contractList.forEach { registerContract(it, queryIdentifier) }
                        putOrCreate(queryIdentifier, validator)
                    }
                }
            }

            @ExperimentalTransmissionApi
            override suspend fun <A : Any> CommunicationScope.pauseOn(
                contract: Contract.Checkpoint.WithArgs<A>
            ): A {
                val queryIdentifier = IdentifierGenerator.generateIdentifier()
                return suspendCancellableCoroutine<A> { continuation ->
                    val validator =
                        object : CheckpointValidator<Contract.Checkpoint.WithArgs<A>, A> {
                            override suspend fun validate(
                                contract: Contract.Checkpoint.WithArgs<A>,
                                args: A
                            ): Boolean {
                                continuation.resume(args)
                                return true
                            }
                        }
                    checkpointTrackerProvider()?.run {
                        registerContract(contract, queryIdentifier)
                        putOrCreate(queryIdentifier, validator)
                    }
                }
            }

            override suspend fun validate(contract: Contract.Checkpoint.Default) {
                val validator = checkpointTrackerProvider()
                    ?.useValidator<Contract.Checkpoint.Default, Unit>(contract)
                validator ?: return
                if (validator.validate(contract, Unit)) {
                    checkpointTrackerProvider()
                        ?.removeValidator(contract)
                }
            }

            override suspend fun <A : Any> validate(
                contract: Contract.Checkpoint.WithArgs<A>,
                args: A
            ) {
                val validator = checkpointTrackerProvider()
                    ?.useValidator<Contract.Checkpoint.WithArgs<A>, A>(contract)
                validator ?: return
                if (validator.validate(contract, args)) {
                    checkpointTrackerProvider()
                        ?.removeValidator(contract)
                }
            }
        }
    }

    val queryHandler: QueryHandler = object : QueryHandler {

        override suspend fun <D : Transmission.Data?> getData(contract: Contract.DataHolder<D>): D {
            val queryIdentifier = IdentifierGenerator.generateIdentifier()
            val result = awaitQueryResult(queryIdentifier) {
                outGoingQuery.send(
                    QueryType.Data(
                        sender = identity.key,
                        contract = contract,
                        queryIdentifier = queryIdentifier
                    )
                )
            } as QueryResult.Data<D>
            return result.data
        }

        override suspend fun <D : Any?> compute(
            contract: Contract.Computation<D>, invalidate: Boolean
        ): D {
            val queryIdentifier = IdentifierGenerator.generateIdentifier()
            val result = awaitQueryResult(queryIdentifier) {
                outGoingQuery.send(
                    QueryType.Computation(
                        sender = identity.key,
                        contract = contract,
                        invalidate = invalidate,
                        queryIdentifier = queryIdentifier
                    )
                )
            } as QueryResult.Computation<D>
            return result.data
        }

        override suspend fun <A : Any, D : Any?> compute(
            contract: Contract.ComputationWithArgs<A, D>, args: A, invalidate: Boolean
        ): D {
            val queryIdentifier = IdentifierGenerator.generateIdentifier()
            val result = awaitQueryResult(queryIdentifier) {
                outGoingQuery.send(
                    QueryType.ComputationWithArgs(
                        sender = identity.key,
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
                QueryType.Execution(contract = contract)
            )
        }

        override suspend fun <A : Any> execute(
            contract: Contract.ExecutionWithArgs<A>, args: A
        ) {
            outGoingQuery.send(
                QueryType.ExecutionWithArgs(contract = contract, args = args)
            )
        }
    }
}
