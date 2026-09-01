#!/usr/bin/env python3
"""デバッグエクスポートの帯域スキャン結果を1枚のスペクトラム図にする。

使い方:
    python3 tools/spectrum_chart.py private/docs/field-tests/2026-09-01/*.json -o spectrum.html

出力は単体で開ける HTML（外部依存なし）。入力ごとに1系列を描き、
横軸=周波数（昇順の9帯域）、縦軸=空きチャンネル基準の相対レベル [dB] を取る。

なぜ絶対 dBFS ではなく相対値か:
    RTL-SDR の dBFS は較正されていないため、装置の絶対感度としては読めない。
    一方「空きチャンネル (1080/1100 MHz) より何 dB 高いか」は同一利得・
    同一計測内の比較なので意味を持ち、地点間でも比較できる。

注意: 帯域スキャンは9点の離散サンプルであって掃引スペクトラムではない。
連続な線で結ぶと測っていない周波数を測ったように見えるため、棒で描く。
"""

from __future__ import annotations

import argparse
import html
import json
import math
import pathlib
import sys

# dataviz スキルの検証済みカテゴリカル配色（明/暗の対応する段）。
# 帯域スキャンはグループ棒グラフ＝隣接ペア判定が適用でき、この 4 スロットは
# 明暗とも隣接ペアの CVD・通常視の閾値を満たすことを検証済み
# （最悪 CVD ΔE 9.1 明 / 8.4 暗、通常視 22.9 / 19.8）。
# 5 つ目を足すと黄と橙が同時に出て全ペア判定を割るため、ここが上限。
SERIES_COLORS = [
    ("#2a78d6", "#3987e5"),  # blue
    ("#eb6834", "#d95926"),  # orange
    ("#1baf7a", "#199e70"),  # aqua
    ("#eda100", "#c98500"),  # yellow
]

# 描画領域（ユーザー座標）。CSS ではなく viewBox 内の単位。
PLOT_W, PLOT_H = 1000, 420

# これを超えたら「棒の値は頭打ち」とみなす。0.1 % でも 8 bit ADC では
# 波形が崩れ始めるので、注意喚起としては十分に低く取る。
CLIP_THRESHOLD = 0.001
MARGIN = {"top": 52, "right": 20, "bottom": 56, "left": 56}


def load_measurement(path: pathlib.Path) -> dict:
    """1つのエクスポートから、描画に必要な最小限を取り出す。"""
    data = json.loads(path.read_text())
    diagnostics = data.get("rf_diagnostics")
    if not diagnostics:
        raise ValueError(f"{path.name}: rf_diagnostics がありません（診断未実行）")

    bands = diagnostics["bands"]
    quiet = [b for b in bands if b.get("role") == "QUIET_REFERENCE"]
    if not quiet:
        raise ValueError(f"{path.name}: 対照チャンネルがありません")
    reference = min(b["mean_dbfs"] for b in quiet)

    points = [
        {
            "hz": b["frequency_hz"],
            "label": b["label"],
            "role": b.get("role", "SURVEY"),
            "mean": b["mean_dbfs"] - reference,
            "peak": b["peak_dbfs"] - reference,
            "harmonic_hz": b.get("harmonic_of_interest_hz"),
            "clip_rate": b.get("clip_rate", 0.0),
        }
        for b in bands
    ]
    points.sort(key=lambda p: p["hz"])

    # 用量反応図に使う 2 つの量。干渉の代表値は 2 倍波が 1090 MHz に落ちる
    # 帯域（ch25）、受信性能はゲインスイープ中の最良 CRC 通過レートを取る。
    harmonic = next((p for p in points if p.get("harmonic_hz")), None)
    sweep = diagnostics.get("gain_sweep", [])
    best_rate = max(
        (g["crc_valid_frames"] * 1000.0 / g["window_ms"]
         for g in sweep if g.get("window_ms")),
        default=0.0,
    )

    return {
        "name": derive_name(path, data),
        "points": points,
        "receiver": data.get("receiver", {}),
        "exported_at": data.get("exported_at", ""),
        "interference_db": harmonic["mean"] if harmonic else None,
        "frames_per_second": best_rate,
        "confounded": False,
    }


def derive_name(path: pathlib.Path, data: dict) -> str:
    """ファイル名から人間が読める系列名を作る。

    リネーム規約 `日付-時刻_地点_内容.json` に沿っている前提で、
    地点と時刻を拾う。合わない名前ならファイル名をそのまま使う。
    """
    sites = {
        "haneda-t1": "羽田 T1",
        "tokyotower-maindeck": "東京タワー 150m",
        "skytree-base": "スカイツリー直下",
        "site-b": "1.05km地点",
        "site-d": "地点D",
    }
    details = {
        "diag-skytree-side": "スカイツリー側",
        "diag-fm-side": "FM 側",
        "diag-short-window": "短窓",
    }
    stem = path.stem
    parts = stem.split("_")
    if len(parts) >= 2 and "-" in parts[0]:
        time_part = parts[0].split("-")[-1]
        clock = f"{time_part[:2]}:{time_part[2:]}" if len(time_part) == 4 else time_part
        site = sites.get(parts[1], parts[1].replace("-", " "))
        detail = details.get("-".join(parts[2:]), "")
        return f"{site} {clock}" + (f" · {detail}" if detail else "")
    return stem


def build_svg(measurements: list[dict]) -> str:
    """周波数の昇順に並べたグループ棒グラフを描く。

    対数周波数軸も試したが、UHF 4局が 491〜557 MHz の狭い範囲に固まって
    判読できなかった。帯域スキャンはそもそも9点の離散サンプルなので、
    「周波数順に並んだカテゴリ」として扱うほうがデータの実態に忠実で、
    かつ読みやすい。横軸のラベルには実周波数を併記して順序を保証する。
    """
    points_by_band = measurements[0]["points"]
    band_count = len(points_by_band)
    series_count = len(measurements)

    all_values = [p["peak"] for m in measurements for p in m["points"]]
    y_max = max(5.0, math.ceil((max(all_values) + 4) / 5) * 5)
    y_min = min(0.0, math.floor((min(all_values) - 2) / 5) * 5)

    def y(value: float) -> float:
        return PLOT_H - (value - y_min) / (y_max - y_min) * PLOT_H

    group_w = PLOT_W / band_count
    # 棒どうしは 2px の地色の隙間で分ける（隣接塗りの分離ルール）。
    bar_w = min(22.0, (group_w - 26) / series_count - 2)

    parts: list[str] = []

    # --- 目盛り（背景に退かせる） ---
    tick = math.ceil(y_min / 10) * 10
    while tick <= y_max:
        yy = y(tick)
        cls = "zero" if tick == 0 else "hgrid"
        parts.append(f'<line class="{cls}" x1="0" y1="{yy:.1f}" x2="{PLOT_W}" y2="{yy:.1f}"/>')
        parts.append(f'<text class="tick y" x="-10" y="{yy + 4:.1f}">{tick:+.0f}</text>')
        tick += 10

    # --- 注目する2帯域を背景で強調し、2倍波の関係を矢印で結ぶ ---
    harmonic_index = next(
        (i for i, p in enumerate(points_by_band) if p.get("harmonic_hz")), None
    )
    adsb_index = next(
        (i for i, p in enumerate(points_by_band) if p["role"] == "ADSB"), None
    )
    for index in (harmonic_index, adsb_index):
        if index is None:
            continue
        parts.append(
            f'<rect class="highlight" x="{index * group_w + 3:.1f}" y="{-6}" '
            f'width="{group_w - 6:.1f}" height="{PLOT_H + 6}"/>'
        )
    if harmonic_index is not None and adsb_index is not None:
        x1 = harmonic_index * group_w + group_w / 2
        x2 = adsb_index * group_w + group_w / 2
        arc_y = -14
        parts.append(
            f'<path class="harmonic" d="M {x1:.1f} {arc_y} '
            f'C {x1 + (x2 - x1) * 0.3:.1f} {arc_y - 16}, '
            f'{x1 + (x2 - x1) * 0.7:.1f} {arc_y - 16}, {x2:.1f} {arc_y}" '
            f'marker-end="url(#arrow)"/>'
        )
        parts.append(
            f'<text class="harmonic-label" x="{(x1 + x2) / 2:.1f}" y="{arc_y - 20}" '
            f'text-anchor="middle">2倍波が ADS-B に落ちる</text>'
        )

    # --- 棒（平均）とピークマーカー ---
    for band_index, band in enumerate(points_by_band):
        group_x = band_index * group_w
        total_w = bar_w * series_count + 2 * (series_count - 1)
        first_x = group_x + (group_w - total_w) / 2

        for series_index, measurement in enumerate(measurements):
            point = measurement["points"][band_index]
            x = first_x + series_index * (bar_w + 2)
            top = y(max(point["mean"], 0))
            height = abs(y(point["mean"]) - y(0))
            clipped = point["clip_rate"] > CLIP_THRESHOLD
            title = (
                f'{measurement["name"]} — {point["label"]}\n'
                f'{point["hz"] / 1e6:.3f} MHz\n'
                f'平均 {point["mean"]:+.1f} dB / ピーク {point["peak"]:+.1f} dB'
                + (f'\nADC クリップ {point["clip_rate"] * 100:.1f}% — 実際はこれ以上に強い'
                   if clipped else "")
            )
            parts.append(f'<g class="series s{series_index}"><title>{html.escape(title)}</title>')
            parts.append(
                f'<rect class="bar" x="{x:.1f}" y="{top:.1f}" width="{bar_w:.1f}" '
                f'height="{max(height, 1.5):.1f}" rx="3"/>'
            )
            if clipped:
                # 飽和した棒は測定値が頭打ちで、真の強度はこれより上にある。
                # 斜線を重ねて「この値は下限でしかない」ことを形でも示す。
                parts.append(
                    f'<rect class="clip-hatch" x="{x:.1f}" y="{top:.1f}" '
                    f'width="{bar_w:.1f}" height="{max(height, 1.5):.1f}" rx="3"/>'
                )
                parts.append(
                    f'<text class="clip-mark" x="{x + bar_w / 2:.1f}" y="{top - 5:.1f}" '
                    f'text-anchor="middle">▲</text>'
                )
            # ピークは平均の上に開いた横棒で置く。平均との差が大きいほど
            # パルス性が強い（DME の発見はこの差分から出た）。
            peak_y = y(point["peak"])
            parts.append(
                f'<line class="peak" x1="{x:.1f}" y1="{peak_y:.1f}" '
                f'x2="{x + bar_w:.1f}" y2="{peak_y:.1f}"/>'
            )
            parts.append(
                f'<line class="peak-link" x1="{x + bar_w / 2:.1f}" y1="{peak_y:.1f}" '
                f'x2="{x + bar_w / 2:.1f}" y2="{top:.1f}"/>'
            )
            parts.append("</g>")

        # --- 帯域ラベル（2行: 名称 + 周波数） ---
        cx = group_x + group_w / 2
        emphasis = " strong" if band_index in (harmonic_index, adsb_index) else ""
        parts.append(
            f'<text class="band-label{emphasis}" x="{cx:.1f}" y="{PLOT_H + 18}" '
            f'text-anchor="middle">{html.escape(short_label(band["label"]))}</text>'
        )
        parts.append(
            f'<text class="band-freq" x="{cx:.1f}" y="{PLOT_H + 34}" '
            f'text-anchor="middle">{band["hz"] / 1e6:.0f} MHz</text>'
        )

    body = "\n".join(parts)
    return f"""<svg viewBox="{-MARGIN['left']} {-MARGIN['top']} {PLOT_W + MARGIN['left'] + MARGIN['right']} {PLOT_H + MARGIN['top'] + MARGIN['bottom']}" role="img">
  <defs>
    <marker id="arrow" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="6" markerHeight="6" orient="auto">
      <path d="M0,0 L8,4 L0,8 z"/>
    </marker>
    <pattern id="clip" width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
      <line x1="0" y1="0" x2="0" y2="6" stroke-width="2.5"/>
    </pattern>
  </defs>
{body}
</svg>"""


def short_label(label: str) -> str:
    """軸ラベル用に帯域名を詰める。周波数は別行に出すので識別子だけ残す。"""
    replacements = {
        "FM 放送 (80 MHz)": "FM",
        "UHF ch16 TOKYO MX": "ch16 MX",
        "UHF ch21 フジテレビ": "ch21 フジ",
        "UHF ch25 日本テレビ": "ch25 日テレ",
        "UHF ch27 NHK 総合": "ch27 NHK",
        "携帯 800 MHz 帯": "携帯800",
        "対照 1080 MHz": "対照",
        "ADS-B 1090 MHz": "ADS-B",
        "対照 1100 MHz": "対照",
    }
    return replacements.get(label, label)


def build_response_svg(measurements: list[dict]) -> str:
    """干渉レベル（横）と受信性能（縦）の関係を散布図で描く。

    点が少ないので回帰線は引かない。4 点で直線を引くと、実際には測っていない
    中間領域まで関係が滑らかだと主張することになる。点と直接ラベルだけ置き、
    読み手に形を判断させる。
    """
    usable = [m for m in measurements if m["interference_db"] is not None]
    if len(usable) < 2:
        return ""

    w, h = 640, 300
    x_min = min(0.0, min(m["interference_db"] for m in usable) - 3)
    x_max = max(m["interference_db"] for m in usable) + 6
    y_max = max(1.0, max(m["frames_per_second"] for m in usable) * 1.35)

    def px(value: float) -> float:
        return (value - x_min) / (x_max - x_min) * w

    def py(value: float) -> float:
        return h - value / y_max * h

    parts: list[str] = []

    for step in range(5):
        value = y_max * step / 4
        yy = py(value)
        parts.append(f'<line class="hgrid" x1="0" y1="{yy:.1f}" x2="{w}" y2="{yy:.1f}"/>')
        parts.append(f'<text class="tick y" x="-10" y="{yy + 4:.1f}">{value:.1f}</text>')

    tick = math.ceil(x_min / 10) * 10
    while tick <= x_max:
        xx = px(tick)
        parts.append(f'<text class="tick x" x="{xx:.1f}" y="{h + 20:.1f}">{tick:+.0f}</text>')
        tick += 10

    ordered = sorted(usable, key=lambda m: m["interference_db"])
    placed: list[tuple[float, float, float]] = []
    for measurement in ordered:
        cx, cy = px(measurement["interference_db"]), py(measurement["frames_per_second"])
        marker = "point-confounded" if measurement["confounded"] else "point"
        title = (
            f'{measurement["name"]}\n'
            f'ch25 干渉 {measurement["interference_db"]:+.1f} dB\n'
            f'{measurement["frames_per_second"]:.1f} フレーム/秒'
        )
        parts.append(f'<g class="response"><title>{html.escape(title)}</title>')
        parts.append(f'<circle class="{marker}" cx="{cx:.1f}" cy="{cy:.1f}" r="6"/>')

        anchor = "end" if cx > w * 0.72 else "start"
        dx = -12 if anchor == "end" else 12

        # 受信ゼロの点は軸上に並ぶのでラベルが必ず衝突する。ラベルの占める
        # 矩形を実測（日本語 1 文字 ≒ 12px、英数 ≒ 6px）で見積もり、
        # 重なる限り上へ段をずらす。重なったラベルは読めず情報を失う。
        label_w = estimate_text_width(measurement["name"]) + 24
        left = cx + dx - (label_w if anchor == "end" else 0)
        label_y = cy - 14
        while any(
            abs(label_y - y) < 34 and left < x_right and left + label_w > x_left
            for x_left, x_right, y in placed
        ):
            label_y -= 34
        placed.append((left, left + label_w, label_y))
        parts.append(
            f'<line class="leader" x1="{cx:.1f}" y1="{cy:.1f}" '
            f'x2="{cx + dx * 0.35:.1f}" y2="{label_y + 5:.1f}"/>'
        )
        parts.append(
            f'<text class="point-label" x="{cx + dx:.1f}" y="{label_y:.1f}" '
            f'text-anchor="{anchor}">{html.escape(measurement["name"])}</text>'
        )
        parts.append(
            f'<text class="point-value" x="{cx + dx:.1f}" y="{label_y + 14:.1f}" '
            f'text-anchor="{anchor}">{measurement["frames_per_second"]:.1f} f/s'
            + ("（ガラス越し）" if measurement["confounded"] else "")
            + "</text>"
        )
        parts.append("</g>")

    body = "\n".join(parts)
    return f"""<svg viewBox="-56 -52 {w + 116} {h + 92}" role="img">
{body}
</svg>"""


def estimate_text_width(text: str) -> float:
    """SVG では文字幅を測れないので、全角/半角で概算する。"""
    return sum(12.0 if ord(ch) > 0x2E80 else 6.5 for ch in text)


def build_table(measurements: list[dict]) -> str:
    """同じ数値を表としても出す（色だけに頼らせないため）。"""
    labels = [p["label"] for p in measurements[0]["points"]]
    head = "".join(f"<th>{html.escape(m['name'])}</th>" for m in measurements)
    rows = []
    for i, label in enumerate(labels):
        hz = measurements[0]["points"][i]["hz"]
        cells = "".join(
            f'<td>{m["points"][i]["mean"]:+.1f}<span class="sub"> / {m["points"][i]["peak"]:+.1f}</span></td>'
            for m in measurements
        )
        rows.append(
            f'<tr><th scope="row">{html.escape(label)}'
            f'<span class="sub">{hz / 1e6:.1f} MHz</span></th>{cells}</tr>'
        )
    return f"""<table>
  <caption>平均 / ピーク、いずれも空きチャンネル基準の相対値 [dB]</caption>
  <thead><tr><th scope="col">帯域</th>{head}</tr></thead>
  <tbody>{"".join(rows)}</tbody>
</table>"""


def build_response_card(measurements: list[dict]) -> str:
    svg = build_response_svg(measurements)
    if not svg:
        return ""
    confounded = [m for m in measurements if m["confounded"]]
    caveat = (
        "<p class=\"note\">"
        + "、".join(html.escape(m["name"]) for m in confounded)
        + " は屋内で Low-E ガラス越しのため、受信できない理由が干渉だけではない。"
        + "白抜きの点で区別している。</p>"
        if confounded else ""
    )
    return f"""<div class="card">
    <h2>干渉レベルと受信性能</h2>
    <p class="lede">横軸は 2 倍波が ADS-B に落ちる ch25 のレベル、縦軸はゲインスイープ中に
    達成できた最良の CRC 通過レート。点が少ないので回帰線は引いていない。</p>
    <div class="scroller">{svg}</div>
    <p class="axis-title">横軸: ch25 干渉レベル [dB]（対照基準）・ 縦軸: CRC 通過 [フレーム/秒]</p>
    {caveat}
  </div>"""


def build_html(measurements: list[dict]) -> str:
    legend = "".join(
        f'<span class="legend-item s{i}">'
        f'<span class="swatch"></span>{html.escape(m["name"])}</span>'
        for i, m in enumerate(measurements[: len(SERIES_COLORS)])
    )
    color_vars_light = "\n".join(
        f"  --series-{i}: {SERIES_COLORS[i][0]};" for i in range(len(SERIES_COLORS))
    )
    color_vars_dark = "\n".join(
        f"  --series-{i}: {SERIES_COLORS[i][1]};" for i in range(len(SERIES_COLORS))
    )
    return f"""<!doctype html>
<html lang="ja">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>1090 MHz 帯域スキャン比較</title>
<style>
:root {{
  color-scheme: light;
  --surface: #fcfcfb;
  --panel: #ffffff;
  --text-primary: #0b0b0b;
  --text-secondary: #52514e;
  --text-muted: #7c7a75;
  --grid: #e3e2df;
  --band: #f0eeea;
{color_vars_light}
}}
@media (prefers-color-scheme: dark) {{
  :root:not([data-theme="light"]) {{
    color-scheme: dark;
    --surface: #141413;
    --panel: #1a1a19;
    --text-primary: #ffffff;
    --text-secondary: #c3c2b7;
    --text-muted: #8f8e86;
    --grid: #2f2f2d;
    --band: #24241f;
{color_vars_dark}
  }}
}}
:root[data-theme="dark"] {{
  color-scheme: dark;
  --surface: #141413;
  --panel: #1a1a19;
  --text-primary: #ffffff;
  --text-secondary: #c3c2b7;
  --text-muted: #8f8e86;
  --grid: #2f2f2d;
  --band: #24241f;
{color_vars_dark}
}}
* {{ box-sizing: border-box; }}
body {{
  margin: 0; padding: 32px 20px 56px;
  background: var(--surface); color: var(--text-primary);
  font-family: -apple-system, BlinkMacSystemFont, "Hiragino Sans", "Noto Sans JP", sans-serif;
  line-height: 1.6;
}}
main {{ max-width: 1100px; margin: 0 auto; }}
h1 {{ font-size: 1.5rem; margin: 0 0 4px; letter-spacing: -0.01em; }}
.lede {{ color: var(--text-secondary); margin: 0 0 24px; max-width: 68ch; }}
.lede strong {{ color: var(--text-primary); font-weight: 600; }}
.card {{
  background: var(--panel); border: 1px solid var(--grid);
  border-radius: 12px; padding: 20px; margin-bottom: 24px;
}}
.legend {{ display: flex; flex-wrap: wrap; gap: 16px; margin-bottom: 12px; }}
.legend-item {{ display: inline-flex; align-items: center; gap: 7px; font-size: .84rem; color: var(--text-secondary); }}
.swatch {{ width: 11px; height: 11px; border-radius: 50%; background: var(--c); }}
.legend-item.s0 {{ --c: var(--series-0); }}
.legend-item.s1 {{ --c: var(--series-1); }}
.legend-item.s2 {{ --c: var(--series-2); }}
.legend-item.s3 {{ --c: var(--series-3); }}
.scroller {{ overflow-x: auto; }}
svg {{ display: block; width: 100%; min-width: 720px; height: auto; overflow: visible; }}
svg line.hgrid {{ stroke: var(--grid); stroke-width: 1; }}
svg line.zero {{ stroke: var(--text-muted); stroke-width: 1.5; }}
svg text {{ fill: var(--text-secondary); font-size: 12px; }}
svg text.tick.y {{ text-anchor: end; fill: var(--text-muted); font-size: 11px; }}
svg text.band-label {{ fill: var(--text-secondary); font-size: 12px; }}
svg text.band-label.strong {{ fill: var(--text-primary); font-weight: 600; }}
svg text.band-freq {{ fill: var(--text-muted); font-size: 10.5px; }}
svg text.harmonic-label {{ fill: var(--text-muted); font-size: 11px; }}
svg rect.highlight {{ fill: var(--band); }}
svg path.harmonic {{ fill: none; stroke: var(--text-muted); stroke-width: 1.5; stroke-dasharray: 4 3; }}
svg marker path {{ fill: var(--text-muted); }}
.series rect.bar {{ fill: var(--c); }}
svg pattern#clip line {{ stroke: var(--surface); opacity: .55; }}
.series rect.clip-hatch {{ fill: url(#clip); }}
.series text.clip-mark {{ fill: var(--c); font-size: 9px; }}
.series line.peak {{ stroke: var(--c); stroke-width: 2; }}
.series line.peak-link {{ stroke: var(--c); stroke-width: 1; stroke-dasharray: 2 2; opacity: .5; }}
.series:hover rect.bar {{ opacity: .78; }}
.series.s0 {{ --c: var(--series-0); }}
.series.s1 {{ --c: var(--series-1); }}
.series.s2 {{ --c: var(--series-2); }}
.series.s3 {{ --c: var(--series-3); }}
.axis-title {{ font-size: .8rem; color: var(--text-muted); margin-top: 8px; }}
h2 {{ font-size: 1.05rem; margin: 0 0 6px; }}
svg text.tick.x {{ text-anchor: middle; fill: var(--text-muted); font-size: 11px; }}
.response circle.point {{ fill: var(--series-0); }}
.response circle.point-confounded {{ fill: var(--panel); stroke: var(--series-0); stroke-width: 2; }}
.response text.point-label {{ fill: var(--text-primary); font-size: 12px; font-weight: 600; }}
.response text.point-value {{ fill: var(--text-secondary); font-size: 11px; }}
.response line.leader {{ stroke: var(--text-muted); stroke-width: 1; opacity: .45; }}
table {{ border-collapse: collapse; width: 100%; font-size: .84rem; }}
caption {{ text-align: left; color: var(--text-muted); font-size: .8rem; padding-bottom: 10px; }}
th, td {{ text-align: right; padding: 7px 10px; border-bottom: 1px solid var(--grid); font-variant-numeric: tabular-nums; }}
thead th {{ color: var(--text-secondary); font-weight: 600; }}
tbody th {{ text-align: left; font-weight: 500; }}
.sub {{ color: var(--text-muted); font-size: .78em; margin-left: 6px; }}
.note {{ color: var(--text-secondary); font-size: .86rem; }}
.note strong {{ color: var(--text-primary); }}
</style>
</head>
<body>
<main>
  <h1>1090 MHz 周辺の帯域スキャン比較</h1>
  <p class="lede">縦軸は空きチャンネル (1080 / 1100 MHz) を 0 dB とした相対レベル。
  棒が平均レベル、その上の横線がピーク。<strong>棒と横線の差が大きいほどパルス性の信号</strong>で、
  1080 MHz の突出は空港 DME による。斜線と ▲ の付いた棒は <strong>ADC が振り切れており、
  実際の強度はその値より上</strong>。横軸は周波数の昇順。棒にカーソルを合わせると数値が出る。</p>

  <div class="card">
    <div class="legend">{legend}</div>
    <div class="scroller">{build_svg(measurements[:len(SERIES_COLORS)])}</div>
    <p class="axis-title">横軸: 周波数（昇順）・ 縦軸: 空きチャンネル基準の相対レベル [dB]</p>
  </div>

  {build_response_card(measurements)}

  <div class="card">{build_table(measurements[:len(SERIES_COLORS)])}</div>

  <p class="note">帯域スキャンは <strong>9 点の離散サンプル</strong>であり掃引スペクトラムではない。
  測っていない周波数を測ったように見せないため、点を線で結んでいない。
  dBFS の絶対値は較正されていないので、比較は常に同一計測内の対照チャンネル基準で行う。</p>
</main>
</body>
</html>"""


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("logs", nargs="+", type=pathlib.Path)
    parser.add_argument("-o", "--output", type=pathlib.Path, required=True)
    parser.add_argument(
        "--confounded",
        action="append",
        default=[],
        metavar="SUBSTRING",
        help="受信できない理由が干渉以外にもある計測（例: ガラス越しの屋内）。"
             "系列名に含まれる文字列で指定し、散布図で白抜きにする。",
    )
    args = parser.parse_args()

    measurements = []
    for path in args.logs:
        try:
            measurements.append(load_measurement(path))
        except (ValueError, KeyError) as error:
            print(f"skip: {error}", file=sys.stderr)

    if not measurements:
        print("描画できる計測がありません", file=sys.stderr)
        return 1
    if len(measurements) > len(SERIES_COLORS):
        print(
            f"注意: 帯域スキャン図は先頭 {len(SERIES_COLORS)} 件のみ描画します"
            f"（色数を超えると識別性が保証できないため）。"
            f"散布図には全 {len(measurements)} 件を使います。",
            file=sys.stderr,
        )

    for pattern in args.confounded:
        for measurement in measurements:
            if pattern in measurement["name"]:
                measurement["confounded"] = True

    args.output.write_text(build_html(measurements))
    print(f"wrote {args.output} ({len(measurements)} series)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
