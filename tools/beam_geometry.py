#!/usr/bin/env python3
"""干渉源から見た俯角を計算し、送信アンテナの垂直パターンの影響を切り分ける。

使い方:
    python3 tools/beam_geometry.py docs/field-tests/2026-09-01/*.json

なぜ必要か:
    ch25 の受信レベルを「送信所からの距離」だけで説明しようとすると破綻する。
    実測では 1.05 km の1.05km地点が +8.6 dB、3.96 km の3.96km地点が +13.3 dB と、
    遠いほうが強かった。放送用送信アンテナは電波を水平方向に集中して放射する
    ため、真下に近い場所は主ビームの外に落ちる。効いているのは距離ではなく、
    送信アンテナから受信点を見下ろす角度（俯角）である。

    自由空間損失を足し戻した「距離補正後レベル」は、その方向へどれだけ
    放射されているかの目安になり、垂直パターンの形が数字として現れる。

注意:
    絶対値には意味がない（dBFS が較正されていないうえ、対照チャンネル基準の
    相対値をさらに加工している）。読むべきは俯角に対する並びだけ。屋内計測は
    建物やガラスの減衰を含むため、その分だけ低く出る。
"""

from __future__ import annotations

import argparse
import json
import math
import pathlib
import sys

# 東京スカイツリー。送信アンテナは地上 497〜634 m に分布するので、
# 代表値として 600 m を採る。数十 m の違いは俯角の並びを変えない。
DEFAULT_TX_LAT = 35.7101
DEFAULT_TX_LON = 139.8107
DEFAULT_TX_HEIGHT_M = 600.0


def great_circle_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    radius = 6371.0088
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lon2 - lon1)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * radius * math.asin(math.sqrt(min(1.0, h)))


def analyse(path: pathlib.Path, tx: tuple[float, float, float]) -> dict | None:
    data = json.loads(path.read_text())
    diagnostics = data.get("rf_diagnostics")
    if not diagnostics:
        return None

    bands = diagnostics["bands"]
    quiet = [b["mean_dbfs"] for b in bands if b.get("role") == "QUIET_REFERENCE"]
    harmonic = next((b for b in bands if b.get("harmonic_of_interest_hz")), None)
    if not quiet or harmonic is None:
        return None

    receiver = data["receiver"]
    horizontal_km = great_circle_km(
        tx[0], tx[1], receiver["latitude"], receiver["longitude"]
    )
    rise_m = tx[2] - receiver.get("height_m", 0.0)
    slant_km = math.hypot(horizontal_km * 1000, rise_m) / 1000

    # 真下に近づくほど俯角は 90° に寄る。水平距離がごく小さいと数値が暴れるので、
    # そのときは真下 (90°) として扱う。
    depression = (
        90.0 if horizontal_km < 0.01
        else math.degrees(math.atan2(rise_m, horizontal_km * 1000))
    )

    level = harmonic["mean_dbfs"] - min(quiet)
    clipped = harmonic.get("clip_rate", 0.0) > 0.001

    return {
        "name": path.stem,
        "horizontal_km": horizontal_km,
        "slant_km": slant_km,
        "depression_deg": depression,
        "level_db": level,
        # 自由空間損失は距離の 2 乗に比例するので 20log10 を足し戻す。
        "normalised_db": level + 20 * math.log10(max(slant_km, 0.001)),
        "clipped": clipped,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("logs", nargs="+", type=pathlib.Path)
    parser.add_argument("--tx-lat", type=float, default=DEFAULT_TX_LAT)
    parser.add_argument("--tx-lon", type=float, default=DEFAULT_TX_LON)
    parser.add_argument("--tx-height", type=float, default=DEFAULT_TX_HEIGHT_M)
    args = parser.parse_args()

    tx = (args.tx_lat, args.tx_lon, args.tx_height)
    rows = [r for r in (analyse(p, tx) for p in args.logs) if r]
    if not rows:
        print("解析できる計測がありません", file=sys.stderr)
        return 1

    rows.sort(key=lambda r: -r["depression_deg"])
    header = f"{'計測':<52}{'水平km':>8}{'斜距離':>8}{'俯角°':>8}{'ch25':>9}{'距離補正後':>11}"
    print(header)
    print("-" * len(header))
    for row in rows:
        flag = " *飽和" if row["clipped"] else ""
        print(
            f"{row['name']:<52}{row['horizontal_km']:>8.2f}{row['slant_km']:>8.2f}"
            f"{row['depression_deg']:>8.1f}{row['level_db']:>+9.1f}"
            f"{row['normalised_db']:>+11.1f}{flag}"
        )
    print("\n* 飽和した計測は測定値が頭打ちで、真の値はこれより高い。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
