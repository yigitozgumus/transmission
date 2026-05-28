package com.trendyol.transmission.transformer.handler

import com.trendyol.transmission.Transmission
import com.trendyol.transmission.router.TransmissionRouteKey
import kotlin.reflect.KClass

@PublishedApi
internal class RouteRegistry<T : Transmission> {
    private val routes = mutableMapOf<KClass<out T>, StackedLambda<T>>()
    private val keyedRoutes = mutableMapOf<TransmissionRouteKey<*>, StackedLambda<T>>()

    fun clear() {
        routes.clear()
        keyedRoutes.clear()
    }

    fun routeTypes(): Set<KClass<out T>> {
        return routes.keys
    }

    fun routeKeys(): Set<TransmissionRouteKey<*>> {
        return keyedRoutes.keys
    }

    @PublishedApi
    internal fun register(type: KClass<out T>, lambda: TransmissionLambda<T>) {
        routes[type] = StackedLambda<T>().also { it.addOperation(lambda) }
    }

    @PublishedApi
    internal fun register(routeKey: TransmissionRouteKey<out T>, lambda: TransmissionLambda<T>) {
        keyedRoutes[routeKey] = StackedLambda<T>().also { it.addOperation(lambda) }
    }

    @PublishedApi
    internal fun extend(type: KClass<out T>, lambda: TransmissionLambda<T>) {
        routes[type] = routes[type]
            ?.also { it.addOperation(lambda) }
            ?: StackedLambda<T>().also { it.addOperation(lambda) }
    }

    @PublishedApi
    internal fun extend(routeKey: TransmissionRouteKey<out T>, lambda: TransmissionLambda<T>) {
        keyedRoutes[routeKey] = keyedRoutes[routeKey]
            ?.also { it.addOperation(lambda) }
            ?: StackedLambda<T>().also { it.addOperation(lambda) }
    }

    suspend fun dispatch(scope: CommunicationScope, transmission: T, routeKey: TransmissionRouteKey<*>? = null) {
        routeKey?.let { keyedRoutes[it]?.execute(scope, transmission) }
            ?: routes[transmission::class]?.execute(scope, transmission)
    }
}
