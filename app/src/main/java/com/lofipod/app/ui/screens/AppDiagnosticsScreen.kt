@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lofipod.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lofipod.app.diagnostics.AppDiagnostics
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App-wide bug telemetry screen. Distinct from
 * [AudioDiagnosticsScreen] (which is audio-chain specific) — this one
 * shows feed failures, download failures, and other notable events
 * across subsystems. Read-only; entries clear on app process restart
 * (the underlying [AppDiagnostics] keeps an in-memory ring buffer).
 *
 * Layout: grouped by category. Each category renders a header + the
 * recent entries within. Newest first. A "Copy" button on each
 * category dumps that section to clipboard for sharing in a bug
 * report. A "Clear" action wipes the whole log.
 */
@Composable
fun AppDiagnosticsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val entries by AppDiagnostics.entries.collectAsState()
    val grouped = remember(entries) {
        AppDiagnostics.Category.values().associateWith { cat ->
            entries.filter { it.category == cat }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App diagnostics") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back",
                            modifier = Modifier.size(28.dp))
                    }
                },
                actions = {
                    TextButton(onClick = { AppDiagnostics.clear() }) { Text("Clear") }
                },
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "No bugs recorded since last process start.\n\n" +
                        "Failed feed loads, failed downloads, and skipped " +
                        "scripture-tag attempts will land here as the app " +
                        "encounters them.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for (cat in AppDiagnostics.Category.values()) {
                val list = grouped[cat] ?: emptyList()
                if (list.isEmpty()) continue
                item(key = "header_${cat.name}") {
                    CategoryHeader(cat = cat, count = list.size, onCopy = {
                        copyToClipboard(ctx, formatCategory(cat, list))
                    })
                }
                items(list, key = { "${cat.name}_${it.timestampMs}_${it.identifier.hashCode()}" }) { entry ->
                    EntryRow(entry)
                }
            }
        }
    }
}

@Composable
private fun CategoryHeader(
    cat: AppDiagnostics.Category,
    count: Int,
    onCopy: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${cat.label} ($count)",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onCopy) { Text("Copy") }
    }
}

@Composable
private fun EntryRow(entry: AppDiagnostics.Entry) {
    SelectionContainer {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    entry.identifier,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    entry.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    formatTimestamp(entry.timestampMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatTimestamp(ms: Long): String {
    val fmt = SimpleDateFormat("MMM d HH:mm:ss", Locale.US)
    return fmt.format(Date(ms))
}

private fun formatCategory(cat: AppDiagnostics.Category, entries: List<AppDiagnostics.Entry>): String =
    buildString {
        appendLine("[${cat.label}]")
        for (e in entries) {
            appendLine("  ${formatTimestamp(e.timestampMs)}  ${e.identifier}")
            appendLine("    ${e.detail}")
        }
    }

private fun copyToClipboard(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("LofiPod app diagnostics", text))
    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
}
