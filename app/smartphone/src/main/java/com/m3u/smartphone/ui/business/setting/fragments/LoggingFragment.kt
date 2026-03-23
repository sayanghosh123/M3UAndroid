package com.m3u.smartphone.ui.business.setting.fragments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.m3u.core.architecture.preferences.LogLevel
import com.m3u.core.architecture.preferences.PreferencesKeys
import com.m3u.core.architecture.preferences.mutablePreferenceOf
import com.m3u.core.unit.DataUnit
import com.m3u.core.util.basic.title
import com.m3u.data.service.AppLogSnapshot
import com.m3u.i18n.R.string
import com.m3u.smartphone.ui.material.components.Preference
import com.m3u.smartphone.ui.material.components.TextField
import com.m3u.smartphone.ui.material.components.TextPreference
import com.m3u.smartphone.ui.material.ktx.plus
import com.m3u.smartphone.ui.material.model.LocalSpacing

@Composable
internal fun LoggingFragment(
    logSnapshot: AppLogSnapshot,
    sendLogs: (String) -> Unit,
    clearLogs: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val spacing = LocalSpacing.current
    var logLevel by mutablePreferenceOf(PreferencesKeys.LOG_LEVEL)
    var logEmail by mutablePreferenceOf(PreferencesKeys.LOG_EMAIL)

    val logLevelLabel = when (logLevel) {
        LogLevel.WARN -> stringResource(string.feat_setting_logging_level_warn)
        LogLevel.INFO -> stringResource(string.feat_setting_logging_level_info)
        LogLevel.DEBUG -> stringResource(string.feat_setting_logging_level_debug)
        LogLevel.VERBOSE -> stringResource(string.feat_setting_logging_level_verbose)
        else -> stringResource(string.feat_setting_logging_level_error)
    }
    val logSummary = stringResource(
        string.feat_setting_logging_summary,
        logSnapshot.fileCount,
        DataUnit.of(logSnapshot.totalBytes).toString()
    )

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(spacing.small),
        contentPadding = contentPadding + PaddingValues(spacing.medium),
        modifier = modifier.fillMaxSize()
    ) {
        item {
            Preference(
                title = stringResource(string.feat_setting_logging).title(),
                content = stringResource(string.feat_setting_logging_description),
                icon = Icons.Rounded.BugReport
            )
        }
        item {
            TextPreference(
                title = stringResource(string.feat_setting_logging_level).title(),
                content = stringResource(string.feat_setting_logging_level_description),
                icon = Icons.Rounded.Tune,
                trailing = logLevelLabel.title(),
                onClick = {
                    logLevel = when (logLevel) {
                        LogLevel.ERROR -> LogLevel.WARN
                        LogLevel.WARN -> LogLevel.INFO
                        LogLevel.INFO -> LogLevel.DEBUG
                        LogLevel.DEBUG -> LogLevel.VERBOSE
                        else -> LogLevel.ERROR
                    }
                }
            )
        }
        item {
            Preference(
                title = stringResource(string.feat_setting_logging_email).title(),
                content = stringResource(string.feat_setting_logging_email_description),
                icon = Icons.Rounded.Email
            )
        }
        item {
            TextField(
                text = logEmail,
                placeholder = stringResource(string.feat_setting_logging_email_placeholder),
                keyboardType = KeyboardType.Email,
                onValueChange = { logEmail = it }
            )
        }
        item {
            Preference(
                title = stringResource(string.feat_setting_logging_saved_logs).title(),
                content = stringResource(string.feat_setting_logging_saved_logs_description),
                icon = Icons.Rounded.Storage
            )
        }
        item {
            TextPreference(
                title = stringResource(string.feat_setting_logging_saved_logs).title(),
                trailing = logSummary,
                icon = Icons.Rounded.Storage,
                enabled = false,
                onClick = {}
            )
        }
        item {
            Preference(
                title = stringResource(string.feat_setting_logging_send).title(),
                content = stringResource(string.feat_setting_logging_send_description),
                icon = Icons.Rounded.Send,
                onClick = { sendLogs(logEmail) }
            )
        }
        item {
            Preference(
                title = stringResource(string.feat_setting_logging_clear).title(),
                content = stringResource(string.feat_setting_logging_clear_description),
                icon = Icons.Rounded.DeleteSweep,
                onClick = clearLogs
            )
        }
    }
}
