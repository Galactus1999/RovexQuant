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
import com.rovexquant.app.domain.InstrumentType
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = savedFolderUri(context)
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val saved = prefs.edit().putString(KEY_TREE_URI, uri.toString()).commit()
        if (!saved) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            throw IllegalStateException("Folder permission could not be saved.")
        }
        if (previous != null && previous != uri) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(previous, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    fun clearFolder(context: Context) {
        val uri = savedFolderUri(context)
        if (uri != null) runCatching {
            context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_TREE_URI).commit()
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
                    val bounded = resolver.openInputStream(documentUri)?.use { it.readBounded(readLimit) }
                        ?: throw IllegalArgumentException("Could not open CSV file.")
                    totalBytes += bounded.bytesRead
                    require(!bounded.exceeded) { "CSV exceeds the configured per-file or folder byte limit." }
                    val bytes = bounded.bytes
                    val instrument = resolveInstrumentFromFileName(displayName)
                    val contentHash = sha256(bytes)
                    val ledgerIdentity = documentId + "|" + instrument.exchange + "|" +
                        instrument.canonicalSymbol + "|" + instrument.instrumentType.name
                    val ledgerKey = FILE_HASH_PREFIX + sha256(ledgerIdentity.toByteArray(Charsets.UTF_8))
                    val existing = ResearchCsvDatasetStore.load(context, instrument)
                    if (prefs.getString(ledgerKey, null) == contentHash && existing != null) {
                        skipped++
                        continue
                    }
                    val parsed = parseFolderCsv(String(bytes, Charsets.UTF_8), instrument.instrumentType == InstrumentType.EQUITY)
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
        val marketZone = ZoneId.of("Asia/Kolkata")
        val distinctSessions = bars.map { it.timestamp.atZoneSameInstant(marketZone).toLocalDate() }.distinct().size
        require(distinctSessions == bars.size) {
            "Automatic folder import accepts daily CSVs only (one bar per trading date). Intraday files were not imported."
        }
    }


    internal fun parseFolderCsv(content: String, volumeRequired: Boolean): List<OhlcvBar> {
        val lines = content.lineSequence().filter { it.isNotBlank() }.iterator()
        require(lines.hasNext()) { "CSV is empty." }
        val headers = parseCsvRecord(lines.next()).mapIndexed { index, value ->
            (if (index == 0) value.removePrefix("\uFEFF") else value)
                .trim().lowercase(Locale.ROOT).replace(" ", "").replace("_", "")
        }
        fun findHeader(vararg names: String): Int {
            val index = headers.indexOfFirst { it in names }
            require(index >= 0) { "CSV is missing required column: " + names.first() }
            return index
        }
        val dateIndex = findHeader("timestamp", "date", "datetime", "time")
        val openIndex = findHeader("open")
        val highIndex = findHeader("high")
        val lowIndex = findHeader("low")
        val closeIndex = findHeader("close", "adjclose")
        val volumeIndex = headers.indexOfFirst { it == "volume" || it == "vol" }
        require(!volumeRequired || volumeIndex >= 0) { "Equity CSV must contain a Volume column." }
        val zone = ZoneId.of("Asia/Kolkata")
        val patterns = listOf(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH)
        )
        fun timestamp(raw: String, row: Int): OffsetDateTime {
            val value = raw.trim()
            runCatching { OffsetDateTime.parse(value) }.getOrNull()?.let { return it }
            runCatching { LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME) }
                .getOrNull()?.let { return it.atZone(zone).toOffsetDateTime() }
            patterns.forEach { pattern ->
                val date = runCatching { LocalDate.parse(value, pattern) }.getOrNull()
                if (date != null) return date.atStartOfDay(zone).toOffsetDateTime()
            }
            throw IllegalArgumentException("CSV row " + row + " has an unsupported date/time; use ISO date or a recognised daily date format.")
        }
        fun price(raw: String, field: String, row: Int): Double {
            val parsed = raw.trim().replace(",", "").toDoubleOrNull()
            require(parsed != null && parsed.isFinite() && parsed > 0.0) {
                "CSV row " + row + " has invalid " + field + " price."
            }
            return parsed
        }
        val bars = ArrayList<OhlcvBar>()
        var previous: OffsetDateTime? = null
        var rowNumber = 1
        while (lines.hasNext()) {
            val cells = parseCsvRecord(lines.next())
            rowNumber++
            require(cells.size == headers.size) { "CSV row " + rowNumber + " has the wrong number of columns." }
            fun cell(index: Int) = cells[index].trim()
            val date = timestamp(cell(dateIndex), rowNumber)
            require(previous?.let { date.isAfter(it) } ?: true) {
                "CSV timestamps must be strictly increasing (row " + rowNumber + ")."
            }
            val open = price(cell(openIndex), "open", rowNumber)
            val high = price(cell(highIndex), "high", rowNumber)
            val low = price(cell(lowIndex), "low", rowNumber)
            val close = price(cell(closeIndex), "close", rowNumber)
            val volume = if (volumeIndex < 0 || cell(volumeIndex).isBlank()) 0L
                else cell(volumeIndex).replace(",", "").toLongOrNull()
                    ?: throw IllegalArgumentException("CSV row " + rowNumber + " has invalid volume.")
            require(volume >= 0L) { "CSV row " + rowNumber + " has negative volume." }
            require(high >= maxOf(open, close) && low <= minOf(open, close) && high >= low) {
                "CSV row " + rowNumber + " has invalid OHLC range."
            }
            bars += OhlcvBar(date, open, high, low, close, volume)
            previous = date
            require(bars.size <= 100_000) { "CSV exceeds the 100,000-row safety limit." }
        }
        require(bars.isNotEmpty()) { "CSV contains no data rows." }
        return bars
    }

    private fun parseCsvRecord(line: String): List<String> {
        val fields = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    fields += field.toString()
                    field.setLength(0)
                }
                else -> field.append(char)
            }
            index++
        }
        require(!quoted) { "CSV contains an unterminated quoted field." }
        fields += field.toString()
        return fields
    }

    private data class BoundedRead(val bytes: ByteArray, val bytesRead: Int, val exceeded: Boolean)

    private fun InputStream.readBounded(maxBytes: Int): BoundedRead {
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            val remaining = maxBytes - output.size()
            if (count > remaining) {
                if (remaining > 0) output.write(buffer, 0, remaining)
                return BoundedRead(output.toByteArray(), maxBytes, true)
            }
            output.write(buffer, 0, count)
        }
        return BoundedRead(output.toByteArray(), output.size(), false)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
