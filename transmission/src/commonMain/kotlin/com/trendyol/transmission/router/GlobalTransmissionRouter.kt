package com.trendyol.transmission.router

import com.trendyol.transmission.Transmission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide registry that bridges globally registered [TransmissionRouter] instances.
 *
 * This coordinator is intentionally lightweight: routers keep their own local buses and lifecycle, while this
 * registry forwards eligible effects between routers.
 */
object GlobalTransmissionRouter {

    private val routers = MutableStateFlow<Map<String, TransmissionRouter>>(emptyMap())

    internal val registeredRouterCount: Int
        get() = routers.value.size

    internal fun isRegistered(routerName: String): Boolean {
        return routerName in routers.value
    }

    internal fun register(router: TransmissionRouter) {
        routers.update { current ->
            check(router.routerName !in current || current[router.routerName] === router) {
                "Router with identity ${router.routerName} is already registered."
            }
            current + (router.routerName to router)
        }
    }

    internal fun unregister(router: TransmissionRouter) {
        routers.update { current ->
            if (current[router.routerName] === router) {
                current - router.routerName
            } else {
                current
            }
        }
    }

    internal fun publishEffect(
        sourceRouter: TransmissionRouter,
        envelope: TransmissionEnvelope<Transmission.Effect>,
    ) {
        val originRouter = envelope.originRouter ?: sourceRouter.routerName
        val globalEnvelope = envelope.copy(originRouter = originRouter)

        routers.value.values
            .asSequence()
            .filterNot { router -> router === sourceRouter }
            .filter { router -> globalEnvelope.target == null || router.containsTransformer(globalEnvelope.target) }
            .forEach { router -> router.receiveGlobalEffect(globalEnvelope) }
    }

    internal fun clear() {
        routers.update { emptyMap() }
    }
}
