#!/usr/bin/env python3
"""
積み重ねた/隣接するグレー箱の合成画像で、「一番上の箱の内側矩形」検出と
パース補正 → 金属部品セグメンテーションの挙動を確かめる。
アプリの BoxRectifier と同じ手順。
  python boxsim.py [--out dir]
"""
import argparse, os, math
import cv2
import numpy as np


def make_stack_scene(rng, tilt=0.0, rot=0.0, shadow=0.35, neighbor=True, rim_dark=True, size=(960, 1280)):
    """上面視: 一番上の箱(内側+縁)、周囲に下段/隣の箱の縁と影、箱の中に穴あき金属板。"""
    H, W = size
    img = np.full((H, W, 3), 105, np.uint8)               # 周囲(下段の箱の上面/隣の箱)
    # 隣の箱の縁(暗い線)を周囲に描く
    if neighbor:
        for k in range(3):
            x = int(W * (0.05 + 0.3 * k)); cv2.line(img, (x, 0), (x, H), (70, 70, 70), 6)
            y = int(H * (0.08 + 0.3 * k)); cv2.line(img, (0, y), (W, y), (70, 70, 70), 6)
        # 隣の箱の中の別の部品(写り込み)
        cv2.rectangle(img, (int(W * 0.02), int(H * 0.05)), (int(W * 0.12), int(H * 0.25)), (190, 188, 185), -1)
    # 一番上の箱: 内側矩形(基準座標)
    bx0, by0, bx1, by1 = int(W * 0.18), int(H * 0.15), int(W * 0.84), int(H * 0.87)
    base = np.full((H, W, 3), 125, np.uint8)
    # 内壁の影: 内側矩形の一辺側を暗く
    inner = base.copy()
    grad = np.linspace(1.0 - shadow, 1.0, by1 - by0, dtype=np.float32)[:, None, None]
    inner[by0:by1, bx0:bx1] = np.clip(inner[by0:by1, bx0:bx1] * grad, 0, 255).astype(np.uint8)
    # 縁(rim): 内側矩形の外側 3% 幅、暗いか明るい帯
    rim = (60, 60, 60) if rim_dark else (170, 170, 170)
    rw = int(W * 0.03)
    top = np.full((H, W, 3), 0, np.uint8)
    mask_top = np.zeros((H, W), np.uint8)
    cv2.rectangle(top, (bx0 - rw, by0 - rw), (bx1 + rw, by1 + rw), rim, -1)
    cv2.rectangle(mask_top, (bx0 - rw, by0 - rw), (bx1 + rw, by1 + rw), 255, -1)
    top[by0:by1, bx0:bx1] = inner[by0:by1, bx0:bx1]
    # 金属板 + 穴
    pw, ph = int((bx1 - bx0) * 0.6), int((by1 - by0) * 0.55)
    px, py = (bx0 + bx1) // 2 - pw // 2, (by0 + by1) // 2 - ph // 2
    plate = np.zeros((H, W), np.uint8)
    cv2.rectangle(plate, (px, py), (px + pw, py + ph), 255, -1)
    holes = [(px + pw // 5, py + ph // 4), (px + 4 * pw // 5, py + ph // 4), (px + pw // 5, py + 3 * ph // 4), (px + 4 * pw // 5, py + 3 * ph // 4), (px + pw // 2, py + ph // 2)]
    for h in holes: cv2.circle(plate, h, int(pw * 0.05), 0, -1)
    metal = np.clip(np.full((H, W, 3), 200, np.float32) * np.linspace(0.8, 1.15, W, dtype=np.float32)[None, :, None], 0, 255).astype(np.uint8)
    top[plate > 0] = metal[plate > 0]
    # 一番上の箱を画像に合成 → 全体に回転+パース
    scene = img.copy(); scene[mask_top > 0] = top[mask_top > 0]
    cx, cy = W / 2, H / 2
    M = cv2.getRotationMatrix2D((cx, cy), rot, 1.0); Hm = np.vstack([M, [0, 0, 1]])
    P = np.eye(3); P[2, 0] = tilt / W; P[2, 1] = tilt * 0.6 / H
    T1 = np.array([[1, 0, -cx], [0, 1, -cy], [0, 0, 1]], float); T2 = np.array([[1, 0, cx], [0, 1, cy], [0, 0, 1]], float)
    Hm = T2 @ P @ T1 @ Hm
    out = cv2.warpPerspective(scene, Hm, (W, H), borderValue=(105, 105, 105))
    out = np.clip(out.astype(np.float32) + rng.normal(0, 4, out.shape), 0, 255).astype(np.uint8)
    corners = cv2.perspectiveTransform(np.float32([[[bx0, by0], [bx1, by0], [bx1, by1], [bx0, by1]]]), Hm)[0]
    return out, corners, len(holes)


# ------------------------------------------------------------- BoxRectifier (アプリと同じ手順)
def order_corners(pts):
    pts = np.array(pts, np.float32)
    s = pts.sum(1); d = np.diff(pts, axis=1).ravel()
    return np.float32([pts[np.argmin(s)], pts[np.argmin(d)], pts[np.argmax(s)], pts[np.argmax(d)]])


def detect_box(bgr, min_area_ratio=0.15, border_px=6):
    """
    一番上の箱の内側矩形 = 「エッジに囲まれた、画像の縁に触れない最大の低勾配領域」。
    輪郭の 4 点近似に頼らないので、隣の箱の線が縁に接していても壊れない。
    """
    H, W = bgr.shape[:2]
    g = cv2.GaussianBlur(cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY), (5, 5), 0)
    gx = cv2.Sobel(g, cv2.CV_32F, 1, 0); gy = cv2.Sobel(g, cv2.CV_32F, 0, 1)
    mag = cv2.magnitude(gx, gy)
    edge = (mag > 60).astype(np.uint8) * 255
    edge = cv2.dilate(edge, cv2.getStructuringElement(cv2.MORPH_RECT, (7, 7)))
    free = cv2.bitwise_not(edge)
    n, lab, stats, _ = cv2.connectedComponentsWithStats(free, connectivity=4)
    best = None
    for i in range(1, n):
        x, y, w, h, a = stats[i]
        if a < min_area_ratio * H * W:
            continue
        if x <= border_px or y <= border_px or x + w >= W - border_px or y + h >= H - border_px:
            continue  # 画像の縁に触れる = 箱が画面外にはみ出している/周囲の領域
        if best is None or a > best[0]:
            best = (a, i)
    if best is None:
        return None
    comp = (lab == best[1]).astype(np.uint8) * 255
    cnts, _ = cv2.findContours(comp, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    c = max(cnts, key=cv2.contourArea)
    hull = cv2.convexHull(c)
    ap = cv2.approxPolyDP(hull, 0.03 * cv2.arcLength(hull, True), True)
    if len(ap) == 4:
        q = order_corners(ap.reshape(4, 2))
    else:
        q = order_corners(cv2.boxPoints(cv2.minAreaRect(hull)))
    # 妥当性: 対辺の長さ比
    w1 = np.linalg.norm(q[1] - q[0]); w2 = np.linalg.norm(q[2] - q[3]); h1 = np.linalg.norm(q[3] - q[0]); h2 = np.linalg.norm(q[2] - q[1])
    if min(w1, w2) / max(w1, w2) < 0.5 or min(h1, h2) / max(h1, h2) < 0.5:
        return None
    # 内側の縁に沿った領域なので、エッジ膨張分(約 3px)だけ外へ広げる
    cx, cy = q.mean(0)
    q = q + (q - [cx, cy]) / np.linalg.norm(q - [cx, cy], axis=1, keepdims=True) * 4
    return q.astype(np.float32)


def rectify(bgr, quad, shrink=0.05):
    tl, tr, br, bl = quad
    w = int(max(np.linalg.norm(tr - tl), np.linalg.norm(br - bl)))
    h = int(max(np.linalg.norm(bl - tl), np.linalg.norm(br - tr)))
    dst = np.float32([[0, 0], [w, 0], [w, h], [0, h]])
    Hm = cv2.getPerspectiveTransform(quad, dst)
    out = cv2.warpPerspective(bgr, Hm, (w, h))
    sx, sy = int(w * shrink), int(h * shrink)
    return out[sy:h - sy, sx:w - sx]


def gray_box_color_seg(bgr, thr=35):
    """
    背景(箱の内側)を画像外周のリングから推定。明度 L は平面(a·x + b·y + c)でフィットして
    内壁の影の勾配を吸収し、a/b は中央値。金属と箱は共に無彩色なので L の重みは 1.0。
    """
    lab = cv2.cvtColor(bgr, cv2.COLOR_BGR2Lab).astype(np.float32)
    H, W = lab.shape[:2]; b = max(2, int(min(H, W) * 0.06))
    ring = np.zeros((H, W), bool); ring[:b, :] = ring[-b:, :] = True; ring[:, :b] = ring[:, -b:] = True
    ys, xs = np.nonzero(ring)
    A = np.stack([xs, ys, np.ones_like(xs)], 1).astype(np.float64)
    Lr = lab[..., 0][ring].astype(np.float64)
    coef, *_ = np.linalg.lstsq(A, Lr, rcond=None)
    # 外れ値(縁の残り・部品)を 1 回除いて再フィット
    resid = np.abs(A @ coef - Lr); keep = resid < max(8.0, 2.0 * np.median(resid))
    coef, *_ = np.linalg.lstsq(A[keep], Lr[keep], rcond=None)
    yy, xx = np.mgrid[0:H, 0:W]
    Lbg = coef[0] * xx + coef[1] * yy + coef[2]
    abg = np.median(lab[..., 1][ring]); bbg = np.median(lab[..., 2][ring])
    d = np.abs(lab[..., 0] - Lbg) * 1.0 + np.abs(lab[..., 1] - abg) + np.abs(lab[..., 2] - bbg)
    d = cv2.GaussianBlur(np.clip(d, 0, 255).astype(np.uint8), (5, 5), 0)
    _, m = cv2.threshold(d, thr, 255, cv2.THRESH_BINARY)
    k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5))
    m = cv2.morphologyEx(m, cv2.MORPH_OPEN, k); m = cv2.morphologyEx(m, cv2.MORPH_CLOSE, k)
    cnts, _ = cv2.findContours(m, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    cnts = [c for c in cnts if cv2.contourArea(c) > 0.01 * H * W]
    return m, sorted(cnts, key=cv2.contourArea, reverse=True)


def count_holes(mask, cnt):
    om = np.zeros_like(mask); cv2.drawContours(om, [cnt], -1, 255, -1); om &= mask
    cs, hier = cv2.findContours(om, cv2.RETR_CCOMP, cv2.CHAIN_APPROX_SIMPLE)
    return sum(1 for i in range(len(cs)) if hier[0][i][3] >= 0 and cv2.contourArea(cs[i]) > 0.0004 * mask.size)


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--out", default=""); ap.add_argument("--trials", type=int, default=20)
    args = ap.parse_args(); rng = np.random.default_rng(3)
    if args.out: os.makedirs(args.out, exist_ok=True)
    ok_box = ok_seg_direct = ok_seg_rect = 0
    for t in range(args.trials):
        scene, gt, nholes = make_stack_scene(rng, tilt=rng.uniform(-0.25, 0.25), rot=rng.uniform(-12, 12), shadow=rng.uniform(0.1, 0.5), rim_dark=bool(rng.integers(0, 2)))
        # 補正なし
        m0, c0 = gray_box_color_seg(scene)
        direct_ok = len(c0) == 1 and count_holes(m0, c0[0]) == nholes
        ok_seg_direct += direct_ok
        # 箱検出 → 補正
        q = detect_box(scene)
        box_ok = q is not None and np.abs(order_corners(gt) - q).max() < 0.03 * scene.shape[1]
        ok_box += box_ok
        rect_ok = False
        if q is not None:
            r = rectify(scene, q)
            m1, c1 = gray_box_color_seg(r)
            rect_ok = len(c1) == 1 and count_holes(m1, c1[0]) == nholes
        ok_seg_rect += rect_ok
        if args.out and t < 6:
            vis = scene.copy()
            if q is not None: cv2.polylines(vis, [q.astype(int)], True, (0, 200, 255), 6)
            cv2.drawContours(vis, c0, -1, (60, 60, 230), 4)
            cv2.imwrite(f"{args.out}/box_{t}_scene.jpg", vis)
            if q is not None:
                rv = r.copy(); cv2.drawContours(rv, c1, -1, (80, 220, 80), 4); cv2.imwrite(f"{args.out}/box_{t}_rect.jpg", rv)
        print(f"trial {t:2d}: 箱検出 {'OK' if box_ok else 'NG'} / 補正なし切り出し {'OK' if direct_ok else f'NG(物体{len(c0)})'} / 補正後切り出し {'OK' if rect_ok else 'NG'}")
    n = args.trials
    print(f"\n箱検出 {ok_box}/{n}  補正なしで部品+穴が正しく取れた {ok_seg_direct}/{n}  補正後 {ok_seg_rect}/{n}")


if __name__ == "__main__":
    main()
