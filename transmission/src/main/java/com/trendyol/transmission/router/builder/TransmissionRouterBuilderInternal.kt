package com.trendyol.transmission.router.builder

import com.trendyol.transmission.router.RegistryScope
import com.trendyol.transmission.router.RegistryScopeImpl
import com.trendyol.transmission.router.loader.TransformerSetLoader
import com.trendyol.transmission.transformer.Transformer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal class TransmissionRouterBuilderInternal internal constructor(
    scope: TransmissionTestingRouterBuilderScope.() -> Unit
) {

    internal var dispatcher: CoroutineDispatcher = Dispatchers.Default
    internal var registryScope: RegistryScopeImpl? = null
    internal lateinit var transformerSetLoader: TransformerSetLoader
    internal var autoInitialization: Boolean = true

    private val scopeImpl = object : TransmissionTestingRouterBuilderScope {

        override fun testing(scope: RegistryScope.() -> Unit) {
            this@TransmissionRouterBuilderInternal.registryScope = RegistryScopeImpl().apply(scope)
        }

        override fun addDispatcher(dispatcher: CoroutineDispatcher) {
            this@TransmissionRouterBuilderInternal.dispatcher = dispatcher

        }

        override fun addTransformerSet(transformerSet: Set<Transformer>) {
            val loader = object: TransformerSetLoader {
                override suspend fun load(): Set<Transformer> {
                    return transformerSet
                }
            }
            addLoader(loader)
        }

        override fun addLoader(loader: TransformerSetLoader) {
            this@TransmissionRouterBuilderInternal.transformerSetLoader = loader
        }

        override fun overrideInitialization() {
            this@TransmissionRouterBuilderInternal.autoInitialization = false
        }
    }

    init {
        scopeImpl.apply(scope)
    }
}
