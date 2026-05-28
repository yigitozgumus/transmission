package com.trendyol.transmission.router

import com.trendyol.transmission.Transmission
import com.trendyol.transmission.transformer.request.Contract

internal data class TransmissionEnvelope<out T : Transmission>(
    val payload: T,
    val source: Contract.Identity? = null,
    val target: Contract.Identity? = null,
    val dataHolder: Contract.DataHolder<*>? = null,
    val originRouter: String? = null,
)
