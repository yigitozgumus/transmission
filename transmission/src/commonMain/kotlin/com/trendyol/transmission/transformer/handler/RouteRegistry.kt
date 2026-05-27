package com.trendyol.transmission.transformer.handler

import com.trendyol.transmission.Transmission
import kotlin.reflect.KClass

@PublishedApi
internal class RouteRegistry<T : Transmission> {
    private val routes = mutableMapOf<KClass<out T>, StackedLambda<T>>()

    fun clear() {
        routes.clear()
    }

    @PublishedApi
    internal fun register(type: KClass<out T>, lambda: TransmissionLambda<T>) {
        routes[type] = StackedLambda<T>().also { it.addOperation(lambda) }
    }

    @PublishedApi
    internal fun extend(type: KClass<out T>, lambda: TransmissionLambda<T>) {
        routes[type] = routes[type]
            ?.also { it.addOperation(lambda) }
            ?: StackedLambda<T>().also { it.addOperation(lambda) }
    }

    suspend fun dispatch(scope: CommunicationScope, transmission: T) {
        routes[transmission::class]?.execute(scope, transmission)
    }
}
