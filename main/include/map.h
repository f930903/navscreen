#pragma once
#include <stdint.h>
#include <stdbool.h>
#include "lvgl.h"

#ifdef __cplusplus
extern "C" {
#endif

/* WGS-84（GPS 原始）→ GCJ-02（高德/腾讯/国内底图）坐标纠偏 */
void map_wgs84_to_gcj02(double lat, double lon, double *out_lat, double *out_lon);

/* 经纬度 → 瓦片浮点坐标（slippy map），z 为层级 */
void map_lonlat_to_tile_f(double lon, double lat, int z, double *fx, double *fy);

/**
 * 把以 (lat, lon) 为中心的 3×3 瓦片拼到目标画布缓冲区
 * dst: 目标缓冲区（RGB565，lv_color_t*），w/h 为画布尺寸
 * lat/lon: 中心坐标。is_gcj02=true 表示传入的就是 GCJ-02（手机推过来的高德坐标），
 *          false 表示 WGS-84（板载 GPS 原始输出），内部自动纠偏
 * z: 瓦片层级；zoom: 缩放系数（1.0=原始，2.0=放大一倍）
 * 成功返回 true（SD 卡里有对应瓦片），失败返回 false（调用方应退回矢量地图）
 */
bool map_blit(lv_color_t *dst, int w, int h, double lat, double lon, int z, float zoom, bool is_gcj02);

/* 释放瓦片缓存（切城市/换层级时调用） */
void map_cache_reset(void);

/**
 * 经纬度 → 屏幕像素（中心在屏幕正中，正北朝上，未做旋转）
 * 所有坐标都用 GCJ-02（与底图一致）
 */
void map_lonlat_to_screen(double clat, double clon, int z, float zoom,
                          int w, int h, double lat, double lon, int *sx, int *sy);

/**
 * 在画布上叠加画一条折线（用于规划路线 / 行驶轨迹）
 * latlon: 交错的 [lat0, lon0, lat1, lon1, ...]，共 n 个点，GCJ-02
 * color565: RGB565 颜色；width_px: 线宽（像素）
 */
void map_draw_route(lv_color_t *dst, int w, int h,
                    double clat, double clon, int z, float zoom,
                    const double *latlon, int n, uint16_t color565, int width_px);

#ifdef __cplusplus
}
#endif
