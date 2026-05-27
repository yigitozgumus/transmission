package com.trendyol.transmission.effect

import com.trendyol.transmission.Transmission
import com.trendyol.transmission.router.TransmissionEnvelope
import com.trendyol.transmission.transformer.request.Contract

internal data class WrappedEffect(
    val envelope: TransmissionEnvelope<Transmission.Effect>
) {
    constructor(
        effect: Transmission.Effect,
        identity: Contract.Identity? = null
    ) : this(
        TransmissionEnvelope(
            payload = effect,
            target = identity,
        )
    )

    val effect: Transmission.Effect
        get() = envelope.payload

    val identity: Contract.Identity?
        get() = envelope.target
}
