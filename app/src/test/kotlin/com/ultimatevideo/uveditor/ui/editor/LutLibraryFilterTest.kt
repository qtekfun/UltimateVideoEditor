package com.ultimatevideo.uveditor.ui.editor

import com.ultimatevideo.uveditor.data.LutInfo
import com.ultimatevideo.uveditor.data.LutStore
import com.ultimatevideo.uveditor.domain.FilterPack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class LutLibraryFilterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(store: LutStore) = LutLibraryViewModel(store, { error("no files are read here") }, io = dispatcher)

    @Test
    fun `installing a filter stores it, lists it and hands over its library key`() {
        val store = LutStore(tmp.newFolder("luts"))
        val vm = viewModel(store)
        var installed: LutInfo? = null

        vm.installFilter("cinematic") { installed = it }

        val info = checkNotNull(installed)
        assertEquals("Cinematic", info.name)
        assertEquals(FilterPack.CUBE_SIZE, info.size)
        assertEquals(listOf(info), vm.state.value.luts)
        assertFalse(vm.state.value.importing)
        assertNotNull(store.load(info.key))
    }

    @Test
    fun `installing the same filter again does not add a second copy`() {
        val store = LutStore(tmp.newFolder("luts"))
        val vm = viewModel(store)
        val keys = mutableListOf<Int>()

        vm.installFilter("noir") { keys += it.key }
        vm.installFilter("noir") { keys += it.key }

        assertEquals(2, keys.size)
        assertEquals(keys[0], keys[1])
        assertEquals(1, store.list().size)
        assertEquals(1, vm.state.value.luts.size)
    }

    @Test
    fun `an unknown filter id does nothing`() {
        val vm = viewModel(LutStore(tmp.newFolder("luts")))
        var called = false

        vm.installFilter("does-not-exist") { called = true }

        assertFalse(called)
        assertTrue(vm.state.value.luts.isEmpty())
        assertFalse(vm.state.value.importing)
    }
}
