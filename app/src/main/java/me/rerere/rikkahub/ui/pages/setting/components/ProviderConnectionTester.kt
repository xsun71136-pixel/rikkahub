package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.withSingleApiKeyForRequest
import me.rerere.ai.util.AllKeysSuspendedException
import me.rerere.ai.util.KeyRotationPolicy
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Connect
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.rikkahub.utils.UiState
import org.koin.compose.koinInject

@Composable
fun ProviderConnectionTester(
    internalProvider: ProviderSetting,
) {
    var showTestDialog by remember { mutableStateOf(false) }
    val providerManager = koinInject<ProviderManager>()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    IconButton(onClick = { showTestDialog = true }) {
        Icon(HugeIcons.Connect, null)
    }

    if (showTestDialog) {
        var model by remember(internalProvider) {
            mutableStateOf(internalProvider.models.firstOrNull { it.type == ModelType.CHAT })
        }
        var nonStreamingState: UiState<String> by remember { mutableStateOf(UiState.Idle) }
        var streamingState: UiState<String> by remember { mutableStateOf(UiState.Idle) }
        var toolsState: UiState<String> by remember { mutableStateOf(UiState.Idle) }
        var streamingText by remember { mutableStateOf("") }

        fun resetStates() {
            nonStreamingState = UiState.Idle
            streamingState = UiState.Idle
            toolsState = UiState.Idle
            streamingText = ""
        }

        AlertDialog(
            onDismissRequest = { showTestDialog = false },
            title = { Text(stringResource(R.string.setting_provider_page_test_connection)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ModelSelector(
                        modelId = model?.id,
                        providers = listOf(internalProvider),
                        type = ModelType.CHAT,
                        modifier = Modifier.fillMaxWidth()
                    ) { model = it }

                    TestResultItem(
                        label = stringResource(R.string.setting_provider_page_test_non_streaming),
                        state = nonStreamingState,
                        resultText = (nonStreamingState as? UiState.Success)?.data ?: ""
                    )
                    TestResultItem(
                        label = stringResource(R.string.setting_provider_page_test_streaming),
                        state = streamingState,
                        resultText = streamingText
                    )
                    TestResultItem(
                        label = stringResource(R.string.setting_provider_page_test_tool_call),
                        state = toolsState,
                        resultText = (toolsState as? UiState.Success)?.data ?: ""
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showTestDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
            confirmButton = {
                TextButton(onClick = {
                    val selectedModel = model ?: return@TextButton
                    resetStates()
                    scope.launch {
                        val managed = KeyRotationPolicy.manages(internalProvider)
                        val selectedKey = if (managed) {
                            runCatching { KeyRotationPolicy.pick(internalProvider) }.getOrElse { error ->
                                val wrapped = if (error is AllKeysSuspendedException) error
                                else IllegalStateException(context.getString(R.string.error_all_keys_suspended), error)
                                val state = UiState.Error(wrapped)
                                nonStreamingState = state
                                streamingState = state
                                toolsState = state
                                return@launch
                            }
                        } else null
                        // Pin one concrete key for all three probes. The old implementation passed the
                        // multi-key provider directly, so each probe could choose another key and none
                        // of the failures were attributed to the key that actually failed.
                        val pinnedProvider = selectedKey?.let { internalProvider.withSingleApiKeyForRequest(it) }
                            ?: internalProvider
                        val provider = providerManager.getProviderByType(pinnedProvider)
                        val probeMessages = listOf(
                            UIMessage.system("You are a helpful assistant"),
                            UIMessage.user("hello"),
                        )
                        val reported = AtomicBoolean(false)

                        suspend fun probe(
                            setLoading: () -> Unit,
                            action: suspend () -> String,
                            setSuccess: (String) -> Unit,
                            setError: (Throwable) -> Unit,
                        ): Throwable? {
                            setLoading()
                            return try {
                                val result = withTimeout(30_000L) { action() }
                                setSuccess(result)
                                null
                            } catch (timeout: TimeoutCancellationException) {
                                val error = IllegalStateException(context.getString(R.string.polish_test_timeout), timeout)
                                setError(error)
                                error
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Throwable) {
                                setError(error)
                                error
                            }
                        }

                        val failures = coroutineScope {
                            awaitAll(
                                async {
                                    probe(
                                        setLoading = { nonStreamingState = UiState.Loading },
                                        action = {
                                            val result = provider.generateText(
                                                providerSetting = pinnedProvider,
                                                messages = probeMessages,
                                                params = TextGenerationParams(
                                                    model = selectedModel,
                                                    customHeaders = selectedModel.customHeaders,
                                                    customBody = selectedModel.customBodies,
                                                ),
                                            )
                                            result.message.parts.filterIsInstance<UIMessagePart.Text>()
                                                .joinToString("") { it.text }
                                        },
                                        setSuccess = { nonStreamingState = UiState.Success(it) },
                                        setError = { nonStreamingState = UiState.Error(it) },
                                    )
                                },
                                async {
                                    probe(
                                        setLoading = { streamingState = UiState.Loading; streamingText = "" },
                                        action = {
                                            val builder = StringBuilder()
                                            provider.streamText(
                                                providerSetting = pinnedProvider,
                                                messages = probeMessages,
                                                params = TextGenerationParams(
                                                    model = selectedModel,
                                                    customHeaders = selectedModel.customHeaders,
                                                    customBody = selectedModel.customBodies,
                                                ),
                                            ).collect { chunk ->
                                                if (chunk is StreamChunk.TextDelta) {
                                                    builder.append(chunk.text)
                                                    streamingText = builder.toString()
                                                }
                                            }
                                            builder.toString()
                                        },
                                        setSuccess = { streamingState = UiState.Success("") },
                                        setError = { streamingState = UiState.Error(it) },
                                    )
                                },
                                async {
                                    probe(
                                        setLoading = { toolsState = UiState.Loading },
                                        action = {
                                            val testTool = Tool(
                                                name = "get_current_time",
                                                description = "Get the current date and time.",
                                                execute = { emptyList() },
                                            )
                                            val result = provider.generateText(
                                                providerSetting = pinnedProvider,
                                                messages = listOf(
                                                    UIMessage.system("You are a helpful assistant"),
                                                    UIMessage.user("Use the get_current_time tool."),
                                                ),
                                                params = TextGenerationParams(
                                                    model = selectedModel,
                                                    tools = listOf(testTool),
                                                    customHeaders = selectedModel.customHeaders,
                                                    customBody = selectedModel.customBodies,
                                                ),
                                            )
                                            val toolCall = result.message.parts.filterIsInstance<UIMessagePart.Tool>().firstOrNull()
                                            if (toolCall != null) {
                                                context.getString(R.string.setting_provider_page_test_tool_called, toolCall.toolName, toolCall.input)
                                            } else {
                                                val text = result.message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
                                                context.getString(R.string.setting_provider_page_test_tool_not_called, text)
                                            }
                                        },
                                        setSuccess = { toolsState = UiState.Success(it) },
                                        setError = { toolsState = UiState.Error(it) },
                                    )
                                },
                            )
                        }

                        if (selectedKey != null) {
                            val keyFailure = failures.filterNotNull().firstOrNull { KeyRotationPolicy.isKeyLevelError(it) }
                            if (keyFailure != null) {
                                // Report once even when several parallel probes return the same 401/429.
                                if (reported.compareAndSet(false, true)) {
                                    KeyRotationPolicy.reportFailure(internalProvider.id.toString(), selectedKey, keyFailure)
                                }
                            } else if (failures.all { it == null }) {
                                // A successful external probe may heal a previously cooling key;
                                // never clear health after a mixed/unsupported probe result.
                                KeyRotationPolicy.clearKeyHealth(internalProvider.id.toString(), selectedKey)
                            }
                        }
                    }
                }) { Text(stringResource(R.string.setting_provider_page_test)) }
            },
        )
    }
}

@Composable
private fun TestResultItem(
    label: String,
    state: UiState<String>,
    resultText: String
) {
    var showErrorSheet by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(120.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        when (state) {
            is UiState.Idle -> Text(
                text = "—",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            is UiState.Loading -> LinearWavyProgressIndicator(modifier = Modifier.weight(1f))
            is UiState.Success -> Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.extendColors.green6
                )
                if (resultText.isNotBlank()) {
                    Text(
                        text = resultText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            is UiState.Error -> Text(
                text = state.error.message ?: "Error",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.extendColors.red6,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable { showErrorSheet = true }
            )
        }
    }

    if (showErrorSheet && state is UiState.Error) {
        val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
        val stackTrace = remember(state.error) {
            state.error.stackTraceToString()
        }
        ModalBottomSheet(
            onDismissRequest = { showErrorSheet = false },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.8f)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = state.error.message ?: "Error",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.extendColors.red6
                )
                Text(
                    text = stackTrace,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
