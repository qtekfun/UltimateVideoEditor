package com.ultimatevideo.uveditor.mvi

import com.ultimatevideo.uveditor.engine.EngineClient
import com.ultimatevideo.uveditor.engine.EngineException
import com.ultimatevideo.uveditor.ui.hub.HubViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HubViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `engine version reaches state`() {
        val viewModel = HubViewModel(FakeEngine(version = "1.2.3"), dispatcher)

        assertEquals("1.2.3", viewModel.state.value.engineVersion)
        assertNull(viewModel.state.value.errorMessage)
    }

    @Test
    fun `engine failure is surfaced as error state`() {
        val viewModel = HubViewModel(FakeEngine(failure = EngineException("boom")), dispatcher)

        assertNull(viewModel.state.value.engineVersion)
        assertEquals("boom", viewModel.state.value.errorMessage)
    }

    private class FakeEngine(
        private val version: String = "0",
        private val failure: EngineException? = null,
    ) : EngineClient {
        override fun version(): String = failure?.let { throw it } ?: version
    }
}
