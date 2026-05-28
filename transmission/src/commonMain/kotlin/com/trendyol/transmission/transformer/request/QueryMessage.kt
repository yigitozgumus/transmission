package com.trendyol.transmission.transformer.request

/**
 * Internal query request/response aliases that make the router query channel read as a
 * request-response flow. Public query APIs still expose [QueryHandler] and [Contract].
 */
internal typealias QueryRequest = QueryType
internal typealias QueryResponse = QueryResult
