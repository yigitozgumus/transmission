package com.trendyol.transmission.transformer

import com.trendyol.transmission.transformer.data.TestData
import com.trendyol.transmission.transformer.dataholder.dataHolder
import com.trendyol.transmission.transformer.request.Contract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TransformerStorageTest {

    @Test
    fun `GIVEN storage with holders computations and executions WHEN clear is called THEN all state is removed`() {
        // Given
        val transformer = Transformer()
        val dataContract = Contract.dataHolder<TestData>()
        val computationContract = Contract.computation<String>()
        val executionContract = Contract.execution()
        transformer.dataHolder(TestData("initial"), dataContract)
        transformer.registerComputation(computationContract) { "computed" }
        transformer.registerExecution(executionContract) {}

        // When
        transformer.storage.clear()

        // Then
        assertEquals(false, transformer.storage.isHolderStateInitialized())
        assertEquals(false, transformer.storage.isHolderDataDefined(dataContract.key))
        assertEquals(false, transformer.storage.hasComputation(computationContract.key))
        assertEquals(false, transformer.storage.hasExecution(executionContract.key))
        assertEquals(null, transformer.storage.getHolderDataByKey(dataContract.key))
    }

    @Test
    fun `GIVEN duplicate data holder with debug name WHEN registered THEN error includes debug name`() {
        // Given
        val transformer = Transformer()
        val dataContract = Contract.dataHolder<TestData>(debugName = "user.state")
        transformer.dataHolder(TestData("initial"), dataContract)

        // When
        val error = assertFailsWith<IllegalArgumentException> {
            transformer.dataHolder(TestData("duplicate"), dataContract)
        }

        // Then
        assertEquals(
            "Multiple data holders with the same key is not allowed: user.state (${dataContract.key})",
            error.message,
        )
    }

    @Test
    fun `GIVEN duplicate computation with debug name WHEN registered THEN error includes debug name`() {
        // Given
        val transformer = Transformer()
        val computationContract = Contract.computation<String>(debugName = "user.count")
        transformer.registerComputation(computationContract) { "computed" }

        // When
        val error = assertFailsWith<IllegalArgumentException> {
            transformer.registerComputation(computationContract) { "duplicate" }
        }

        // Then
        assertEquals(
            "Multiple computations with the same key is not allowed: user.count (${computationContract.key})",
            error.message,
        )
    }

    @Test
    fun `GIVEN duplicate execution with debug name WHEN registered THEN error includes debug name`() {
        // Given
        val transformer = Transformer()
        val executionContract = Contract.execution(debugName = "cache.clear")
        transformer.registerExecution(executionContract) {}

        // When
        val error = assertFailsWith<IllegalArgumentException> {
            transformer.registerExecution(executionContract) {}
        }

        // Then
        assertEquals(
            "Multiple executions with the same key is not allowed: cache.clear (${executionContract.key})",
            error.message,
        )
    }
}
