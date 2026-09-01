package com.ayakix.pocketradar.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes a debug report to the app's cache and opens the system share sheet.
 *
 * Share-sheet rather than a save dialog because the report's destination is
 * off-device (a chat, a mail, a drive) — that is the whole point of the
 * export. Files land in `cache/debug_reports/`, so the OS reclaims them
 * automatically and nothing needs a storage permission.
 */
object DebugReportSharer {

    fun share(context: Context, reportJson: String) {
        val dir = File(context.cacheDir, REPORT_DIR).apply { mkdirs() }

        // Keep only a handful of past reports; the cache is not a history.
        dir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_KEPT_REPORTS - 1)
            ?.forEach { it.delete() }

        val name = "pocketradar-debug-${FileTimestamp.format(Date())}.json"
        val file = File(dir, name)
        file.writeText(reportJson)

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "デバッグレポートを共有"))
    }

    private const val REPORT_DIR = "debug_reports"
    private const val MAX_KEPT_REPORTS = 5

    private val FileTimestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
}
