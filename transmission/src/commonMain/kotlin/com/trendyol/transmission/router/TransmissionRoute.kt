package com.trendyol.transmission.router

import com.trendyol.transmission.Transmission
import kotlin.jvm.JvmInline

/**
 * Stable, generated-or-explicit route identifier used for non-reflective signal/effect routing.
 */
@JvmInline
value class TransmissionRouteKey<out T : Transmission>(val value: String)

/**
 * Resolves route keys for generated or explicitly keyed transmissions without runtime type reflection.
 */
fun interface TransmissionRouteResolver {
    fun keyOf(transmission: Transmission): TransmissionRouteKey<*>?
}

/**
 * Marks a sealed signal/effect hierarchy for route key generation by the KSP processor.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class GenerateTransmissionRoutes(
    /** Optional generated object name. Defaults to '<AnnotatedTypeName>Routes'. */
    val name: String = "",
)
