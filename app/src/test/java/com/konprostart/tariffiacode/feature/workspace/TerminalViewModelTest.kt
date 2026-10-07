package com.konprostart.tariffiacode.feature.workspace

import com.konprostart.tariffiacode.core.runtime.RuntimeWorkTracker
import com.konprostart.tariffiacode.runtime.local.LocalRuntimeCommandResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * The terminal runs a blocking sandbox command; launching the process or reading its output can
 * throw (I/O, runtime missing), and that used to escape the ViewModel's coroutine and crash the app.
 * These tests pin that the failure is shown as a terminal error while cancellation still propagates.
 *
 * `Dispatchers.Unconfined` is used for the injected IO dispatcher so the whole chain runs eagerly on
 * the test thread and the assertions can read the final state without scheduler juggling.
 */
class TerminalViewModelTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(runShell: suspend (String, Long) -> LocalRuntimeCommandResult) =
        TerminalViewModel(runShell, RuntimeWorkTracker(), Dispatchers.Unconfined)

    @Test
    fun `a successful command appends its output and stops running`() {
        val vm = viewModel { _, _ -> LocalRuntimeCommandResult(0, "hello\nworld") }

        vm.executeCommand("echo hi")

        assertFalse(vm.state.value.isRunning)
        assertTrue(vm.state.value.lines.any { it.text == "hello" && it.type == TerminalLineType.OUTPUT })
        assertFalse(vm.state.value.lines.any { it.type == TerminalLineType.ERROR })
    }

    @Test
    fun `a failed command shows its exit code as an error`() {
        val vm = viewModel { _, _ -> LocalRuntimeCommandResult(2, "") }

        vm.executeCommand("false")

        assertFalse(vm.state.value.isRunning)
        assertTrue(vm.state.value.lines.any { it.text == "exit code: 2" && it.type == TerminalLineType.ERROR })
    }

    @Test
    fun `an IOException is shown as an error and releases the lease instead of crashing`() {
        val work = RuntimeWorkTracker()
        val vm = TerminalViewModel({ _, _ -> throw IOException("no runtime") }, work, Dispatchers.Unconfined)

        vm.executeCommand("ls")

        assertFalse(vm.state.value.isRunning)
        assertTrue(vm.state.value.lines.any { it.text == "no runtime" && it.type == TerminalLineType.ERROR })
        assertFalse("the work lease must be released", work.active.value)
    }

    @Test
    fun `cancellation is rethrown, not shown as a command error`() {
        val vm = viewModel { _, _ -> throw CancellationException("cancelled") }

        vm.executeCommand("ls")

        assertFalse(
            "a cancellation must not become a command error",
            vm.state.value.lines.any { it.type == TerminalLineType.ERROR },
        )
    }
}
