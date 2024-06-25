package com.trendyol.transmission.transformer.query

import com.trendyol.transmission.Transmission

internal class ComputationDelegate(
    private val useCache: Boolean = false,
    private val computation: suspend RequestHandler.() -> Transmission.Data?
) : ComputationOwner {

    private var result: Transmission.Data? = null

    override suspend fun getResult(scope: RequestHandler, invalidate: Boolean): Transmission.Data? {
        return if (useCache && invalidate.not()) {
            result ?: computation(scope).also { result = it }
        } else {
            result = null
            computation(scope)
        }
    }
}
