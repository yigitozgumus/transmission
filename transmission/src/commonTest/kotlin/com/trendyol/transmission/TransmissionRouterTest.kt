package com.trendyol.transmission

import app.cash.turbine.turbineScope
import com.trendyol.transmission.effect.RouterEffect
import com.trendyol.transmission.router.TransmissionRouter
import com.trendyol.transmission.router.builder.TransmissionRouter
import com.trendyol.transmission.transformer.FakeTransformer
import com.trendyol.transmission.transformer.Transformer
import com.trendyol.transmission.transformer.TestTransformer1
import com.trendyol.transmission.transformer.TestTransformer2
import com.trendyol.transmission.transformer.TestTransformer3
import com.trendyol.transmission.transformer.data.TestData
import com.trendyol.transmission.transformer.data.TestEffect
import com.trendyol.transmission.transformer.data.TestSignal
import com.trendyol.transmission.transformer.dataholder.dataHolder
import com.trendyol.transmission.transformer.extendEffectHandler
import com.trendyol.transmission.transformer.extendSignalHandler
import com.trendyol.transmission.transformer.handler.handlers
import com.trendyol.transmission.transformer.handler.onSignal
import com.trendyol.transmission.transformer.request.Contract
import com.trendyol.transmission.transformer.request.QueryHandler
import com.trendyol.transmission.transformer.request.computation.ComputationDelegate
import com.trendyol.transmission.transformer.request.computation.ComputationDelegateWithArgs
import com.trendyol.transmission.router.loader.TransformerSetLoader
import com.trendyol.transmission.router.streamData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TransmissionRouterTest {

    private lateinit var sut: TransmissionRouter

    private val testDispatcher = UnconfinedTestDispatcher()

    @Test
    fun `GIVEN Router with no transformers WHEN initialize is called THEN router should throw IllegalStateException`() =
        runTest {
            // When
            val error = assertFailsWith<IllegalStateException> {
                sut = TransmissionRouter {
                    addTransformerSet(setOf())
                    addDispatcher(testDispatcher)
                }
            }

            // Then
            assertEquals(
                TransmissionRouter.EMPTY_TRANSFORMER_SET_MESSAGE,
                error.message
            )
        }

    @Test
    fun `GIVEN Router with no loader WHEN initialize is called THEN router should throw IllegalStateException`() {
        // When
        val error = assertFailsWith<IllegalStateException> {
            sut = TransmissionRouter {
                addDispatcher(testDispatcher)
            }
        }

        // Then
        assertEquals(
            TransmissionRouter.EMPTY_TRANSFORMER_SET_MESSAGE,
            error.message
        )
    }

    @Test
    fun `GIVEN Router with empty loader WHEN auto initialization runs THEN router should not initialize`() =
        runTest {
            // When
            sut = TransmissionRouter {
                addLoader(object : TransformerSetLoader {
                    override suspend fun load(): Set<Transformer> = emptySet()
                })
                addDispatcher(testDispatcher)
            }

            // Then
            assertEquals(false, sut.isInitialized.value)
            assertEquals(
                TransmissionRouter.EMPTY_TRANSFORMER_SET_MESSAGE,
                sut.initializationError.value?.message
            )
        }

    @Test
    fun `GIVEN Router with one transformer WHEN initialize is called THEN router should not throw IllegalStateException`() =
        runTest {
            // Given
            val exception = try {
                // When
                sut = TransmissionRouter {
                    addTransformerSet(setOf(FakeTransformer(testDispatcher)))
                    addDispatcher(testDispatcher)
                }
                null
            } catch (e: IllegalStateException) {
                e
            }
            // Then
            assertEquals(exception, null)
        }

    @Test
    fun `GIVEN initialized Router with one Transformer WHEN processSignal is called THEN transformer should contain the signal`() {
        // Given
        val transformer = FakeTransformer(testDispatcher)
        sut = TransmissionRouter {
            addTransformerSet(setOf(transformer))
            addDispatcher(testDispatcher)
        }
        // When
        sut.process(TestSignal)

        // Then
        assertEquals(transformer.signalList.last(), TestSignal)
    }

    @Test
    fun `GIVEN initialized Router with multiple Transformers WHEN processSignal is called THEN all transformers should contain the signal`() {
        // Given
        val transformer1 = TestTransformer1(testDispatcher)
        val transformer2 = TestTransformer2(testDispatcher)
        val transformer3 = TestTransformer3(testDispatcher)
        sut = TransmissionRouter {
            addTransformerSet(setOf(transformer1, transformer2, transformer3))
            addDispatcher(testDispatcher)
        }
        // When
        sut.process(TestSignal)

        // Then
        assertEquals(transformer1.signalList.last(), TestSignal)
        assertEquals(transformer2.signalList.last(), TestSignal)
        assertEquals(transformer3.signalList.last(), TestSignal)
    }

    /**
     * This test depends on the specific implementation of [FakeTransformer]. Added this to check
     * the effect broadcast.
     */
    @Test
    fun `GIVEN initialized Router with multiple Transformers WHEN processSignal is called THEN all transformers should contain the FakeTransformer's Signal`() {
        // Given
        val transformer1 = TestTransformer1(testDispatcher)
        val transformer2 = TestTransformer2(testDispatcher)
        val transformer3 = TestTransformer3(testDispatcher)
        sut = TransmissionRouter {
            addTransformerSet(setOf(transformer1, transformer2, transformer3))
            addDispatcher(testDispatcher)
        }
        // When
        sut.process(TestSignal)

        // Then
        assertEquals(transformer1.effectList.last(), TestEffect)
        assertEquals(transformer2.effectList.last(), TestEffect)
        assertEquals(transformer3.effectList.last(), TestEffect)
    }

    @Test
    fun `GIVEN initialized Router with multiple Transformers WHEN processSignal is called THEN all effects should be sent through onEffect`() =
        runTest {
            turbineScope {
                // Given
                val transformer1 = TestTransformer1(testDispatcher)
                val transformer2 = TestTransformer2(testDispatcher)
                val transformer3 = TestTransformer3(testDispatcher)
                sut = TransmissionRouter {
                    addTransformerSet(setOf(transformer1, transformer2, transformer3))
                    addDispatcher(testDispatcher)
                }
                // When
                val effects = sut.effectStream.testIn(backgroundScope)
                sut.process(TestSignal)
                assertEquals(6, effects.cancelAndConsumeRemainingEvents().size)
                // Then
            }
        }

    @Test
    fun `GIVEN initialized Router with multiple Transformers WHEN processSignal is called THEN all transformers should send the correct TestData`() =
        runTest {
            turbineScope {
                // Given
                val transformer1 = TestTransformer1(testDispatcher)
                val transformer2 = TestTransformer2(testDispatcher)
                val transformer3 = TestTransformer3(testDispatcher)
                sut = TransmissionRouter {
                    addTransformerSet(setOf(transformer1, transformer2, transformer3))
                    addDispatcher(testDispatcher)
                }
                // When
                val collector = sut.dataStream.testIn(this@runTest.backgroundScope)
                sut.process(TestSignal)

                // Then
                assertEquals(TestData("update with TestTransformer1"), collector.awaitItem())
                assertEquals(TestData("update with TestTransformer2"), collector.awaitItem())
                assertEquals(TestData("update with TestTransformer3"), collector.awaitItem())
            }
        }

    @Test
    fun `GIVEN initialized Router with multiple Transformers WHEN processSignal is called THEN all transformers should not contain the RouterPayloadEffect`() {
        // Given
        val transformer1 = TestTransformer1(testDispatcher)
        val transformer2 = TestTransformer2(testDispatcher)
        val transformer3 = TestTransformer3(testDispatcher)
        sut = TransmissionRouter {
            addTransformerSet(setOf(transformer1, transformer2, transformer3))
            addDispatcher(testDispatcher)
        }
        // When
        sut.process(TestSignal)

        // Then
        assertEquals(transformer1.effectList.contains(RouterEffect("")), false)
        assertEquals(transformer2.effectList.contains(RouterEffect("")), false)
        assertEquals(transformer3.effectList.contains(RouterEffect("")), false)
    }

    @Test
    fun `GIVEN Router with manual initialization WHEN created without loader THEN it should initialize later`() {
        // Given
        val transformer = FakeTransformer(testDispatcher)
        sut = TransmissionRouter {
            overrideInitialization()
            addDispatcher(testDispatcher)
        }

        // When
        sut.initialize(object : TransformerSetLoader {
            override suspend fun load(): Set<Transformer> = setOf(transformer)
        })
        sut.process(TestSignal)

        // Then
        assertEquals(TestSignal, transformer.signalList.last())
    }

    @Test
    fun `GIVEN missing handlers WHEN extend handlers are added THEN they should handle transmissions`() {
        // Given
        val signals = mutableListOf<Transmission.Signal>()
        val effects = mutableListOf<Transmission.Effect>()
        val transformer = Transformer(dispatcher = testDispatcher)
            .extendSignalHandler<TestSignal> { signal -> signals.add(signal) }
            .extendEffectHandler<TestEffect> { effect -> effects.add(effect) }
        sut = TransmissionRouter {
            addTransformerSet(setOf(transformer))
            addDispatcher(testDispatcher)
        }

        // When
        sut.process(TestSignal)
        sut.process(TestEffect)

        // Then
        assertEquals(listOf<Transmission.Signal>(TestSignal), signals)
        assertEquals(listOf<Transmission.Effect>(TestEffect), effects)
    }

    @Test
    fun `GIVEN parent signal handler WHEN child signal is processed THEN handler should not run`() {
        // Given
        var handledSignals = 0
        val transformer = Transformer(dispatcher = testDispatcher).apply {
            handlers {
                onSignal<ParentSignal> { handledSignals++ }
            }
        }
        sut = TransmissionRouter {
            addTransformerSet(setOf(transformer))
            addDispatcher(testDispatcher)
        }

        // When
        sut.process(ChildSignal)

        // Then
        assertEquals(0, handledSignals)
    }

    @Test
    fun `GIVEN data holders with same data type WHEN observed by contract THEN streams are separated`() =
        runTest {
            turbineScope {
                // Given
                val firstContract = Contract.dataHolder<TestData>()
                val secondContract = Contract.dataHolder<TestData>()
                val transformer = Transformer(dispatcher = testDispatcher).apply {
                    val firstHolder = dataHolder(TestData("first-initial"), firstContract)
                    val secondHolder = dataHolder(TestData("second-initial"), secondContract)
                    handlers {
                        onSignal<UpdateHoldersSignal> {
                            firstHolder.update { TestData("first-updated") }
                            secondHolder.update { TestData("second-updated") }
                        }
                    }
                }
                sut = TransmissionRouter {
                    addTransformerSet(setOf(transformer))
                    addDispatcher(testDispatcher)
                }
                val firstStream = sut.streamData(firstContract).testIn(backgroundScope)
                val secondStream = sut.streamData(secondContract).testIn(backgroundScope)

                // When
                sut.process(UpdateHoldersSignal)

                // Then updates are separated by contract.
                val firstItem = firstStream.awaitItem()
                val secondItem = secondStream.awaitItem()
                val firstUpdate = if (firstItem == TestData("first-initial")) firstStream.awaitItem() else firstItem
                val secondUpdate = if (secondItem == TestData("second-initial")) secondStream.awaitItem() else secondItem
                assertEquals(TestData("first-updated"), firstUpdate)
                assertEquals(TestData("second-updated"), secondUpdate)
            }
        }

    @Test
    fun `GIVEN cached computation with args WHEN called with different args THEN cache should be keyed by args`() =
        runTest {
            // Given
            val calls = mutableListOf<String>()
            val computation = ComputationDelegateWithArgs<String>(useCache = true) { args ->
                calls.add(args)
                "$args-${calls.size}"
            }
            val queryHandler = unusedQueryHandler()

            // When
            val firstA = computation.getResult(queryHandler, invalidate = false, args = "a")
            val firstB = computation.getResult(queryHandler, invalidate = false, args = "b")
            val secondA = computation.getResult(queryHandler, invalidate = false, args = "a")

            // Then
            assertEquals("a-1", firstA)
            assertEquals("b-2", firstB)
            assertEquals("a-1", secondA)
            assertEquals(listOf("a", "b"), calls)
        }

    @Test
    fun `GIVEN cached computation with args WHEN invalidated THEN it should refresh cached result`() =
        runTest {
            // Given
            var callCount = 0
            val computation = ComputationDelegateWithArgs<String>(useCache = true) { args ->
                callCount++
                "$args-$callCount"
            }
            val queryHandler = unusedQueryHandler()

            // When
            val first = computation.getResult(queryHandler, invalidate = false, args = "a")
            val refreshed = computation.getResult(queryHandler, invalidate = true, args = "a")
            val cachedRefresh = computation.getResult(queryHandler, invalidate = false, args = "a")

            // Then
            assertEquals("a-1", first)
            assertEquals("a-2", refreshed)
            assertEquals("a-2", cachedRefresh)
        }

    @Test
    fun `GIVEN cached nullable computation WHEN called repeatedly THEN it should cache null result`() =
        runTest {
            // Given
            var callCount = 0
            val computation = ComputationDelegate(useCache = true) {
                callCount++
                null
            }
            val queryHandler = unusedQueryHandler()

            // When
            val first = computation.getResult(queryHandler, invalidate = false)
            val second = computation.getResult(queryHandler, invalidate = false)

            // Then
            assertEquals(null, first)
            assertEquals(null, second)
            assertEquals(1, callCount)
        }

    @Test
    fun `GIVEN cached computation WHEN invalidated THEN it should refresh cached result`() =
        runTest {
            // Given
            var callCount = 0
            val computation = ComputationDelegate(useCache = true) {
                callCount++
                "result-$callCount"
            }
            val queryHandler = unusedQueryHandler()

            // When
            val first = computation.getResult(queryHandler, invalidate = false)
            val refreshed = computation.getResult(queryHandler, invalidate = true)
            val cachedRefresh = computation.getResult(queryHandler, invalidate = false)

            // Then
            assertEquals("result-1", first)
            assertEquals("result-2", refreshed)
            assertEquals("result-2", cachedRefresh)
        }

    @Test
    fun `GIVEN computation with args without cache WHEN called repeatedly THEN it should recompute`() =
        runTest {
            // Given
            var callCount = 0
            val computation = ComputationDelegateWithArgs<String>(useCache = false) { args ->
                callCount++
                "$args-$callCount"
            }
            val queryHandler = unusedQueryHandler()

            // When
            val first = computation.getResult(queryHandler, invalidate = false, args = "a")
            val second = computation.getResult(queryHandler, invalidate = false, args = "a")

            // Then
            assertEquals("a-1", first)
            assertEquals("a-2", second)
        }

    private interface ParentSignal : Transmission.Signal

    private object ChildSignal : ParentSignal

    private object UpdateHoldersSignal : Transmission.Signal

    private fun unusedQueryHandler(): QueryHandler = object : QueryHandler {
        override suspend fun <D : Transmission.Data?> getData(contract: Contract.DataHolder<D>): D {
            error("unused")
        }

        override suspend fun <D : Any?> compute(
            contract: Contract.Computation<D>,
            invalidate: Boolean
        ): D {
            error("unused")
        }

        override suspend fun <A : Any, D : Any?> compute(
            contract: Contract.ComputationWithArgs<A, D>,
            args: A,
            invalidate: Boolean
        ): D {
            error("unused")
        }

        override suspend fun execute(contract: Contract.Execution) {
            error("unused")
        }

        override suspend fun <A : Any> execute(
            contract: Contract.ExecutionWithArgs<A>,
            args: A
        ) {
            error("unused")
        }
    }
}
