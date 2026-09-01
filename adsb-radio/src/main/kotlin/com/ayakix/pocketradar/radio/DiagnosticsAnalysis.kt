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
    findings += analyzeBandClipping(bands)
    findings += analyzePulseInterference(bands)
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
 * 帯域スキャン側の ADC クリップを見る。
 *
 * ゲインスイープは 1090 MHz でしか測らないので、帯域外の送信所で入力段が
 * 潰れていてもクリップ率はゼロのまま出てこない。スカイツリー直下の実測
 * (ch25 でクリップ率 25.6 %、1090 MHz では 0 %) がこの穴を露わにした。
 * 飽和は目的周波数の外で起きても、そこで生じた歪みが 1090 MHz に落ちてくる。
 */
private fun analyzeBandClipping(bands: List<BandResult>): List<Finding> {
    val worst = bands.maxByOrNull { it.metrics.clipRate } ?: return emptyList()
    if (worst.metrics.clipRate <= ClipRateWarning) return emptyList()

    val severity =
        if (worst.metrics.clipRate > ClipRateCritical) Severity.CRITICAL else Severity.WARNING
    val clipped = bands.filter { it.metrics.clipRate > ClipRateWarning }
    val others = clipped.filter { it !== worst }
    val alsoText = if (others.isEmpty()) "" else
        "同様に " + others.joinToString("、") { it.target.label } + " も飽和しています。"

    return listOf(
        Finding(
            severity,
            "帯域外の信号で ADC が振り切れている: ${worst.target.label}",
            "${"%.3f".format(worst.target.frequencyHz / 1e6)} MHz を受信中、全サンプルの " +
                "${"%.1f".format(worst.metrics.clipRate * 100)} % が 8 bit ADC の上下限に" +
                "貼り付いています。${alsoText}" +
                "1090 MHz に同調している間はクリップが出なくても、入力段はこれらの信号を" +
                "同時に浴びており、そこで生じた歪みが 1090 MHz に落ちてきます。" +
                "利得を下げても目的信号が一緒に小さくなるだけで解決しません。" +
                "アンテナ直後 (LNA より前) の 1090 MHz SAW バンドパスフィルタが唯一の対策です。",
        )
    )
}

/**
 * 空きチャンネルの「平均は静かなのにピークだけ強い」パターンはパルス性の
 * 干渉源を示す。1090 MHz 近傍でその代表格は DME (962〜1213 MHz のパルス
 * ペア) で、空港のそばでは避けられない。羽田での実測 (対照 1080 MHz が
 * 平均 -47 dBFS / ピーク -4.8 dBFS) がこの判定を追加した動機。
 *
 * 連続波の飽和と違い利得調整では消えず、プリアンブル検出器が誤反応して
 * 候補数だけが膨らむ、という形で復調品質に効く。
 */
private fun analyzePulseInterference(bands: List<BandResult>): List<Finding> {
    val pulsed = bands
        .filter { it.target.role == BandRole.QUIET_REFERENCE }
        .filter {
            it.metrics.peakLevelDbfs - it.metrics.meanLevelDbfs >= PulsePeakExcessDb &&
                it.metrics.peakLevelDbfs >= PulsePeakFloorDbfs
        }
    val worst = pulsed.maxByOrNull { it.metrics.peakLevelDbfs } ?: return emptyList()

    return listOf(
        Finding(
            Severity.WARNING,
            "空きチャンネルに強力なパルス性信号 (DME の可能性)",
            "${worst.target.label} の平均は ${"%.1f".format(worst.metrics.meanLevelDbfs)} dBFS と" +
                "静かなのに、ピークは ${"%.1f".format(worst.metrics.peakLevelDbfs)} dBFS に達しています。" +
                "連続波ではなく強いパルスが飛んでいる形で、この周波数帯では空港の DME " +
                "(距離測定装置) が典型です。プリアンブル検出器がパルスに誤反応して候補数が" +
                "膨らみ、CRC 通過率を押し下げます。空港から離れれば自然に消える性質のものです。",
        )
    )
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

/** 空きチャンネルでピークが平均をこれだけ上回ったらパルス性とみなす。 */
private const val PulsePeakExcessDb = 30.0

/** 弱いパルスまで騒がないよう、ピーク自体にも下限を置く。 */
private const val PulsePeakFloorDbfs = -20.0

/** 「最小利得」とみなす上限 (0.1 dB 単位)。 */
private const val MinimumGainTenthsDb = 100

/** 利得ほぼ 0 dB でこのレベルを超えるのは、通常環境ではあり得ない。 */
private const val MinimumGainHotLevelDbfs = -35.0

private const val ClipRateWarning = 0.001
private const val ClipRateCritical = 0.02

/** 最大利得での受信数が最良点のこの割合を下回ったら「崩れている」とみなす。 */
private const val CollapseRatio = 0.5
