@file:Suppress("UNCHECKED_CAST")

package com.trendyol.transmission.transformer.handler

import com.trendyol.transmission.Transmission

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

    internal suspend fun dispatchSignal(
        scope: CommunicationScope,
        signal: Transmission.Signal,
    ) {
        signalRoutes.dispatch(scope, signal)
    }

    internal suspend fun dispatchEffect(
        scope: CommunicationScope,
        effect: Transmission.Effect,
    ) {
        effectRoutes.dispatch(scope, effect)
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
}
