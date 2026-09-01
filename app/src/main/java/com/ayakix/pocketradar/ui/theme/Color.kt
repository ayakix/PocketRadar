package com.ayakix.pocketradar.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 航空レーダー風の固定パレット。
 * 管制卓のディスプレイを意識して、ダークは青黒の背景にシアン（走査線・航跡）、
 * アンバー（警告・アクセント）を組み合わせる。壁紙由来の Dynamic Color だと
 * 地図マーカーや航跡の色が端末ごとに変わり「レーダー画面」らしい統一感が
 * 崩れるため、あえて固定色にしている。
 */

// 共通アクセント
val RadarCyan = Color(0xFF35C9DC)        // 走査線・航跡・プライマリ
val RadarCyanDim = Color(0xFF0E4E58)     // プライマリコンテナ（ダーク）
val RadarAmber = Color(0xFFFFB74D)       // セカンダリ（強調・警告寄り）
val RadarAmberDim = Color(0xFF5C4218)

// ダーク面
val NightBackground = Color(0xFF0A1118)  // 青みがかったほぼ黒
val NightSurface = Color(0xFF101B24)
val NightSurfaceHigh = Color(0xFF16242F) // カード・シートの持ち上げ面
val NightOutline = Color(0xFF3A4F5C)
val NightOnSurface = Color(0xFFDCE7ED)
val NightOnSurfaceVariant = Color(0xFF8FA5B1)

// ライト面（同系統の色相で統一）
val DayPrimary = Color(0xFF006878)
val DayPrimaryContainer = Color(0xFFA9EDFF)
val DaySecondary = Color(0xFF8B5000)
val DaySecondaryContainer = Color(0xFFFFDCBE)
val DayBackground = Color(0xFFF5FAFC)
val DaySurface = Color(0xFFF5FAFC)
val DaySurfaceHigh = Color(0xFFE9F1F5)
val DayOutline = Color(0xFF70828B)
val DayOnSurface = Color(0xFF171C1F)
val DayOnSurfaceVariant = Color(0xFF40484D)
