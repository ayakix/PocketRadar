package com.ayakix.pocketradar.radio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.math.pow

/**
 * The diagnostics verdict exists to be trusted on a rooftop with no way to
 * cross-check it, so the reasoning is pinned here against synthetic
 * measurements that stand in for sites we cannot visit on demand.
 */
class DiagnosticsAnalysisTest {

    // ---- helpers ------------------------------------------------------------

    /** Build metrics whose [SignalMetrics.meanLevelDbfs] equals [dbfs]. */
    private fun metricsAt(
        dbfs: Double,
        clipRate: Double = 0.0,
        peakDbfs: Double = dbfs,
    ): SignalMetrics {
        val mean = SignalMetrics.FullScaleMagnitude * 10.0.pow(dbfs / 20.0)
        val peak = SignalMetrics.FullScaleMagnitude * 10.0.pow(peakDbfs / 20.0)
        val samples = 100_000
        return SignalMetrics(
            sampleCount = samples,
            clippedSamples = (samples * clipRate).toInt(),
            meanMagnitude = mean,
            rmsMagnitude = mean,
            peakMagnitude = peak.toInt().coerceAtLeast(1),
            dcOffsetI = 0.0,
            dcOffsetQ = 0.0,
        )
    }

    private fun band(
        label: String,
        dbfs: Double,
        role: BandRole = BandRole.SURVEY,
        harmonicHz: Int? = null,
        peakDbfs: Double = dbfs,
        clipRate: Double = 0.0,
    ) = BandResult(
        target = BandTarget(label, 500_000_000, role = role, harmonicOfInterestHz = harmonicHz),
        metrics = metricsAt(dbfs, clipRate = clipRate, peakDbfs = peakDbfs),
    )

    private fun gain(
        tenthsDb: Int,
        validFrames: Int,
        preambles: Int = validFrames * 2,
        dbfs: Double = -40.0,
        clipRate: Double = 0.0,
    ) = GainResult(
        gainTenthsDb = tenthsDb,
        metrics = metricsAt(dbfs, clipRate),
        preambleMatches = preambles,
        framesDecoded = preambles,
        crcValidFrames = validFrames,
        windowMillis = 1_000,
    )

    private fun List<Finding>.titled(fragment: String) =
        singleOrNull { it.title.contains(fragment) }

    // ---- 正常なサイト --------------------------------------------------------

    @Test
    fun `healthy site reports no critical findings`() {
        val bands = listOf(
            band("対照 1080", -48.0, BandRole.QUIET_REFERENCE),
            band("対照 1100", -48.0, BandRole.QUIET_REFERENCE),
            band("UHF ch25", -44.0),
        )
        val gains = listOf(
            gain(0, validFrames = 0, preambles = 0, dbfs = -58.0),
            gain(250, validFrames = 40),
            gain(496, validFrames = 90),
        )

        val findings = analyze(bands, gains)

        assertTrue(findings.none { it.severity == Severity.CRITICAL }, "unexpected: $findings")
        assertTrue(findings.any { it.title.contains("受信できている") })
        assertTrue(findings.any { it.title.contains("素直に改善") })
    }

    // ---- 帯域外の強力な送信所 -------------------------------------------------

    @Test
    fun `strong out of band carrier is flagged critical`() {
        val bands = listOf(
            band("対照 1080", -50.0, BandRole.QUIET_REFERENCE),
            band("UHF ch25", -20.0),
        )
        val findings = analyze(bands, listOf(gain(250, validFrames = 5)))

        val finding = findings.titled("帯域外に強力な信号あり")
        assertEquals(Severity.CRITICAL, finding?.severity)
        assertTrue(finding!!.detail.contains("デセンシ"))
    }

    @Test
    fun `harmonic transmitter is called out separately`() {
        val bands = listOf(
            band("対照 1080", -50.0, BandRole.QUIET_REFERENCE),
            band("UHF ch25 日本テレビ", -25.0, harmonicHz = 1_090_285_714),
        )
        val findings = analyze(bands, listOf(gain(250, validFrames = 0, preambles = 0)))

        val finding = findings.titled("2倍波")
        assertEquals(Severity.CRITICAL, finding?.severity)
        assertTrue(finding!!.detail.contains("1090.3"), finding.detail)
        assertTrue(finding.detail.contains("SAW"), "should name the concrete fix")
    }

    @Test
    fun `quiet survey band does not raise the harmonic finding`() {
        val bands = listOf(
            band("対照 1080", -50.0, BandRole.QUIET_REFERENCE),
            band("UHF ch25 日本テレビ", -47.0, harmonicHz = 1_090_285_714),
        )
        val findings = analyze(bands, listOf(gain(496, validFrames = 30)))

        assertTrue(findings.titled("2倍波") == null, "should stay quiet: $findings")
    }

    // ---- 飽和・相互変調 -------------------------------------------------------

    @Test
    fun `frame count collapsing at high gain is reported as intermodulation`() {
        val gains = listOf(
            gain(0, validFrames = 2),
            gain(166, validFrames = 60),
            gain(328, validFrames = 25),
            gain(496, validFrames = 4),
        )
        val findings = analyze(emptyList(), gains)

        val finding = findings.titled("相互変調の兆候")
        assertEquals(Severity.CRITICAL, finding?.severity)
        assertTrue(finding!!.detail.contains("3 dB"), "should explain the 3:1 slope")
    }

    @Test
    fun `adc clipping is reported`() {
        val gains = listOf(
            gain(0, validFrames = 10),
            gain(496, validFrames = 12, clipRate = 0.05),
        )
        val findings = analyze(emptyList(), gains)

        assertEquals(Severity.CRITICAL, findings.titled("ADC が振り切れている")?.severity)
    }

    @Test
    fun `hot input at minimum gain is decisive on its own`() {
        val gains = listOf(gain(0, validFrames = 0, preambles = 0, dbfs = -20.0))
        val findings = analyze(emptyList(), gains)

        assertEquals(Severity.CRITICAL, findings.titled("最小利得でも入力が過大")?.severity)
    }

    // ---- 帯域外の飽和 ---------------------------------------------------------

    @Test
    fun `clipping on a survey band is reported even when 1090 is clean`() {
        // スカイツリー直下の再現: ch25 でクリップ率 25.6 %、1090 MHz は 0 %。
        // ゲインスイープ側だけを見ていると見逃す組み合わせ。
        val bands = listOf(
            band("対照 1080", -48.6, BandRole.QUIET_REFERENCE),
            band("UHF ch25 日本テレビ", -5.5, peakDbfs = 0.0, clipRate = 0.256),
            band("UHF ch21 フジテレビ", -8.1, peakDbfs = 0.0, clipRate = 0.052),
        )
        val gains = listOf(
            gain(0, validFrames = 0, preambles = 7268, dbfs = -49.1),
            gain(496, validFrames = 0, preambles = 8330, dbfs = -32.0),
        )

        val finding = analyze(bands, gains).titled("帯域外の信号で ADC が振り切れている")

        assertEquals(Severity.CRITICAL, finding?.severity)
        assertTrue(finding!!.title.contains("ch25"), finding.title)
        assertTrue(finding.detail.contains("フジテレビ"), "should name the other clipped bands")
        assertTrue(finding.detail.contains("SAW"), "should name the concrete fix")
    }

    @Test
    fun `no clipping on any band stays silent`() {
        val bands = listOf(
            band("対照 1080", -48.0, BandRole.QUIET_REFERENCE),
            band("UHF ch25", -29.0),
        )
        val findings = analyze(bands, listOf(gain(402, validFrames = 20)))

        assertTrue(findings.titled("帯域外の信号で ADC") == null, "unexpected: $findings")
    }

    // ---- パルス性干渉 (DME) ---------------------------------------------------

    @Test
    fun `quiet reference with huge peak-to-mean ratio flags pulse interference`() {
        // 羽田実測の再現: 対照 1080 MHz が平均 -47 dBFS / ピーク -4.8 dBFS。
        val bands = listOf(
            band("対照 1080", -47.0, BandRole.QUIET_REFERENCE, peakDbfs = -4.8),
            band("対照 1100", -47.0, BandRole.QUIET_REFERENCE, peakDbfs = -34.0),
        )
        val findings = analyze(bands, listOf(gain(402, validFrames = 4)))

        val finding = findings.titled("パルス性信号")
        assertEquals(Severity.WARNING, finding?.severity)
        assertTrue(finding!!.detail.contains("DME"))
    }

    @Test
    fun `moderate peaks on quiet references stay silent`() {
        val bands = listOf(
            band("対照 1080", -48.0, BandRole.QUIET_REFERENCE, peakDbfs = -34.0),
        )
        val findings = analyze(bands, listOf(gain(402, validFrames = 40)))

        assertTrue(findings.titled("パルス性信号") == null, "unexpected: $findings")
    }

    // ---- 届いていない vs 復調できない -----------------------------------------

    @Test
    fun `no preamble at all points at the antenna side`() {
        val gains = listOf(gain(250, validFrames = 0, preambles = 0), gain(496, validFrames = 0, preambles = 0))
        val findings = analyze(emptyList(), gains)

        val finding = findings.titled("プリアンブルを 1 つも検出できていない")
        assertEquals(Severity.CRITICAL, finding?.severity)
        assertTrue(finding!!.detail.contains("設置側"))
    }

    @Test
    fun `preambles without crc point at noise not absence`() {
        val gains = listOf(gain(496, validFrames = 0, preambles = 5_000))
        val findings = analyze(emptyList(), gains)

        val finding = findings.titled("CRC が 1 つも通らない")
        assertEquals(Severity.CRITICAL, finding?.severity)
        assertTrue(finding!!.detail.contains("ノイズを踏んでいる"))
    }

    // ---- 並び順 --------------------------------------------------------------

    @Test
    fun `critical findings sort ahead of good ones`() {
        val bands = listOf(
            band("対照 1080", -50.0, BandRole.QUIET_REFERENCE),
            band("UHF ch25", -18.0),
        )
        val gains = listOf(gain(166, validFrames = 50), gain(496, validFrames = 3))

        val severities = analyze(bands, gains).map { it.severity }

        assertEquals(severities.sortedBy { it.ordinal }, severities)
        assertEquals(Severity.CRITICAL, severities.first())
    }
}
