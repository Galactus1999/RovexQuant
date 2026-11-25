package com.rovexquant.app.data.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.rovexquant.app.domain.InstrumentCatalog
import com.rovexquant.app.domain.InstrumentIdentity
import com.rovexquant.app.domain.OhlcvBar
import com.rovexquant.app.domain.OhlcvCsvResearch
import com.rovexquant.app.domain.OhlcvInterval
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

data class ResearchCsvFolderImportReport(
    val scannedFiles: Int,
    val importedFiles: Int,
    val skippedUnchanged: Int,
    val failedFiles: Int,
    val rowsImported: Int,
    val datasets: List<ResearchCsvDatasetMetadata>,
    val errors: List<String>
) {
    val summary: String
        get() = "Scanned $scannedFiles CSV files · imported $importedFiles · unchanged $skippedUnchanged · failed $failedFiles · $rowsImported rows."
}

/** SAF-only, user-selected folder scanner; provider caches remain isolated. */
object ResearchCsvFolderImporter {
    private const val PREFS = "research_csv_folder_import"
    private const val KEY_TREE_URI = "tree_uri"
    private const val FILE_HASH_PREFIX = "document_hash_"
    const val MAX_CSV_BYTES = 20 * 1024 * 1024
    const val MAX_FOLDER_BYTES = 100 * 1024 * 1024
    const val MAX_CSV_FILES = 100
    private const val MAX_ERROR_MESSAGES = 5

    fun savedFolderUri(context: Context): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TREE_URI, null)
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }

    fun persistFolder(context: Context, uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TREE_URI, uri.toString())
            .apply()
    }

    fun clearFolder(context: Context) {
        val uri = savedFolderUri(context)
        if (uri != null) runCatching {
            context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_TREE_URI).apply()
    }

    fun scan(context: Context, treeUri: Uri): ResearchCsvFolderImportReport {
        val resolver = context.contentResolver
        val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE
        )
        val cursor = resolver.query(childrenUri, projection, null, null, null)
            ?: throw IllegalStateException("The selected CSV folder could not be listed.")
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val errors = mutableListOf<String>()
        var scanned = 0
        var imported = 0
        var skipped = 0
        var failed = 0
        var rowsImported = 0
        var totalBytes = 0L

        cursor.use { rows ->
            val idColumn = rows.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = rows.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeColumn = rows.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            while (rows.moveToNext()) {
                val displayName = rows.getString(nameColumn) ?: continue
                if (!displayName.endsWith(".csv", ignoreCase = true)) continue
                if (scanned >= MAX_CSV_FILES) {
                    if (errors.size < MAX_ERROR_MESSAGES) errors += "Stopped after $MAX_CSV_FILES CSV files; split larger folders."
                    break
                }
                scanned++
                try {
                    val reportedSize = if (sizeColumn >= 0 && !rows.isNull(sizeColumn)) rows.getLong(sizeColumn) else null
                    require(reportedSize == null || reportedSize <= MAX_CSV_BYTES) { "File exceeds the 20 MB per-file limit." }
                    val remainingBytes = MAX_FOLDER_BYTES - totalBytes
                    require(remainingBytes > 0) { "Folder scan reached the 100 MB total limit." }
                    val readLimit = minOf(MAX_CSV_BYTES.toLong(), remainingBytes).toInt()
                    require(reportedSize == null || reportedSize <= readLimit) { "Folder scan reached the 100 MB total limit." }

                    val documentId = rows.getString(idColumn)
                    val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                    val bytes = resolver.openInputStream(documentUri)?.use { it.readBounded(readLimit) }
                        ?: throw IllegalArgumentException("Could not open CSV file.")
                    totalBytes += bytes.size
                    val instrument = resolveInstrumentFromFileName(displayName)
                    val contentHash = sha256(bytes)
                    val ledgerKey = FILE_HASH_PREFIX + sha256(documentId.toByteArray(Charsets.UTF_8))
                    val existing = ResearchCsvDatasetStore.load(context, instrument)
                    if (prefs.getString(ledgerKey, null) == contentHash && existing != null) {
                        skipped++
                        continue
                    }
                    val parsed = OhlcvCsvResearch.parse(String(bytes, Charsets.UTF_8))
                    validateDailyBars(parsed)
                    val merged = OhlcvCsvResearch.merge(existing.orEmpty(), parsed)
                    ResearchCsvDatasetStore.save(context, instrument, merged, displayName)
                    // Cache commit precedes the ledger write; a crash only causes a safe idempotent re-import.
                    prefs.edit().putString(ledgerKey, contentHash).apply()
                    imported++
                    rowsImported += parsed.size
                } catch (failure: SecurityException) {
                    throw failure
                } catch (failure: Exception) {
                    failed++
                    if (errors.size < MAX_ERROR_MESSAGES) {
                        errors += displayName + ": " + (failure.message ?: "CSV import failed")
                    }
                }
            }
        }
        return ResearchCsvFolderImportReport(
            scannedFiles = scanned,
            importedFiles = imported,
            skippedUnchanged = skipped,
            failedFiles = failed,
            rowsImported = rowsImported,
            datasets = ResearchCsvDatasetStore.list(context),
            errors = errors
        )
    }

    internal fun resolveInstrumentFromFileName(fileName: String): InstrumentIdentity {
        require(fileName.endsWith(".csv", ignoreCase = true)) { "Only .csv files are supported." }
        val stem = fileName.substringBeforeLast('.', fileName).trim().uppercase(Locale.ROOT)
        val exchangeMatch = Regex("^(NSE|BSE)[_-](.+)$").matchEntire(stem)
        val exchange = exchangeMatch?.groupValues?.get(1) ?: "NSE"
        var symbol = exchangeMatch?.groupValues?.get(2) ?: stem
        symbol = symbol.replace(Regex("[_-](?:1D|DAILY)$"), "")
        if (symbol.startsWith("NIFTY") || symbol.startsWith("BANKNIFTY")) {
            symbol = symbol.replace('_', ' ').replace('-', ' ')
        }
        return InstrumentCatalog.resolve(symbol, exchange, OhlcvInterval.DAILY)
    }

    internal fun validateDailyBars(bars: List<OhlcvBar>) {
        require(bars.isNotEmpty()) { "CSV contains no daily bars." }
        val distinctSessions = bars.map { it.timestamp.toLocalDate() }.distinct().size
        require(distinctSessions == bars.size) {
            "Automatic folder import accepts daily CSVs only (one bar per trading date). Intraday files were not imported."
        }
    }

    private fun InputStream.readBounded(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(output.size() + count <= maxBytes) { "CSV exceeds the configured folder scan byte limit." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
