@file:Suppress("UNCHECKED_CAST")

package com.trendyol.transmission.transformer.handler

import com.trendyol.transmission.Transmission
import com.trendyol.transmission.router.TransmissionId
import kotlin.reflect.KClass

typealias SignalLambda = TransmissionLambda<Transmission.Signal>
typealias EffectLambda = TransmissionLambda<Transmission.Effect>

class HandlerRegistry internal constructor() {

    @PublishedApi
    internal val signalRegistry = RouteRegistry<Transmission.Signal>()

    @PublishedApi
    internal val effectRegistry = RouteRegistry<Transmission.Effect>()

    internal fun clear() {
        signalRegistry.clear()
        effectRegistry.clear()
    }

    internal fun signalTypes(): Set<KClass<out Transmission.Signal>> {
        return signalRegistry.registeredTypes()
    }

    internal fun signalTransmissionIds(): Set<TransmissionId<*>> {
        return signalRegistry.transmissionIds()
    }

    internal fun effectTypes(): Set<KClass<out Transmission.Effect>> {
        return effectRegistry.registeredTypes()
    }

    internal fun effectTransmissionIds(): Set<TransmissionId<*>> {
        return effectRegistry.transmissionIds()
    }

    internal suspend fun dispatchSignal(
        scope: CommunicationScope,
        signal: Transmission.Signal,
        transmissionId: TransmissionId<*>? = null,
    ) {
        signalRegistry.dispatch(scope, signal, transmissionId)
    }

    internal suspend fun dispatchEffect(
        scope: CommunicationScope,
        effect: Transmission.Effect,
        transmissionId: TransmissionId<*>? = null,
    ) {
        effectRegistry.dispatch(scope, effect, transmissionId)
    }

    @PublishedApi
    internal inline fun <reified T : Transmission.Signal> signal(
        noinline lambda: suspend CommunicationScope.(signal: T) -> Unit
    ) {
        signalRegistry.register(T::class, lambda as SignalLambda)
    }

    @PublishedApi
    internal inline fun <reified T : Transmission.Signal> extendSignal(
        noinline lambda: suspend CommunicationScope.(signal: T) -> Unit
    ) {
        signalRegistry.extend(T::class, lambda as SignalLambda)
    }

    @PublishedApi
    internal fun <T : Transmission.Signal> signal(
        transmissionId: TransmissionId<T>,
        lambda: suspend CommunicationScope.(signal: T) -> Unit,
    ) {
        signalRegistry.register(transmissionId, lambda as SignalLambda)
    }

    @PublishedApi
    internal fun <T : Transmission.Signal> extendSignal(
        transmissionId: TransmissionId<T>,
        lambda: suspend CommunicationScope.(signal: T) -> Unit,
    ) {
        signalRegistry.extend(transmissionId, lambda as SignalLambda)
    }

    @PublishedApi
    internal inline fun <reified T : Transmission.Effect> effect(
        noinline lambda: suspend CommunicationScope.(effect: T) -> Unit
    ) {
        effectRegistry.register(T::class, lambda as EffectLambda)
    }

    @PublishedApi
    internal inline fun <reified T : Transmission.Effect> extendEffect(
        noinline lambda: suspend CommunicationScope.(effect: T) -> Unit
    ) {
        effectRegistry.extend(T::class, lambda as EffectLambda)
    }

    @PublishedApi
    internal fun <T : Transmission.Effect> effect(
        transmissionId: TransmissionId<T>,
        lambda: suspend CommunicationScope.(effect: T) -> Unit,
    ) {
        effectRegistry.register(transmissionId, lambda as EffectLambda)
    }

    @PublishedApi
    internal fun <T : Transmission.Effect> extendEffect(
        transmissionId: TransmissionId<T>,
        lambda: suspend CommunicationScope.(effect: T) -> Unit,
    ) {
        effectRegistry.extend(transmissionId, lambda as EffectLambda)
    }
}
