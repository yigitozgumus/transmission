package com.trendyol.transmission.transformer.handler

import com.trendyol.transmission.Transmission
import com.trendyol.transmission.router.TransmissionId
import kotlin.reflect.KClass

@PublishedApi
internal class RouteRegistry<T : Transmission> {
    private val ids = mutableMapOf<KClass<out T>, StackedLambda<T>>()
    private val keyedIds = mutableMapOf<TransmissionId<*>, StackedLambda<T>>()

    fun clear() {
        ids.clear()
        keyedIds.clear()
    }

    fun registeredTypes(): Set<KClass<out T>> {
        return ids.keys
    }

    fun transmissionIds(): Set<TransmissionId<*>> {
        return keyedIds.keys
    }

    @PublishedApi
    internal fun register(type: KClass<out T>, lambda: TransmissionLambda<T>) {
        ids[type] = StackedLambda<T>().also { it.addOperation(lambda) }
    }

    @PublishedApi
    internal fun register(transmissionId: TransmissionId<out T>, lambda: TransmissionLambda<T>) {
        keyedIds[transmissionId] = StackedLambda<T>().also { it.addOperation(lambda) }
    }

    @PublishedApi
    internal fun extend(type: KClass<out T>, lambda: TransmissionLambda<T>) {
        ids[type] = ids[type]
            ?.also { it.addOperation(lambda) }
            ?: StackedLambda<T>().also { it.addOperation(lambda) }
    }

    @PublishedApi
    internal fun extend(transmissionId: TransmissionId<out T>, lambda: TransmissionLambda<T>) {
        keyedIds[transmissionId] = keyedIds[transmissionId]
            ?.also { it.addOperation(lambda) }
            ?: StackedLambda<T>().also { it.addOperation(lambda) }
    }

    suspend fun dispatch(scope: CommunicationScope, transmission: T, transmissionId: TransmissionId<*>? = null) {
        transmissionId?.let { keyedIds[it]?.execute(scope, transmission) }
            ?: ids[transmission::class]?.execute(scope, transmission)
    }
}
