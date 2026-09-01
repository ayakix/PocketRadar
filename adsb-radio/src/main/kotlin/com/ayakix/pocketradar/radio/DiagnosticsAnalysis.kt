package com.ayakix.pocketradar.radio

/**
 * Turns the raw band-scan and gain-sweep measurements into human-readable
 * conclusions.
 *
 * Kept as a pure function so the reasoning can be unit-tested against
 * synthetic measurements — there is no other way to check the "receiver is
 * being overloaded" verdict without physically standing under a broadcast
 * transmitter.
 *
 * The thresholds below are engineering judgement, not physical constants.
 * They are chosen to be decisive at a site that is obviously broken and quiet
 * at a site that obviously works; the borderline cases are deliberately
 * reported as WARNING rather than CRITICAL.
 */
fun analyze(bands: List<BandResult>, gains: List<GainResult>): List<Finding> {
    val findings = mutableListOf<Finding>()

    findings += analyzeInputLevel(bands)
    findings += analyzeMinimumGainLevel(gains)
    findings += analyzeGainCurve(gains)
    findings += analyzeDecoding(gains)

    // CRITICAL 側を先に見せる。原因の切り分けが目的なので、
    // 「問題なし」の行より「疑わしい」行が上に来るほうが役に立つ。
    return findings.sortedBy { it.severity.ordinal }
}

// ---- 1. 入力レベル: 帯域外の強い信号が入っていないか -------------------------

private fun analyzeInputLevel(bands: List<BandResult>): List<Finding> {
    val findings = mutableListOf<Finding>()
    if (bands.isEmpty()) return findings

    val quietRef = bands.filter { it.target.role == BandRole.QUIET_REFERENCE }
        .minOfOrNull { it.metrics.meanLevelDbfs }

    val survey = bands.filter { it.target.role == BandRole.SURVEY }
    val strongest = survey.maxByOrNull { it.metrics.meanLevelDbfs }

    if (quietRef != null && strongest != null) {
        val excess = strongest.metrics.meanLevelDbfs - quietRef
        val severity = when {
            excess >= StrongCarrierCriticalDb -> Severity.CRITICAL
            excess >= StrongCarrierWarningDb -> Severity.WARNING
            else -> Severity.GOOD
        }
        findings += if (severity == Severity.GOOD) {
            Finding(
                Severity.GOOD,
                "帯域外に強い信号なし",
                "最も強かった ${strongest.target.label} でも、空きチャンネルとの差は " +
                    "${"%.1f".format(excess)} dB です。フロントエンドを飽和させるほどの" +
                    "電波は入っていません。",
            )
        } else {
            Finding(
                severity,
                "帯域外に強力な信号あり: ${strongest.target.label}",
                "空きチャンネル (対照) より ${"%.1f".format(excess)} dB 高い信号が入っています。" +
                    "RTL-SDR のフロントエンドには選択度がほとんどないため、この電波は 1090 MHz に" +
                    "同調していても入力段まで素通りし、増幅器を非線形領域に押し込みます。" +
                    "結果として利得が絞られ (デセンシ)、目的の弱い ADS-B 信号が埋もれます。",
            )
        }
    }

    // 2倍波が 1090 に落ちる送信所は個別に名指しする。周波数が離れているため
    // 「1090 とは無関係」と誤解されやすく、ここが最も説明の要る失敗要因になる。
    bands.filter { it.target.harmonicOfInterestHz != null }.forEach { band ->
        if (quietRef == null) return@forEach
        val excess = band.metrics.meanLevelDbfs - quietRef
        if (excess < StrongCarrierWarningDb) return@forEach
        val harmonicMhz = (band.target.harmonicOfInterestHz ?: 0) / 1e6
        findings += Finding(
            Severity.CRITICAL,
            "2倍波が ADS-B に重なる送信所: ${band.target.label}",
            "${"%.3f".format(band.target.frequencyHz / 1e6)} MHz が対照より " +
                "${"%.1f".format(excess)} dB 高い状態です。この 2 倍は " +
                "${"%.1f".format(harmonicMhz)} MHz で、ADS-B の 1090 MHz とほぼ一致します。" +
                "強い基本波がチューナー入力段を歪ませると、受信機の内部で 2 倍波が生成され、" +
                "目的信号の真上にノイズとして現れます。1090 MHz の SAW バンドパスフィルタを" +
                "アンテナ直後 (LNA より前) に入れて基本波を落とすのが根本対策です。",
        )
    }

    return findings
}

/**
 * 利得 0 dB でも大きな入力があるなら、それだけで異常な強電界だと断定できる。
 * 通常のアンテナ環境では 0 dB のチューナー利得で ADC はほぼ無音になるため、
 * バンドスキャンの結果を待たずに単独で結論を出せる数少ない指標。
 */
private fun analyzeMinimumGainLevel(gains: List<GainResult>): List<Finding> {
    val zeroGain = gains.minByOrNull { it.gainTenthsDb } ?: return emptyList()
    if (zeroGain.gainTenthsDb > MinimumGainTenthsDb) return emptyList()
    if (zeroGain.metrics.meanLevelDbfs <= MinimumGainHotLevelDbfs) return emptyList()

    return listOf(
        Finding(
            Severity.CRITICAL,
            "最小利得でも入力が過大",
            "チューナー利得 ${"%.1f".format(zeroGain.gainDb)} dB でも平均レベルが " +
                "${"%.1f".format(zeroGain.metrics.meanLevelDbfs)} dBFS あります。" +
                "通常の環境ではこの設定でほぼ無音になるはずで、極めて強い電波源が" +
                "近接していることを意味します。利得調整では回避できません。",
        )
    )
}

// ---- 2. ゲイン曲線の形: 飽和しているかどうか --------------------------------

private fun analyzeGainCurve(gains: List<GainResult>): List<Finding> {
    val findings = mutableListOf<Finding>()
    if (gains.size < 2) return findings

    val worstClip = gains.maxByOrNull { it.metrics.clipRate }
    if (worstClip != null && worstClip.metrics.clipRate > ClipRateWarning) {
        val severity =
            if (worstClip.metrics.clipRate > ClipRateCritical) Severity.CRITICAL else Severity.WARNING
        findings += Finding(
            severity,
            "ADC が振り切れている",
            "利得 ${"%.1f".format(worstClip.gainDb)} dB で全サンプルの " +
                "${"%.2f".format(worstClip.metrics.clipRate * 100)}% が 8 bit ADC の上下限に" +
                "貼り付いています。ここまで来ると波形が潰れ、プリアンブル検出も PPM 復調も" +
                "成立しません。利得を下げれば緩和しますが、原因が帯域外の信号なら" +
                "同時に目的信号も小さくなるため、フィルタを入れないと解決しません。",
        )
    }

    val best = gains.maxByOrNull { it.crcValidFrames }
    val highest = gains.maxByOrNull { it.gainTenthsDb }
    if (best == null || highest == null || best.crcValidFrames == 0) return findings

    if (best.gainTenthsDb < highest.gainTenthsDb &&
        highest.crcValidFrames < best.crcValidFrames * CollapseRatio
    ) {
        findings += Finding(
            Severity.CRITICAL,
            "利得を上げると受信数が落ちる (相互変調の兆候)",
            "最良は ${"%.1f".format(best.gainDb)} dB で ${best.crcValidFrames} フレーム、" +
                "最大利得 ${"%.1f".format(highest.gainDb)} dB では ${highest.crcValidFrames} " +
                "フレームまで落ちています。線形に動いている受信機なら利得を上げるほど" +
                "改善するか頭打ちになるはずで、逆に落ちるのは入力過大の典型的な症状です。" +
                "相互変調ひずみは入力が 1 dB 増えるごとに約 3 dB 増えるため、" +
                "ある点を超えると目的信号より速くノイズが育ちます。",
        )
    } else if (best.gainTenthsDb == highest.gainTenthsDb) {
        findings += Finding(
            Severity.GOOD,
            "利得に対して素直に改善している",
            "最大利得 ${"%.1f".format(highest.gainDb)} dB が最良でした。" +
                "受信機は線形領域で動いており、入力過大の兆候はありません。",
        )
    }

    return findings
}

// ---- 3. 復調の成否: 届いていないのか、復調できていないのか -------------------

private fun analyzeDecoding(gains: List<GainResult>): List<Finding> {
    val findings = mutableListOf<Finding>()
    if (gains.isEmpty()) return findings

    val totalValid = gains.sumOf { it.crcValidFrames }
    val totalPreambles = gains.sumOf { it.preambleMatches }
    val best = gains.maxByOrNull { it.crcValidFrames }

    if (totalValid > 0 && best != null) {
        findings += Finding(
            Severity.GOOD,
            "ADS-B を受信できている",
            "最良条件は利得 ${"%.1f".format(best.gainDb)} dB で、" +
                "${"%.1f".format(best.validFramesPerSecond)} フレーム/秒 (CRC 通過) です。" +
                "この利得を常用値にすると受信数が最大になります。",
        )
        return findings
    }

    // ここから先は CRC 通過ゼロ。プリアンブル検出数で原因を二分する。
    if (totalPreambles == 0) {
        findings += Finding(
            Severity.CRITICAL,
            "プリアンブルを 1 つも検出できていない",
            "1090 MHz に ADS-B らしい波形が全く現れていません。アンテナが接続されていない、" +
                "同調していない、あるいは見通し範囲に機体がいない可能性があります。" +
                "帯域外の信号レベルが正常なら、受信機ではなく設置側の問題です。",
        )
        return findings
    }

    val worst = gains.maxByOrNull { it.preambleMatches }
    findings += Finding(
        Severity.CRITICAL,
        "プリアンブルは検出するが CRC が 1 つも通らない",
        "最大 ${"%.0f".format(worst?.preamblesPerSecond ?: 0.0)} 回/秒 の頻度で" +
            "プリアンブル候補が出ているのに、CRC を通過したフレームはゼロです。" +
            "これは検出器がノイズを踏んでいる状態で、実信号が届いていないか、" +
            "SNR が復調に足りていないことを示します。帯域外に強い信号が出ている場合は、" +
            "そのノイズフロア上昇が原因です。",
    )
    return findings
}

// ---- しきい値 ---------------------------------------------------------------

/** 対照チャンネルに対しこれだけ高ければ、飽和を疑う水準。 */
private const val StrongCarrierWarningDb = 12.0

/** ここまで来ると、フィルタなしでは受信は難しいと判断する。 */
private const val StrongCarrierCriticalDb = 25.0

/** 「最小利得」とみなす上限 (0.1 dB 単位)。 */
private const val MinimumGainTenthsDb = 100

/** 利得ほぼ 0 dB でこのレベルを超えるのは、通常環境ではあり得ない。 */
private const val MinimumGainHotLevelDbfs = -35.0

private const val ClipRateWarning = 0.001
private const val ClipRateCritical = 0.02

/** 最大利得での受信数が最良点のこの割合を下回ったら「崩れている」とみなす。 */
private const val CollapseRatio = 0.5
