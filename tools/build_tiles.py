#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
离线地图瓦片生成工具
--------------------
把在线地图瓦片下载下来，裁成 256x256、转成 RGB565 原始二进制，
按 /tiles/<z>/<x>_<y>.raw 的目录结构写入 SD 卡，供 ESP32 直接读取。

用法：
    pip install pillow requests
    python build_tiles.py --lat 29.84 --lon 115.24 --radius 3 --zoom 12 13 14 --out ./tiles

参数说明：
    --lat/--lon   中心点经纬度（GCJ-02 火星坐标，与高德/腾讯一致）
    --radius      中心点向外扩展的瓦片半径（单位：格），3 表示 7x7 格
    --zoom        要下载的层级，可多个（12=城区概览，14=街道级，16=细节）
    --out         输出目录，整个拷进 SD 卡根目录即可
    --source      osm（默认，免费无需 key）或 amap（高德，需 --key）

注意：
    * OSM 瓦片有访问频率限制，请控制下载量，单次别超过几百张
    * 生成的 .raw 是小端 RGB565，与 sdkconfig 里 swap_bytes=false 对应
"""

import argparse
import math
import os
import time
import struct
from io import BytesIO

try:
    import requests
    from PIL import Image
except ImportError:
    raise SystemExit("请先安装依赖：pip install pillow requests")

TILE = 256
UA = {"User-Agent": "navscreen-tile-builder/1.0 (personal DIY project)"}


def lonlat_to_tile(lon, lat, z):
    """经纬度 → 瓦片号（slippy map 标准）"""
    n = 2 ** z
    x = int((lon + 180.0) / 360.0 * n)
    lat_rad = math.radians(lat)
    y = int((1.0 - math.log(math.tan(lat_rad) + 1 / math.cos(lat_rad)) / math.pi) / 2.0 * n)
    return x, y


def fetch_osm(z, x, y):
    url = f"https://tile.openstreetmap.org/{z}/{x}/{y}.png"
    r = requests.get(url, headers=UA, timeout=15)
    r.raise_for_status()
    return Image.open(BytesIO(r.content)).convert("RGB")


def fetch_amap(z, x, y, key):
    # 高德瓦片：style=7 为简洁矢量底图
    url = (f"https://webrd0{(x + y) % 4 + 1}.is.autonavi.com/appmaptile"
           f"?lang=zh_cn&size=1&scale=1&style=7&x={x}&y={y}&z={z}&key={key}")
    r = requests.get(url, headers=UA, timeout=15)
    r.raise_for_status()
    return Image.open(BytesIO(r.content)).convert("RGB")


def to_rgb565_raw(img):
    """PIL RGB 图 → 小端 RGB565 字节流"""
    w, h = img.size
    px = img.load()
    buf = bytearray(w * h * 2)
    i = 0
    for yy in range(h):
        for xx in range(w):
            r, g, b = px[xx, yy]
            v = ((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3)
            struct.pack_into("<H", buf, i)
            buf[i] = v & 0xFF
            buf[i + 1] = (v >> 8) & 0xFF
            i += 2
    return bytes(buf)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--lat", type=float, required=True, help="中心纬度")
    ap.add_argument("--lon", type=float, required=True, help="中心经度")
    ap.add_argument("--radius", type=int, default=3, help="向外扩展格数")
    ap.add_argument("--zoom", type=int, nargs="+", default=[13], help="层级，可多个")
    ap.add_argument("--out", default="./tiles", help="输出目录")
    ap.add_argument("--source", choices=["osm", "amap"], default="osm")
    ap.add_argument("--key", default="", help="高德 Key（source=amap 时必填）")
    args = ap.parse_args()

    total = 0
    for z in args.zoom:
        cx, cy = lonlat_to_tile(args.lon, args.lat, z)
        zdir = os.path.join(args.out, str(z))
        os.makedirs(zdir, exist_ok=True)

        for dx in range(-args.radius, args.radius + 1):
            for dy in range(-args.radius, args.radius + 1):
                x, y = cx + dx, cy + dy
                path = os.path.join(zdir, f"{x}_{y}.raw")
                if os.path.exists(path):
                    continue
                try:
                    img = (fetch_amap(z, x, y, args.key) if args.source == "amap"
                           else fetch_osm(z, x, y))
                    if img.size != (TILE, TILE):
                        img = img.resize((TILE, TILE))
                    with open(path, "wb") as f:
                        f.write(to_rgb565_raw(img))
                    total += 1
                    print(f"[OK] z{z} {x}_{y}")
                except Exception as e:
                    print(f"[跳过] z{z} {x}_{y} -> {e}")
                time.sleep(0.25)   # 礼貌爬取，别把人服务器搞崩

    print(f"\n完成，共 {total} 张瓦片，输出在 {os.path.abspath(args.out)}")
    print("把整个 tiles 目录拷进 SD 卡根目录即可（路径 /sdcard/tiles/...）")


if __name__ == "__main__":
    main()
