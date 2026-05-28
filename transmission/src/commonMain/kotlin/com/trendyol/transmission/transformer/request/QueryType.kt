package com.trendyol.transmission.transformer.request

internal sealed interface QueryType {

    class Data<D : com.trendyol.transmission.Transmission.Data?>(
        val sender: String,
        val contract: Contract.DataHolder<D>,
        val queryIdentifier: String,
    ) : QueryType {
        val key: String = contract.key
    }

    class Computation<D : Any?>(
        val sender: String,
        val contract: Contract.Computation<D>,
        val queryIdentifier: String,
        val invalidate: Boolean = false,
    ) : QueryType {
        val key: String = contract.key
    }

    class ComputationWithArgs<A : Any, D : Any?>(
        val sender: String,
        val contract: Contract.ComputationWithArgs<A, D>,
        val args: A,
        val queryIdentifier: String,
        val invalidate: Boolean = false,
    ) : QueryType {
        val key: String = contract.key
    }

    class Execution(
        val contract: Contract.Execution,
    ) : QueryType {
        val key: String = contract.key
    }

    class ExecutionWithArgs<A : Any>(
        val contract: Contract.ExecutionWithArgs<A>,
        val args: A,
    ) : QueryType {
        val key: String = contract.key
    }
}
