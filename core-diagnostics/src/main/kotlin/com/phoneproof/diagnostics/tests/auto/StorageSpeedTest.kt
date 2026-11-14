package com.phoneproof.diagnostics.tests.auto

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.phoneproof.diagnostics.model.Category
import com.phoneproof.diagnostics.model.DiagnosticTest
import com.phoneproof.diagnostics.model.Finding
import com.phoneproof.diagnostics.model.Measurement
import com.phoneproof.diagnostics.model.Severity
import com.phoneproof.diagnostics.model.SkipReason
import com.phoneproof.diagnostics.model.TestContext
import com.phoneproof.diagnostics.model.TestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.random.Random
import kotlin.system.measureNanoTime

/**
 * Sequential and 4K random throughput against internal storage.
 *
 * Worn eMMC and UFS degrade long before they fail outright, and the symptom the owner notices is
 * "the phone got slow" — which sellers attribute to software. Numbers here separate a tired flash
 * chip from a full one, and the write path is fsync'd so the figure reflects the device rather than
 * the page cache.
 */
class StorageSpeedTest(
    private val payloadBytes: Long = DEFAULT_PAYLOAD_BYTES,
) : DiagnosticTest {

    override val id = ID
    override val title = "Storage speed"
    override val category = Category.PERFORMANCE
    override val estimatedDurationMs = 25_000L

    override suspend fun run(ctx: TestContext): TestResult = withContext(Dispatchers.IO) {
        val workDir = File(ctx.app.cacheDir, "storage-benchmark").apply { mkdirs() }
        val file = File(workDir, "payload.bin")

        val free = StatFs(Environment.getDataDirectory().absolutePath).let {
            it.availableBlocksLong * it.blockSizeLong
        }

        // Never fill a phone that is already nearly full; shrink the payload instead.
        val payload = minOf(payloadBytes, (free - SAFETY_MARGIN_BYTES).coerceAtLeast(0))
        if (payload < MIN_PAYLOAD_BYTES) {
            return@withContext TestResult.Skipped(
                SkipReason.PRECONDITION_NOT_MET,
                "Only ${free / 1_000_000} MB free — need at least " +
                    "${(MIN_PAYLOAD_BYTES + SAFETY_MARGIN_BYTES) / 1_000_000} MB to benchmark safely",
            )
        }

        try {
            ctx.progress.update(0.1f, "Sequential write")
            val writeMbps = measureSequentialWrite(file, payload) { fraction ->
                ctx.progress.update(0.1f + fraction * 0.35f, "Sequential write")
            }

            ctx.progress.update(0.5f, "Sequential read")
            val readMbps = measureSequentialRead(ctx, file) { fraction ->
                ctx.progress.update(0.5f + fraction * 0.25f, "Sequential read")
            }
            val readIsCacheInfluenced = looksCacheInfluenced(readMbps, writeMbps)

            ctx.progress.update(0.8f, "Random 4K access")
            val random = measureRandomIo(file)

            ctx.progress.update(0.95f, "Cleaning up")

            val measurements = listOf(
                Measurement.number(KEY_SEQ_WRITE, "Sequential write", writeMbps, unit = "MB/s", expected = "400+ on recent flagships, 100+ on budget phones"),
                Measurement.number(
                    KEY_SEQ_READ,
                    "Sequential read",
                    readMbps,
                    unit = "MB/s",
                    expected = "800+ on recent flagships, 200+ on budget phones",
                    note = if (readIsCacheInfluenced) {
                        "upper bound only — the OS served part of this from RAM, so it overstates the flash"
                    } else {
                        null
                    },
                ),
                Measurement.number(KEY_RANDOM_READ, "Random 4K read", random.readIops, unit = "IOPS"),
                Measurement.number(KEY_RANDOM_WRITE, "Random 4K write", random.writeIops, unit = "IOPS"),
                Measurement.number(KEY_PAYLOAD_MB, "Test payload", payload / 1_000_000.0, unit = "MB"),
                Measurement.number(KEY_FREE_GB, "Free space", free / 1_000_000_000.0, unit = "GB"),
            )

            // Grading leans on the fsync'd write and random writes, which cannot be faked by the
            // page cache; the read figure is reported but never drives the verdict.
            val tier = classify(writeMbps, readMbps, readIsCacheInfluenced)

            val findings = buildList {
                if (writeMbps < SLOW_WRITE_MBPS) {
                    add(
                        Finding(
                            Severity.MAJOR,
                            "Sequential write of ${format(writeMbps)} MB/s is below what any healthy " +
                                "phone should manage. Worn flash or a nearly full partition — both " +
                                "make the phone feel slow no matter how often it is reset.",
                            KEY_SEQ_WRITE,
                        ),
                    )
                } else if (writeMbps < MODEST_WRITE_MBPS) {
                    add(
                        Finding(
                            Severity.MINOR,
                            "Write throughput of ${format(writeMbps)} MB/s suggests older eMMC storage " +
                                "or a partition under pressure.",
                            KEY_SEQ_WRITE,
                        ),
                    )
                }

                if (readIsCacheInfluenced) {
                    add(
                        Finding(
                            Severity.INFO,
                            "Read throughput was partly served from RAM and is quoted as an upper " +
                                "bound. The write figure is flushed to flash and is the one to trust.",
                            KEY_SEQ_READ,
                        ),
                    )
                }

                if (free < LOW_FREE_SPACE_BYTES) {
                    add(
                        Finding(
                            Severity.MINOR,
                            "Only ${format(free / 1_000_000_000.0)} GB free. Flash slows sharply when " +
                                "nearly full, so this figure may understate the hardware.",
                            KEY_FREE_GB,
                        ),
                    )
                }
            }

            val summary = "$tier · ${format(writeMbps)} MB/s write" +
                if (readIsCacheInfluenced) "" else ", ${format(readMbps)} MB/s read"

            when {
                findings.any { it.severity == Severity.MAJOR } -> TestResult.Fail(summary, measurements, findings)
                findings.any { it.severity == Severity.MINOR } -> TestResult.Warn(summary, measurements, findings)
                else -> TestResult.Pass(summary, measurements, findings)
            }
        } finally {
            runCatching { file.delete() }
            runCatching { workDir.delete() }
        }
    }

    private fun measureSequentialWrite(
        file: File,
        payload: Long,
        onProgress: (Float) -> Unit,
    ): Double {
        val buffer = ByteArray(BLOCK_BYTES) { (it % 251).toByte() }
        val blocks = (payload / BLOCK_BYTES).toInt().coerceAtLeast(1)

        val nanos = measureNanoTime {
            FileOutputStream(file).use { stream ->
                repeat(blocks) { index ->
                    stream.write(buffer)
                    if (index % PROGRESS_EVERY == 0) onProgress(index.toFloat() / blocks)
                }
                stream.flush()
                // Without this the number measures the page cache, not the flash.
                stream.fd.sync()
            }
        }

        return throughputMbps(blocks.toLong() * BLOCK_BYTES, nanos)
    }

    private fun measureSequentialRead(
        ctx: TestContext,
        file: File,
        onProgress: (Float) -> Unit,
    ): Double {
        evictPageCache(ctx)
        val buffer = ByteArray(BLOCK_BYTES)
        var total = 0L

        val nanos = measureNanoTime {
            file.inputStream().use { stream ->
                var index = 0
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (index++ % PROGRESS_EVERY == 0) {
                        onProgress(total.toFloat() / file.length().coerceAtLeast(1))
                    }
                }
            }
        }

        return throughputMbps(total, nanos)
    }

    private fun measureRandomIo(file: File): RandomIo {
        val length = file.length()
        if (length < RANDOM_BLOCK_BYTES * 2) return RandomIo(0.0, 0.0)

        val buffer = ByteArray(RANDOM_BLOCK_BYTES)
        val maxOffset = length - RANDOM_BLOCK_BYTES
        val random = Random(RANDOM_SEED)

        val readNanos = measureNanoTime {
            RandomAccessFile(file, "r").use { raf ->
                repeat(RANDOM_OPS) {
                    raf.seek(random.nextLong(maxOffset) / RANDOM_BLOCK_BYTES * RANDOM_BLOCK_BYTES)
                    raf.read(buffer)
                }
            }
        }

        val writeNanos = measureNanoTime {
            RandomAccessFile(file, "rw").use { raf ->
                repeat(RANDOM_OPS) {
                    raf.seek(random.nextLong(maxOffset) / RANDOM_BLOCK_BYTES * RANDOM_BLOCK_BYTES)
                    raf.write(buffer)
                }
                raf.fd.sync()
            }
        }

        return RandomIo(
            readIops = iops(RANDOM_OPS, readNanos),
            writeIops = iops(RANDOM_OPS, writeNanos),
        )
    }

    /**
     * Apps cannot drop the page cache, so a freshly written file is often still resident in RAM and
     * reads back at memory speed. Touching a ballast buffer sized against *available* memory evicts
     * some of it, which is the most an unprivileged process can do.
     *
     * This is deliberately best-effort: rather than pretend it is sufficient, the caller checks the
     * result with [looksCacheInfluenced] and labels the number instead of publishing a fiction.
     */
    private fun evictPageCache(ctx: TestContext) {
        runCatching {
            val activityManager = ctx.app
                .getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            val info = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(info)

            // Stay well clear of the low-memory threshold; being killed mid-benchmark helps nobody.
            val headroom = (info.availMem - info.threshold * 2).coerceAtLeast(0)
            val ballastSize = (headroom / 3)
                .coerceIn(0, MAX_BALLAST_BYTES)
                .toInt()
            if (ballastSize < MIN_BALLAST_BYTES) return

            val ballast = ByteArray(ballastSize)
            for (i in ballast.indices step 4096) ballast[i] = 1
        }
    }

    /**
     * A read that massively outruns the fsync'd write, and exceeds any shipping flash controller,
     * is the page cache rather than the device. We report the number but say so.
     */
    private fun looksCacheInfluenced(readMbps: Double, writeMbps: Double): Boolean =
        readMbps > CACHE_SUSPICION_MBPS && readMbps > writeMbps * CACHE_SUSPICION_RATIO

    /**
     * Describes the throughput actually measured rather than naming a storage technology.
     *
     * Android does not expose whether the phone has UFS or eMMC, so inferring "UFS 3.x" from a
     * benchmark number states a guess as a fact — and a budget phone writing 323 MB/s would be
     * labelled a flagship. Performance is what was measured, so performance is what is reported.
     */
    private fun classify(writeMbps: Double, readMbps: Double, readSuspect: Boolean): String {
        val trustedRead = if (readSuspect) 0.0 else readMbps
        return when {
            writeMbps > 300 || trustedRead > 700 -> "Fast storage"
            writeMbps > 150 || trustedRead > 400 -> "Mid-range storage"
            writeMbps > 60 || trustedRead > 150 -> "Entry-level storage"
            else -> "Slow storage"
        }
    }

    private fun throughputMbps(bytes: Long, nanos: Long): Double =
        if (nanos <= 0) 0.0 else bytes / 1_000_000.0 / (nanos / 1_000_000_000.0)

    private fun iops(operations: Int, nanos: Long): Double =
        if (nanos <= 0) 0.0 else operations / (nanos / 1_000_000_000.0)

    private fun format(value: Double) = String.format("%.1f", value)

    private data class RandomIo(val readIops: Double, val writeIops: Double)

    companion object {
        const val ID = "performance.storage_speed"

        const val KEY_SEQ_WRITE = "storage.seq_write_mbps"
        const val KEY_SEQ_READ = "storage.seq_read_mbps"
        const val KEY_RANDOM_READ = "storage.random_read_iops"
        const val KEY_RANDOM_WRITE = "storage.random_write_iops"
        const val KEY_PAYLOAD_MB = "storage.payload_mb"
        const val KEY_FREE_GB = "storage.free_gb"

        private const val DEFAULT_PAYLOAD_BYTES = 256L * 1024 * 1024
        private const val MIN_PAYLOAD_BYTES = 32L * 1024 * 1024
        private const val SAFETY_MARGIN_BYTES = 512L * 1024 * 1024
        private const val LOW_FREE_SPACE_BYTES = 5L * 1000 * 1000 * 1000
        private const val BLOCK_BYTES = 1024 * 1024
        private const val RANDOM_BLOCK_BYTES = 4096
        private const val RANDOM_OPS = 2000
        private const val RANDOM_SEED = 20260824L
        private const val PROGRESS_EVERY = 16
        private const val MIN_BALLAST_BYTES = 32L * 1024 * 1024
        private const val MAX_BALLAST_BYTES = 512L * 1024 * 1024
        /**
         * Lowered from 2500 after a budget MediaTek phone reported 2242 MB/s reads against a
         * 418 MB/s fsync'd write — no entry-level flash does that, so it was page cache slipping
         * under the old threshold. Safe for flagships because the ratio guard below must also
         * hold, and genuine UFS 4.0 reads and writes at comparable speeds.
         */
        private const val CACHE_SUSPICION_MBPS = 1_200.0
        private const val CACHE_SUSPICION_RATIO = 2.0
        private const val SLOW_WRITE_MBPS = 30.0
        private const val MODEST_WRITE_MBPS = 90.0
    }
}
