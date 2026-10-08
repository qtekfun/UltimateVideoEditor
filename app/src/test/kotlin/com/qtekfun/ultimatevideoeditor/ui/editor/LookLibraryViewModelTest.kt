package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.data.LookStore
import com.qtekfun.ultimatevideoeditor.domain.CurvePoint
import com.qtekfun.ultimatevideoeditor.domain.Effect
import com.qtekfun.ultimatevideoeditor.domain.EffectType
import com.qtekfun.ultimatevideoeditor.domain.GradeCurve
import com.qtekfun.ultimatevideoeditor.domain.GradeCurves
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class LookLibraryViewModelTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun library(): LookLibraryViewModel {
        var n = 0
        return LookLibraryViewModel(LookStore(File(temp.root, "looks")), dispatcher) { "id${n++}" }
    }

    private fun grade(contrast: Double = 1.0, curves: GradeCurves? = null): Effect {
        val values = EffectType.COLOR_GRADE.defaults.toMutableList().also { it[15] = contrast }
        return Effect("g", EffectType.COLOR_GRADE, values, curves)
    }

    @Test
    fun `saving a look lists it and survives a new library`() {
        val vm = library()
        vm.save("  Warm  ", grade(contrast = 1.2))
        assertEquals(listOf("Warm"), vm.state.value.looks.map { it.name })
        assertEquals(1.2, vm.state.value.looks.single().values[15], 0.0)
        assertEquals(listOf("Warm"), library().state.value.looks.map { it.name })
    }

    @Test
    fun `a blank name is refused with a message`() {
        val vm = library()
        vm.save("   ", grade())
        assertEquals(emptyList<String>(), vm.state.value.looks.map { it.name })
        assertNotNull(vm.state.value.error)
        vm.clearError()
        assertNull(vm.state.value.error)
    }

    @Test
    fun `a name already in use gets a number`() {
        val vm = library()
        vm.save("Teal", grade())
        vm.save("teal", grade(contrast = 1.5))
        vm.save("Teal", grade(contrast = 0.5))
        assertEquals(listOf("Teal", "teal 2", "Teal 3"), vm.state.value.looks.map { it.name })
    }

    @Test
    fun `deleting a look removes it from the list and the disk`() {
        val vm = library()
        vm.save("One", grade())
        val id = vm.state.value.looks.single().id
        vm.delete(id)
        assertEquals(emptyList<String>(), vm.state.value.looks.map { it.name })
        assertEquals(emptyList<String>(), library().state.value.looks.map { it.name })
    }

    @Test
    fun `copy remembers the grade and its curves for pasting`() {
        val vm = library()
        val curves = GradeCurves(red = GradeCurve(listOf(CurvePoint(0.0, 0.2), CurvePoint(1.0, 0.9))))
        vm.copy(grade(contrast = 1.3, curves = curves))
        val clip = vm.state.value.clipboard!!
        assertEquals(1.3, clip.values[15], 0.0)
        assertEquals(curves, clip.curves)
        vm.copy(grade())
        assertEquals(GradeCurves.IDENTITY, vm.state.value.clipboard!!.curves)
    }

    @Test
    fun `only a colour grade can be saved or copied`() {
        val vm = library()
        val blur = Effect("b", EffectType.BLUR)
        vm.save("Nope", blur)
        vm.copy(blur)
        assertEquals(emptyList<String>(), vm.state.value.looks.map { it.name })
        assertNull(vm.state.value.clipboard)
    }
}
