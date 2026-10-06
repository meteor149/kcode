package ai.meteor.kcode.plugin.recovery

import ai.meteor.kcode.ApplicationHostOptions
import ai.meteor.kcode.plugin.KcodeProfileHost
import ai.meteor.kcode.plugin.ProfileHostPhase
import ai.meteor.kcode.plugin.api.profiles.ProfileSource
import ai.meteor.kcode.plugin.api.profiles.ProfileTarget
import ai.meteor.kcode.ui.design.KcodeDefaultDesignTokens
import ai.meteor.kcode.ui.design.KcodeExtendedColors
import ai.meteor.kcode.ui.design.KcodeSpacing
import ai.meteor.kcode.ui.design.KcodeTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch

/** Native fallback is outside the replaceable product tree. Ready roots retain their own theme. */
@Composable
fun ProfileHostContent(host: KcodeProfileHost, options: ApplicationHostOptions, languageCode: String) {
    val hostState by host.state.collectAsState()
    val client = host.profileCommands
    val session = remember(host, client) { client?.let(::RecoverySession) }
    val scope = rememberCoroutineScope()
    var showDetails by remember(hostState.failure) { mutableStateOf(false) }
    val texts = remember(languageCode) {
        { key: String -> RecoveryResourceStrings[if (languageCode.startsWith("zh")) "${key}_zh" else key]
            ?: RecoveryResourceStrings[key] ?: key }
    }
    if (hostState.phase == ProfileHostPhase.Ready) {
        host.Render(options)
        return
    }
    if (hostState.phase == ProfileHostPhase.Closed) return

    // This host surface uses standard Material roles; no default product theme is loaded.
    val colors = lightColorScheme()
    KcodeTheme(colors, Shapes(), KcodeExtendedColors(colors.surfaceVariant, colors.surface,
        colors.surfaceContainer, colors.secondaryContainer), Typography(), KcodeDefaultDesignTokens) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(KcodeSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(KcodeSpacing.sm)) {
                Text(texts("title"), style = MaterialTheme.typography.headlineSmall)
                if (hostState.phase != ProfileHostPhase.RecoveryRequired) {
                    CircularProgressIndicator()
                    Text(texts("transition"))
                } else {
                    Text(texts("explanation"))
                    hostState.failure?.let { failure ->
                        Text(generateSequence(failure) { it.cause }.last().toString(), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { showDetails = !showDetails }) { Text(texts("details")) }
                        if (showDetails) Text(failure.stackTraceToString())
                    }
                    if (session == null) Text(texts("unavailable")) else {
                        val state by session.state.collectAsState()
                        LaunchedEffect(session, hostState) { session.refresh() }
                        Button(onClick = { scope.launch { session.refresh() } }, enabled = !state.busy) { Text(texts("refresh")) }
                        state.catalogue?.profiles?.forEach { profile ->
                            Text("${profile.displayName} (${profile.id})", style = MaterialTheme.typography.titleMedium)
                            if (profile.generation != null) {
                                OutlinedButton(onClick = { scope.launch { session.select(ProfileTarget(profile.id)) } },
                                    enabled = !state.busy && !state.dirty) {
                                    Text(texts("committed") + if (state.target == ProfileTarget(profile.id)) " (${texts("selected")})" else "")
                                }
                            }
                            if (profile.hasDraft) {
                                val target = ProfileTarget(profile.id, ProfileSource.Draft)
                                OutlinedButton(onClick = { scope.launch { session.select(target) } }, enabled = !state.busy && !state.dirty) {
                                    Text(texts("draft") + if (state.target == target) " (${texts("selected")})" else "")
                                }
                            }
                        }
                        state.target?.let {
                            OutlinedTextField(state.document, session::edit, modifier = Modifier.fillMaxWidth().testTag("recovery-definition"),
                                enabled = !state.busy, label = { Text(texts("document")) }, minLines = 8, maxLines = 16)
                            if (state.dirty) Text(texts("unsaved"))
                            Button(onClick = { scope.launch { session.save() } }, enabled = !state.busy && state.dirty) { Text(texts("save")) }
                            Button(onClick = { scope.launch { session.activate() } }, enabled = !state.busy && !state.dirty) { Text(texts("activate")) }
                            Button(onClick = { scope.launch { session.activate(saveFirst = true) } }, enabled = !state.busy) { Text(texts("save_activate")) }
                            OutlinedButton(onClick = session::discard, enabled = !state.busy && state.dirty) { Text(texts("discard")) }
                        }
                        if (state.busy) CircularProgressIndicator()
                        state.failure?.let { Text(texts(it), color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}
