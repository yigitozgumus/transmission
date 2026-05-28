package com.trendyol.transmission.router

import com.trendyol.transmission.Transmission
import com.trendyol.transmission.transformer.request.QueryResult
import com.trendyol.transmission.transformer.request.QueryType
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

    internal fun validateContracts(router: TransmissionRouter) {
        val conflicts = routers.value.values
            .asSequence()
            .filterNot { registeredRouter -> registeredRouter === router }
            .flatMap { registeredRouter -> router.contractConflictsWith(registeredRouter).asSequence() }
            .toList()

        check(conflicts.isEmpty()) {
            "Duplicate global router contracts found for ${router.routerName}: ${conflicts.joinToString()}"
        }
    }

    internal fun routeQuery(
        sourceRouter: TransmissionRouter,
        query: QueryType,
    ): Boolean {
        val targetRouter = routers.value.values
            .asSequence()
            .filterNot { router -> router === sourceRouter }
            .firstOrNull { router -> router.canResolve(query) }
            ?: return false

        targetRouter.receiveGlobalQuery(query)
        return true
    }

    internal fun routeQueryResult(result: QueryResult): Boolean {
        val ownerRouter = routers.value[result.owner] ?: return false
        ownerRouter.receiveGlobalQueryResult(result)
        return true
    }

    internal fun clear() {
        routers.update { emptyMap() }
    }
}
