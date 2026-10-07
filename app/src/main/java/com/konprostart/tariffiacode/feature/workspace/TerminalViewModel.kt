package com.konprostart.tariffiacode.feature.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.konprostart.tariffiacode.core.runtime.RuntimeWorkTracker
import com.konprostart.tariffiacode.runtime.local.LocalRuntimeCommandResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class TerminalLineType { INPUT, OUTPUT, ERROR, SYSTEM }

data class TerminalLine(
    val text: String,
    val type: TerminalLineType,
)

data class TerminalUiState(
    val lines: List<TerminalLine> = emptyList(),
    val isRunning: Boolean = false,
    val currentInput: String = "",
    val workingDirectory: String = "/root",
)

class TerminalViewModel(
    /**
     * Runs one command in the sandbox. A function rather than the concrete runner so the failure
     * path is testable; [com.konprostart.tariffiacode.runtime.local.LocalRuntimeCommandRunner.runShell]
     * is wired in by the navigation graph.
     */
    private val runShell: suspend (command: String, timeoutSeconds: Long) -> LocalRuntimeCommandResult,
    /**
     * A shell command run here is real work on the runtime's proot process just like a chat turn,
     * but the terminal never touches
     * [com.konprostart.tariffiacode.data.repository.RuntimeActivityRepository] - the only place that
     * already tracks that as work - so without a lease the device could suspend mid-command.
     */
    private val runtimeWork: RuntimeWorkTracker,
    /** Where the blocking sandbox command runs; injectable so the failure path is testable. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state =
        MutableStateFlow(
            TerminalUiState(
                lines =
                    listOf(
                        TerminalLine("OpenCode Terminal - PRoot Alpine Linux", TerminalLineType.SYSTEM),
                    ),
            ),
        )
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()

    fun executeCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return

        _state.update { s ->
            s.copy(
                lines = appendLine(s.lines, TerminalLine("${s.workingDirectory} $ $trimmed", TerminalLineType.INPUT)),
                currentInput = "",
                isRunning = true,
            )
        }

        viewModelScope.launch {
            val result =
                try {
                    runtimeWork.withLease(TERMINAL_LEASE_TAG) {
                        withContext(ioDispatcher) {
                            val fullCommand =
                                if (_state.value.workingDirectory != "/root") {
                                    "cd ${_state.value.workingDirectory} && $trimmed"
                                } else {
                                    trimmed
                                }
                            runShell(fullCommand, 30L)
                        }
                    }
                } catch (cancellation: CancellationException) {
                    // The ViewModel is going away; do not report it as a command failure.
                    throw cancellation
                } catch (error: Exception) {
                    // Launching or reading the sandbox process failed (I/O, runtime missing). Keep the
                    // terminal usable and show it as an error line instead of crashing the scope.
                    _state.update { s ->
                        s.copy(
                            lines =
                                appendLine(
                                    s.lines,
                                    TerminalLine(error.message ?: "Command failed to run", TerminalLineType.ERROR),
                                ),
                            isRunning = false,
                        )
                    }
                    return@launch
                }

            _state.update { s ->
                val newLines = s.lines.toMutableList()
                if (result.output.isNotBlank()) {
                    result.output.lines().forEach { line ->
                        val type = if (result.exitCode != 0) TerminalLineType.ERROR else TerminalLineType.OUTPUT
                        newLines.add(TerminalLine(line, type))
                    }
                }
                if (result.exitCode != 0 && result.output.isBlank()) {
                    newLines.add(TerminalLine("exit code: ${result.exitCode}", TerminalLineType.ERROR))
                }
                s.copy(
                    lines = trimScrollback(newLines),
                    isRunning = false,
                    workingDirectory = resolveWorkingDirectory(s.workingDirectory, trimmed),
                )
            }
        }
    }

    fun updateInput(text: String) {
        _state.update { it.copy(currentInput = text) }
    }

    fun clear() {
        _state.update {
            it.copy(
                lines =
                    listOf(
                        TerminalLine("OpenCode Terminal - PRoot Alpine Linux", TerminalLineType.SYSTEM),
                    ),
            )
        }
    }

    private fun resolveWorkingDirectory(
        current: String,
        command: String,
    ): String {
        val cdPattern = Regex("""^cd\s+(.*)$""")
        val match = cdPattern.find(command.trim()) ?: return current
        val target = match.groupValues[1].trim().removeSurrounding("\"").removeSurrounding("'")
        return when {
            target.startsWith("/") -> target
            target == "~" -> "/root"
            target == ".." -> current.substringBeforeLast("/", "").ifEmpty { "/" }.ifEmpty { "/" }
            target == "." -> current
            else -> if (current == "/") "/$target" else "$current/$target"
        }
    }

    private fun appendLine(
        lines: List<TerminalLine>,
        line: TerminalLine,
    ): List<TerminalLine> {
        return trimScrollback(lines + line)
    }

    private fun trimScrollback(lines: List<TerminalLine>): List<TerminalLine> {
        return if (lines.size > MAX_SCROLLBACK) lines.takeLast(MAX_SCROLLBACK) else lines
    }

    private companion object {
        const val MAX_SCROLLBACK = 500
        const val TERMINAL_LEASE_TAG = "terminal"
    }
}
