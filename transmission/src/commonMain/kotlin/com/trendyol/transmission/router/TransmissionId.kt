package com.trendyol.transmission.router

import com.trendyol.transmission.Transmission
import kotlin.jvm.JvmInline

/**
 * Stable, generated-or-explicit transmission identifier used for non-reflective signal/effect routing.
 */
@JvmInline
value class TransmissionId<out T : Transmission>(val value: String)

/**
 * Resolves transmission IDs for generated or explicitly keyed transmissions without runtime type reflection.
 */
fun interface TransmissionIdResolver {
    fun idOf(transmission: Transmission): TransmissionId<*>?
}

/**
 * Opt-in annotation for the KSP-based transmission ID generation API.
 *
 * This API generates [TransmissionIdResolver] objects from [@GenerateTransmissionId]-annotated
 * sealed interfaces via Kotlin Symbol Processing.
 * The API surface, including [autoDiscoverIds], generated resolver objects, and the
 * [@GenerateTransmissionId] annotation itself, is experimental and subject to change.
 *
 * To opt in, apply [@OptIn][kotlin.OptIn] at the declaration or file level:
 * ```kotlin
 * @file:OptIn(ExperimentalTransmissionIdGeneration::class)
 * ```
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "Transmission ID generation via KSP is experimental and may change in future releases.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class ExperimentalTransmissionIdGeneration

/**
 * Marks a sealed signal/effect hierarchy for transmission ID generation by the KSP processor.
 *
 * @param name Optional generated object name. Defaults to '<AnnotatedTypeName>Id'.
 * @param scope Optional scope for grouping related resolvers. When set, [autoDiscoverIds]
 *   with that scope registers only resolvers in the same scope, enabling multiple routers to each
 *   discover only their relevant signal/effect hierarchies.
 */
@ExperimentalTransmissionIdGeneration
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class GenerateTransmissionId(
    val name: String = "",
    val scope: String = "",
)
