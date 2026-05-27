package com.trendyol.transmission.router

import com.trendyol.transmission.Transmission
import com.trendyol.transmission.transformer.request.Contract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

internal class TransmissionBus(
    scope: CoroutineScope,
    capacity: Capacity,
) {
    private val signalBroadcast = scope.createBroadcast<TransmissionEnvelope<Transmission.Signal>>(capacity = capacity)
    private val dataBroadcast = scope.createBroadcast<TransmissionEnvelope<Transmission.Data>>(capacity = capacity)
    private val effectBroadcast = scope.createBroadcast<TransmissionEnvelope<Transmission.Effect>>(capacity = capacity)

    val signalStream: SharedFlow<TransmissionEnvelope<Transmission.Signal>> = signalBroadcast.output
    val dataStream: SharedFlow<TransmissionEnvelope<Transmission.Data>> = dataBroadcast.output
    val effectStream: SharedFlow<TransmissionEnvelope<Transmission.Effect>> = effectBroadcast.output

    val dataProducer: SendChannel<TransmissionEnvelope<Transmission.Data>> = dataBroadcast.producer
    val effectProducer: SendChannel<TransmissionEnvelope<Transmission.Effect>> = effectBroadcast.producer

    val dataPayloadStream: SharedFlow<Transmission.Data> = dataStream
        .map { it.payload }
        .shareIn(scope, SharingStarted.Lazily)

    val effectPayloadStream: SharedFlow<Transmission.Effect> = effectStream
        .map { it.payload }
        .shareIn(scope, SharingStarted.Lazily)

    suspend fun send(signal: Transmission.Signal) {
        signalBroadcast.producer.send(TransmissionEnvelope(payload = signal))
    }

    suspend fun send(effect: Transmission.Effect) {
        effectBroadcast.producer.send(TransmissionEnvelope(payload = effect))
    }

    fun effectsFor(identity: Contract.Identity): Flow<TransmissionEnvelope<Transmission.Effect>> {
        return effectStream.filter { it.target == null || it.target == identity }
    }
}
