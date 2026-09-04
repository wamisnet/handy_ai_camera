#!/usr/bin/env python3
"""
アプリの検査ログ(files/logs/verify_log.csv)を読み、手法ごとの本人/他製品スコア分布と
推奨しきい値を出力する。feedback.csv(結果画面の「正しい/誤り」ボタン)があれば、
誤判定になった行を突き合わせて表示する。

使い方:
  python analyze_log.py verify_log.csv [feedback.csv]
"""
import csv
import sys
from collections import defaultdict

METHODS = ["SHAPE", "FEATURE", "HOG", "NCC", "EMBOSS", "HOLES", "EMBEDDING"]


def fnum(s):
    try:
        return float(s)
    except (TypeError, ValueError):
        return None


def suggest(genuine, impostor):
    if not genuine:
        return None
    if not impostor:
        return min(genuine) * 0.9
    gmin, imax = min(genuine), max(impostor)
    if gmin > imax:
        return (gmin + imax) / 2
    cands = sorted(genuine + impostor)
    best, best_err = cands[0], 1e9
    for t in cands:
        err = max(sum(1 for g in genuine if g < t), sum(1 for i in impostor if i >= t))
        if err < best_err:
            best, best_err = t, err
    return best


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    rows = list(csv.DictReader(open(sys.argv[1], encoding="utf-8")))
    rows = [r for r in rows if r.get("part")]
    print(f"検査行数: {len(rows)}  (製品: {sorted({r['product'] for r in rows})})")
    self_s, imp_s = defaultdict(list), defaultdict(list)
    for r in rows:
        for m in METHODS:
            a, b = fnum(r.get(f"{m}_self")), fnum(r.get(f"{m}_impostor"))
            if a is not None:
                self_s[m].append(a)
            if b is not None:
                imp_s[m].append(b)
    print(f"\n{'手法':10} {'本人min':>8} {'本人中央':>8} {'他者max':>8} {'他者中央':>8} {'余裕':>7} {'推奨thr':>8}")
    for m in METHODS:
        g, i = self_s[m], imp_s[m]
        if not g:
            continue
        g_sorted, i_sorted = sorted(g), sorted(i)
        gmed = g_sorted[len(g) // 2]
        imed = i_sorted[len(i) // 2] if i else float("nan")
        imax = max(i) if i else float("nan")
        gap = min(g) - imax if i else float("nan")
        thr = suggest(g, i)
        print(f"{m:10} {min(g):8.3f} {gmed:8.3f} {imax:8.3f} {imed:8.3f} {gap:7.3f} {thr:8.3f}")
    ok = sum(1 for r in rows if r["part_pass"] == "true")
    print(f"\nパーツ合格 {ok}/{len(rows)}")
    if len(sys.argv) > 2:
        fb = list(csv.DictReader(open(sys.argv[2], encoding="utf-8")))
        wrong = [f for f in fb if f["label"] == "confirmed_wrong"]
        print(f"\nオペレータが「誤り」と記録した判定: {len(wrong)} 件")
        for w in wrong:
            hit = [r for r in rows if r["product"] == w["product"] and abs(int(float(w["timestamp"])) // 1000 - 0) >= 0]
            print(f"  {w['timestamp']} {w['product']} pass={w['pass']}  (該当検査行 {len(hit)} 候補)")


if __name__ == "__main__":
    main()
