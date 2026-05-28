@file:Suppress("UNCHECKED_CAST")

package com.trendyol.transmission.transformer.handler

import com.trendyol.transmission.Transmission
import com.trendyol.transmission.router.TransmissionRouteKey
import kotlin.reflect.KClass

typealias SignalLambda = TransmissionLambda<Transmission.Signal>
typealias EffectLambda = TransmissionLambda<Transmission.Effect>

class HandlerRegistry internal constructor() {

    @PublishedApi
    internal val signalRoutes = RouteRegistry<Transmission.Signal>()

    @PublishedApi
    internal val effectRoutes = RouteRegistry<Transmission.Effect>()

    internal fun clear() {
        signalRoutes.clear()
        effectRoutes.clear()
    }

    internal fun signalTypes(): Set<KClass<out Transmission.Signal>> {
        return signalRoutes.routeTypes()
    }

    internal fun signalRouteKeys(): Set<TransmissionRouteKey> {
        return signalRoutes.routeKeys()
    }

    internal fun effectTypes(): Set<KClass<out Transmission.Effect>> {
        return effectRoutes.routeTypes()
    }

    internal fun effectRouteKeys(): Set<TransmissionRouteKey> {
        return effectRoutes.routeKeys()
    }

    internal suspend fun dispatchSignal(
        scope: CommunicationScope,
        signal: Transmission.Signal,
        routeKey: TransmissionRouteKey? = null,
    ) {
        signalRoutes.dispatch(scope, signal, routeKey)
    }

    internal suspend fun dispatchEffect(
        scope: CommunicationScope,
        effect: Transmission.Effect,
        routeKey: TransmissionRouteKey? = null,
    ) {
        effectRoutes.dispatch(scope, effect, routeKey)
    }

    @PublishedApi
    internal inline fun <reified T : Transmission.Signal> signal(
        noinline lambda: suspend CommunicationScope.(signal: T) -> Unit
    ) {
        signalRoutes.register(T::class, lambda as SignalLambda)
    }

    @PublishedApi
    internal inline fun <reified T : Transmission.Signal> extendSignal(
        noinline lambda: suspend CommunicationScope.(signal: T) -> Unit
    ) {
        signalRoutes.extend(T::class, lambda as SignalLambda)
    }

    @PublishedApi
    internal fun <T : Transmission.Signal> signal(
        routeKey: TransmissionRouteKey,
        lambda: suspend CommunicationScope.(signal: T) -> Unit,
    ) {
        signalRoutes.register(routeKey, lambda as SignalLambda)
    }

    @PublishedApi
    internal fun <T : Transmission.Signal> extendSignal(
        routeKey: TransmissionRouteKey,
        lambda: suspend CommunicationScope.(signal: T) -> Unit,
    ) {
        signalRoutes.extend(routeKey, lambda as SignalLambda)
    }

    @PublishedApi
    internal inline fun <reified T : Transmission.Effect> effect(
        noinline lambda: suspend CommunicationScope.(effect: T) -> Unit
    ) {
        effectRoutes.register(T::class, lambda as EffectLambda)
    }

    @PublishedApi
    internal inline fun <reified T : Transmission.Effect> extendEffect(
        noinline lambda: suspend CommunicationScope.(effect: T) -> Unit
    ) {
        effectRoutes.extend(T::class, lambda as EffectLambda)
    }

    @PublishedApi
    internal fun <T : Transmission.Effect> effect(
        routeKey: TransmissionRouteKey,
        lambda: suspend CommunicationScope.(effect: T) -> Unit,
    ) {
        effectRoutes.register(routeKey, lambda as EffectLambda)
    }

    @PublishedApi
    internal fun <T : Transmission.Effect> extendEffect(
        routeKey: TransmissionRouteKey,
        lambda: suspend CommunicationScope.(effect: T) -> Unit,
    ) {
        effectRoutes.extend(routeKey, lambda as EffectLambda)
    }
}
