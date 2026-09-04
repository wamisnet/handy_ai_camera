#!/usr/bin/env python3
"""
アプリと同じ照合パイプラインの Python 版で、合成画像を使って手法の挙動を確かめるシミュレータ。

・「黒背景 + 押印文字入りのウレタン片」を複数製品ぶん合成し、
  距離(スケール)・回転・傾き(パース)・照明(ガンマ/影/ノイズ)をランダムに変えた検査画像を生成
・セグメンテーション → 正規化(canonical) → 各手法スコア を計算し、本人/他者の分布と分離余裕を表示

実機画像が無い段階で「どの手法がどの変動に強いか」を掴むためのもの。
  python simulate.py                 # 既定 (4 製品 × 12 試行)
  python simulate.py --trials 30 --out out/   # 画像も保存
"""
import argparse
import math
import os
import random

import cv2
import numpy as np

CANVAS = 384


# ---------------------------------------------------------------- 合成シーン
def make_foam(text, w=420, h=180, seed=0):
    """ウレタン片(明るいグレー、粗い表面) + 低コントラストの押印文字。"""
    rng = np.random.default_rng(seed)
    base = np.full((h, w), 175, np.float32)
    noise = cv2.GaussianBlur(rng.normal(0, 18, (h, w)).astype(np.float32), (0, 0), 2)
    base += noise
    # 押印: 文字を彫り込んだ陰影 (上側が暗く下側が明るい)
    stamp = np.zeros((h, w), np.uint8)
    cv2.putText(stamp, text, (30, h // 2 + 25), cv2.FONT_HERSHEY_SIMPLEX, 2.2, 255, 7, cv2.LINE_AA)
    sx = cv2.Sobel(stamp.astype(np.float32), cv2.CV_32F, 0, 1, ksize=3)
    base -= 0.11 * sx  # 縦方向の陰影のみ (斜め照明)
    base -= 0.10 * stamp  # 凹部はわずかに暗い
    mask = np.zeros((h, w), np.uint8)
    cv2.ellipse(mask, (w // 2, h // 2), (w // 2 - 4, h // 2 - 4), 0, 0, 360, 255, -1)
    cv2.rectangle(mask, (w // 2 - 60, 0), (w // 2 + 60, 40), 0, -1)  # 切り欠き(形状に個性)
    bgr = cv2.cvtColor(np.clip(base, 0, 255).astype(np.uint8), cv2.COLOR_GRAY2BGR)
    bgr[..., 0] = (bgr[..., 0] * 0.92).astype(np.uint8)  # 少し黄色寄り
    return bgr, mask


def render_scene(part_bgr, part_mask, scale, angle, tilt, gamma, shadow, noise, size=(720, 960), rng=None):
    """黒背景に部品を貼る。scale=距離差, angle=面内回転, tilt=パース, gamma/shadow/noise=照明。"""
    H, W = size
    ph, pw = part_mask.shape
    scene = np.full((H, W, 3), 12, np.uint8)  # 黒背景(完全な 0 ではない)
    smask = np.zeros((H, W), np.uint8)
    # 変換: 中心合わせ → 回転スケール → 軽いパース
    cx, cy = W / 2 + rng.uniform(-60, 60), H / 2 + rng.uniform(-60, 60)
    M = cv2.getRotationMatrix2D((pw / 2, ph / 2), angle, scale)
    M[0, 2] += cx - pw / 2
    M[1, 2] += cy - ph / 2
    Hm = np.vstack([M, [0, 0, 1]])
    P = np.eye(3)
    P[2, 0] = tilt / W
    P[2, 1] = tilt * 0.5 / H
    # パースを中心基準に
    T1 = np.array([[1, 0, -cx], [0, 1, -cy], [0, 0, 1]], float)
    T2 = np.array([[1, 0, cx], [0, 1, cy], [0, 0, 1]], float)
    Hm = T2 @ P @ T1 @ Hm
    warped = cv2.warpPerspective(part_bgr, Hm, (W, H), flags=cv2.INTER_LINEAR)
    wmask = cv2.warpPerspective(part_mask, Hm, (W, H), flags=cv2.INTER_NEAREST)
    scene[wmask > 0] = warped[wmask > 0]
    smask |= wmask
    # 照明: ガンマ + 片側からの影 + ノイズ
    f = scene.astype(np.float32) / 255.0
    grad = np.linspace(1.0, 1.0 - shadow, W, dtype=np.float32)[None, :, None]
    f = np.clip(f * grad, 0, 1) ** gamma
    f += rng.normal(0, noise, f.shape).astype(np.float32)
    return (np.clip(f, 0, 1) * 255).astype(np.uint8)


# ---------------------------------------------------------------- パイプライン(アプリ同等)
def segment_dark_bg(bgr):
    g = cv2.GaussianBlur(cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY), (5, 5), 0)
    _, b = cv2.threshold(g, 0, 255, cv2.THRESH_BINARY | cv2.THRESH_OTSU)
    k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5))
    b = cv2.morphologyEx(b, cv2.MORPH_OPEN, k)
    b = cv2.morphologyEx(b, cv2.MORPH_CLOSE, k)
    cnts, _ = cv2.findContours(b, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    cnts = [c for c in cnts if cv2.contourArea(c) > 0.01 * b.size]
    cnts.sort(key=cv2.contourArea, reverse=True)
    return b, cnts


def canonicalize(bgr, mask, cnt, canvas=CANVAS, fill=0.88):
    (cx, cy), (w, h), ang = cv2.minAreaRect(cnt)
    if w < h:
        ang += 90
        w, h = h, w
    s = canvas * fill / max(w, 1)
    M = cv2.getRotationMatrix2D((cx, cy), ang, s)
    M[0, 2] += canvas / 2 - cx
    M[1, 2] += canvas / 2 - cy
    out = cv2.warpAffine(bgr, M, (canvas, canvas), flags=cv2.INTER_LINEAR, borderValue=(0, 0, 0))
    om = np.zeros_like(mask)
    cv2.drawContours(om, [cnt], -1, 255, -1)
    om &= mask
    om = cv2.warpAffine(om, M, (canvas, canvas), flags=cv2.INTER_NEAREST, borderValue=0)
    out[om == 0] = 0
    return out, om


def variants(bgr, mask):
    return [(bgr, mask), (cv2.rotate(bgr, cv2.ROTATE_180), cv2.rotate(mask, cv2.ROTATE_180))]


def clahe(g):
    return cv2.createCLAHE(3.0, (8, 8)).apply(g)


def grad_mag(g, mask):
    gx = cv2.Scharr(g, cv2.CV_32F, 1, 0)
    gy = cv2.Scharr(g, cv2.CV_32F, 0, 1)
    m = cv2.magnitude(gx, gy)
    m = cv2.normalize(m, None, 0, 255, cv2.NORM_MINMAX, cv2.CV_8U, mask=mask)
    m[mask == 0] = 0
    return m


def masked_corr(a, b, ma, mb):
    m = (ma > 0) & (mb > 0)
    if m.sum() < 50:
        return 0.0
    x, y = a[m].astype(np.float64), b[m].astype(np.float64)
    x -= x.mean()
    y -= y.mean()
    d = math.sqrt((x * x).sum() * (y * y).sum())
    return float((x * y).sum() / d) if d > 1e-6 else 0.0


# --- 手法
def shape_score(mq, mt):
    cq = max(cv2.findContours(mq, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)[0], key=cv2.contourArea)
    ct = max(cv2.findContours(mt, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)[0], key=cv2.contourArea)
    d = cv2.matchShapes(cq, ct, cv2.CONTOURS_MATCH_I1, 0)

    def ar_sol(c):
        (_, (w, h), _) = cv2.minAreaRect(c)
        ar = max(w, h) / max(min(w, h), 1)
        hull = cv2.convexHull(c)
        sol = cv2.contourArea(c) / max(cv2.contourArea(hull), 1)
        ext = cv2.contourArea(c) / max(w * h, 1)
        return ar, sol, ext

    aq, sq, eq = ar_sol(cq)
    at, st, et = ar_sol(ct)
    s = 0.45 * math.exp(-4 * d) + 0.25 * (1 - min(1, abs(aq - at) / max(aq, at))) + 0.15 * (1 - min(1, abs(sq - st) * 2)) + 0.15 * (1 - min(1, abs(eq - et) * 2))
    return float(np.clip(s, 0, 1))


_orb = cv2.ORB_create(1200, 1.2, 8, 15, 0, 2, cv2.ORB_HARRIS_SCORE, 31, 10)


def feats(bgr, mask):
    g = clahe(cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY))
    m = cv2.erode(mask, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (7, 7)))
    kp, des = _orb.detectAndCompute(g, m)
    return kp, des


def feature_score(q, t):
    (kq, dq), (kt, dt) = q, t
    if dq is None or dt is None or len(kq) < 8 or len(kt) < 8:
        return 0.0
    bf = cv2.BFMatcher(cv2.NORM_HAMMING)
    good = []
    for m in bf.knnMatch(dq, dt, k=2):
        if len(m) == 2 and m[0].distance < 0.8 * m[1].distance:
            good.append(m[0])
    if len(good) < 8:
        return 0.0
    src = np.float32([kq[g.queryIdx].pt for g in good])
    dst = np.float32([kt[g.trainIdx].pt for g in good])
    Hm, inl = cv2.findHomography(src, dst, cv2.RANSAC, 5.0, maxIters=2000, confidence=0.995)
    if Hm is None:
        return 0.0
    det = Hm[0, 0] * Hm[1, 1] - Hm[0, 1] * Hm[1, 0]
    scale = math.sqrt(abs(det))
    if not (det > 0 and 0.5 <= scale <= 2.0 and max(abs(Hm[2, 0]), abs(Hm[2, 1])) < 0.004):
        return 0.0
    n = int(inl.sum())
    ratio = n / max(min(len(kq), len(kt)), 1)
    return float(np.clip(ratio * (0.5 + 0.5 * min(1, n / 25)), 0, 1))


_hog = cv2.HOGDescriptor((CANVAS, CANVAS), (48, 48), (24, 24), (24, 24), 9)


def hog(bgr, mask):
    g = clahe(cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY))
    g[mask == 0] = 0
    return _hog.compute(g).ravel()


def hog_score(a, b):
    d = np.linalg.norm(a) * np.linalg.norm(b)
    return float(np.clip(a @ b / d, 0, 1)) if d > 1e-9 else 0.0


def ecc_align(gq, gt, mq):
    s = 128 / CANVAS
    q = cv2.GaussianBlur(cv2.resize(gq, None, fx=s, fy=s, interpolation=cv2.INTER_AREA), (5, 5), 0)
    t = cv2.GaussianBlur(cv2.resize(gt, None, fx=s, fy=s, interpolation=cv2.INTER_AREA), (5, 5), 0)
    m = cv2.resize(mq, None, fx=s, fy=s, interpolation=cv2.INTER_NEAREST)
    warp = np.eye(2, 3, dtype=np.float32)
    try:
        _, warp = cv2.findTransformECC(t, q, warp, cv2.MOTION_AFFINE, (cv2.TERM_CRITERIA_COUNT | cv2.TERM_CRITERIA_EPS, 40, 1e-4), m, 5)
        warp[0, 2] /= s
        warp[1, 2] /= s
        det = warp[0, 0] * warp[1, 1] - warp[0, 1] * warp[1, 0]
        if not (0.5 < det < 2.0):
            warp = np.eye(2, 3, dtype=np.float32)
    except cv2.error:
        warp = np.eye(2, 3, dtype=np.float32)
    return warp


def inner_mask(mask):
    k = CANVAS // 32 * 2 + 1
    return cv2.erode(mask, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k)))


def roi_mask(roi):
    m = np.zeros((CANVAS, CANVAS), np.uint8)
    x, y, w, h = [int(v * CANVAS) for v in roi]
    m[y:y + h, x:x + w] = 255
    return m


def ncc_score(q, t, roi=None):
    (bq, mq), (bt, mt) = q, t
    gq, gt = cv2.cvtColor(bq, cv2.COLOR_BGR2GRAY), cv2.cvtColor(bt, cv2.COLOR_BGR2GRAY)
    warp = ecc_align(gq, gt, mq)
    iq, it = inner_mask(mq), inner_mask(mt)
    Gq = grad_mag(cv2.GaussianBlur(clahe(gq), (0, 0), 1.5), iq)
    Gt = grad_mag(cv2.GaussianBlur(clahe(gt), (0, 0), 1.5), it)
    aligned = cv2.warpAffine(Gq, warp, (CANVAS, CANVAS), flags=cv2.INTER_LINEAR | cv2.WARP_INVERSE_MAP)
    am = cv2.warpAffine(iq, warp, (CANVAS, CANVAS), flags=cv2.INTER_NEAREST | cv2.WARP_INVERSE_MAP)
    g = max(0.0, masked_corr(aligned, Gt, am, it))
    if roi is None:
        return g
    rm = roi_mask(roi)
    r = max(0.0, masked_corr(aligned, Gt, am & rm, it & rm))
    return 0.4 * g + 0.6 * r


def emboss_edges(bgr, mask):
    g = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY).astype(np.float32)
    hp = g - cv2.GaussianBlur(g, (0, 0), CANVAS / 48)
    sd = max(hp[mask > 0].std(), 1e-3) if (mask > 0).any() else 1
    hp8 = np.clip(hp * (128 / (2.5 * sd)) + 128, 0, 255).astype(np.uint8)
    k = CANVAS // 24 * 2 + 1
    inner = cv2.erode(mask, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k)))  # 輪郭帯を除外
    hp8[inner == 0] = 0
    hp8 = cv2.GaussianBlur(hp8, (3, 3), 0)
    e = cv2.Canny(hp8, 40, 110)
    e &= inner
    dt = cv2.distanceTransform(255 - e, cv2.DIST_L2, 3)
    return e, dt


def chamfer_dir(ae, bdt, dx, dy, roi=None):
    n = ae.shape[0]
    rx, ry, rw, rh = (0, 0, n, n) if roi is None else [int(v * n) for v in roi]
    x0, y0 = max(rx, -dx), max(ry, -dy)
    x1, y1 = min(rx + rw, n - dx), min(ry + rh, n - dy)
    if x1 - x0 < 4 or y1 - y0 < 4:
        return -1
    a = ae[y0:y1, x0:x1]
    b = bdt[y0 + dy:y1 + dy, x0 + dx:x1 + dx]
    cnt = int((a > 0).sum())
    if cnt < 20:
        return -1
    return float(np.exp(-b[a > 0] / 3.0).mean())


def _sym(eq, dq, et, dtt, roi):
    best = -1.0
    for dy in range(-6, 7, 3):
        for dx in range(-6, 7, 3):
            f = chamfer_dir(eq, dtt, dx, dy, roi)
            b = chamfer_dir(et, dq, -dx, -dy, roi)
            if f < 0 or b < 0:
                continue
            best = max(best, 2 * f * b / max(f + b, 1e-6))
    return best


def emboss_score(q, t, roi=None):
    (eq, dq), (et, dtt) = q, t
    if (eq > 0).sum() < 30 or (et > 0).sum() < 30:
        return 0.0
    g = max(0.0, _sym(eq, dq, et, dtt, None))
    if roi is None:
        return g
    r = _sym(eq, dq, et, dtt, roi)
    return g if r < 0 else 0.35 * g + 0.65 * r


# ---------------------------------------------------------------- 実験
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--trials", type=int, default=12)
    ap.add_argument("--out", default="")
    ap.add_argument("--seed", type=int, default=1)
    args = ap.parse_args()
    rng = np.random.default_rng(args.seed)
    random.seed(args.seed)
    if args.out:
        os.makedirs(args.out, exist_ok=True)

    # 製品: 形はほぼ同じで押印文字だけ違うものを含める(最難関ケース)
    products = {"A-12": "A-12", "A-13": "A-13", "B-07": "B-07", "A-12X": "A-12X"}
    parts = {k: make_foam(v, seed=i) for i, (k, v) in enumerate(products.items())}

    # 登録: 各製品 3 ビュー (中距離・正対・環境光 / フラッシュ寄り)
    templates = {}
    for name, (pb, pm) in parts.items():
        views = []
        for j in range(3):
            sc = render_scene(pb, pm, scale=rng.uniform(0.9, 1.3), angle=rng.uniform(-15, 15), tilt=rng.uniform(-0.05, 0.05),
                              gamma=rng.uniform(0.9, 1.1), shadow=rng.uniform(0, 0.2), noise=0.01, rng=rng)
            _, cnts = segment_dark_bg(sc)
            cb, cm = canonicalize(sc, segment_dark_bg(sc)[0], cnts[0])
            if views:  # 向き揃え
                ref = grad_mag(clahe(cv2.cvtColor(views[0][0], cv2.COLOR_BGR2GRAY)), views[0][1])
                cands = variants(cb, cm)
                cb, cm = max(cands, key=lambda v: masked_corr(ref, grad_mag(clahe(cv2.cvtColor(v[0], cv2.COLOR_BGR2GRAY)), v[1]), views[0][1], v[1]))
            views.append((cb, cm))
            if args.out:
                cv2.imwrite(os.path.join(args.out, f"tmpl_{name}_{j}.png"), cb)
        templates[name] = [dict(img=v, feat=feats(*v), hog=hog(*v), emb=emboss_edges(*v)) for v in views]

    methods = ["Shape", "Feature", "HOG", "NCC", "Emboss", "NCC+ROI", "Emboss+ROI"]
    ROI = (0.06, 0.30, 0.62, 0.42)  # ユーザが押印文字を囲んだ想定(正規化座標)
    genuine = {m: [] for m in methods}
    impostor = {m: [] for m in methods}
    fails = 0
    conds = []

    for t in range(args.trials):
        name = random.choice(list(products))
        pb, pm = parts[name]
        cond = dict(scale=rng.uniform(0.55, 1.6), angle=rng.uniform(0, 360), tilt=rng.uniform(-0.18, 0.18),
                    gamma=rng.uniform(0.6, 1.6), shadow=rng.uniform(0, 0.5), noise=rng.uniform(0.005, 0.03))
        sc = render_scene(pb, pm, rng=rng, **cond)
        mask, cnts = segment_dark_bg(sc)
        if len(cnts) != 1:
            fails += 1
            continue
        cb, cm = canonicalize(sc, mask, cnts[0])
        if args.out:
            cv2.imwrite(os.path.join(args.out, f"query_{t:02d}_{name}.png"), cb)
        qvars = variants(cb, cm)
        qfeat = feats(cb, cm)
        qhog = [hog(*v) for v in qvars]
        qemb = [emboss_edges(*v) for v in qvars]
        for tname, views in templates.items():
            sc_m = {m: 0.0 for m in methods}
            for v in views:
                sc_m["Shape"] = max(sc_m["Shape"], shape_score(cm, v["img"][1]))
                sc_m["Feature"] = max(sc_m["Feature"], feature_score(qfeat, v["feat"]))
                sc_m["HOG"] = max(sc_m["HOG"], max(hog_score(h, v["hog"]) for h in qhog))
                sc_m["NCC"] = max(sc_m["NCC"], max(ncc_score(qv, v["img"]) for qv in qvars))
                sc_m["Emboss"] = max(sc_m["Emboss"], max(emboss_score(e, v["emb"]) for e in qemb))
                sc_m["NCC+ROI"] = max(sc_m["NCC+ROI"], max(ncc_score(qv, v["img"], ROI) for qv in qvars))
                sc_m["Emboss+ROI"] = max(sc_m["Emboss+ROI"], max(emboss_score(e, v["emb"], ROI) for e in qemb))
            bucket = genuine if tname == name else impostor
            for m in methods:
                bucket[m].append(sc_m[m])
        conds.append(cond)

    print(f"試行 {args.trials}, セグメンテーション失敗 {fails}")
    print(f"\n{'手法':11} {'本人min':>8} {'本人中央':>8} {'他者max':>8} {'他者中央':>8} {'余裕':>7}  判定")
    for m in methods:
        g, i = np.array(genuine[m]), np.array(impostor[m])
        gap = g.min() - i.max()
        verdict = "◎ 完全分離" if gap > 0 else ("○ 概ね分離" if np.median(g) - np.percentile(i, 95) > 0.05 else "✗ 重なり大")
        print(f"{m:11} {g.min():8.3f} {np.median(g):8.3f} {i.max():8.3f} {np.median(i):8.3f} {gap:7.3f}  {verdict}")

    # 融合(既定重み)
    for label, w in [("既定重み(ROIなし)", {"Shape": 1.0, "Feature": 2.0, "HOG": 1.5, "NCC": 1.5, "Emboss": 1.5}),
                     ("既定重み(ROIあり)", {"Shape": 1.0, "Feature": 2.0, "HOG": 1.5, "NCC+ROI": 1.5, "Emboss+ROI": 1.5}),
                     ("識別重視(Feature/ROI)", {"Shape": 0.5, "Feature": 3.0, "HOG": 0.5, "NCC+ROI": 2.0, "Emboss+ROI": 2.0})]:
        fg = sum(w[m] * np.array(genuine[m]) for m in w) / sum(w.values())
        fi = sum(w[m] * np.array(impostor[m]) for m in w) / sum(w.values())
        print(f"融合 {label:22}: 本人 min {fg.min():.3f} 中央 {np.median(fg):.3f} / 他者 max {fi.max():.3f} 中央 {np.median(fi):.3f} / 余裕 {fg.min() - fi.max():+.3f}")


if __name__ == "__main__":
    main()
