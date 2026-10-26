#include "map.h"
#include "config.h"
#include "drivers.h"

#include "esp_log.h"
#include "esp_heap_caps.h"
#include "freertos/FreeRTOS.h"

#include <math.h>
#include <string.h>

static const char *TAG = "map";

/* ============================================================
 *  坐标纠偏：WGS-84 → GCJ-02
 *  国内底图（高德/腾讯/天地图）都用 GCJ-02，GPS 输出的是 WGS-84，
 *  不转换会有 300~600 米的偏移，必须转
 * ============================================================ */
static const double PI     = 3.14159265358979323846;
static const double A_AXIS = 6378245.0;          // 克拉索夫斯基椭球长半轴
static const double EE     = 0.00669342162296594323;

static double transform_lat(double x, double y)
{
    double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y
               + 0.1 * x * y + 0.2 * sqrt(fabs(x));
    ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0;
    ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0;
    ret += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0;
    return ret;
}

static double transform_lon(double x, double y)
{
    double ret = 300.0 + x + 2.0 * y + 0.1 * x * x
               + 0.1 * x * y + 0.1 * sqrt(fabs(x));
    ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0;
    ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0;
    ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0;
    return ret;
}

static int out_of_china(double lat, double lon)
{
    return !(lon > 72.004 && lon < 137.8347 && lat > 0.8293 && lat < 55.8271);
}

void map_wgs84_to_gcj02(double lat, double lon, double *out_lat, double *out_lon)
{
    if (out_of_china(lat, lon)) { *out_lat = lat; *out_lon = lon; return; }

    double dLat = transform_lon(lon - 105.0, lat - 35.0);
    double dLon = transform_lat(lon - 105.0, lat - 35.0);
    double radLat = lat / 180.0 * PI;
    double magic = sin(radLat);
    magic = 1 - EE * magic * magic;
    double sqrtMagic = sqrt(magic);

    dLat = (dLat * 180.0) / ((A_AXIS * (1 - EE)) / (magic * sqrtMagic) * PI);
    dLon = (dLon * 180.0) / (A_AXIS / sqrtMagic * cos(radLat) * PI);

    *out_lat = lat + dLat;
    *out_lon = lon + dLon;
}

void map_lonlat_to_tile_f(double lon, double lat, int z, double *fx, double *fy)
{
    double n = (double)(1 << z);
    *fx = (lon + 180.0) / 360.0 * n;
    double lat_rad = lat * PI / 180.0;
    *fy = (1.0 - log(tan(lat_rad) + 1.0 / cos(lat_rad)) / PI) / 2.0 * n;
}

/* ============================================================
 *  瓦片缓存：3×3 = 9 张，每张 256×256×2 = 128KB，共约 1.15MB（放 PSRAM）
 * ============================================================ */
#define MOSAIC   3
#define TILE_PX  (TILE_SIZE * TILE_SIZE)

static uint16_t *s_buf[MOSAIC * MOSAIC] = {0};
static int  s_key_z = -1;
static int  s_key_x[MOSAIC * MOSAIC];
static int  s_key_y[MOSAIC * MOSAIC];
static bool s_ok[MOSAIC * MOSAIC];
static bool s_inited = false;

static bool ensure_tile(int idx, int z, int x, int y)
{
    if (!s_buf[idx]) {
        s_buf[idx] = (uint16_t *)heap_caps_malloc(TILE_PX * sizeof(uint16_t), MALLOC_CAP_SPIRAM);
        if (!s_buf[idx]) { ESP_LOGE(TAG, "瓦片缓存申请失败"); return false; }
        s_key_x[idx] = INT32_MIN;
    }
    if (s_key_z == z && s_key_x[idx] == x && s_key_y[idx] == y) return s_ok[idx];

    s_key_x[idx] = x; s_key_y[idx] = y;
    s_ok[idx] = map_load_tile(z, x, y, s_buf[idx], TILE_PX);
    return s_ok[idx];
}

void map_cache_reset(void)
{
    s_key_z = -1;
    for (int i = 0; i < MOSAIC * MOSAIC; i++) s_key_x[i] = INT32_MIN;
    s_inited = false;
}

/* ============================================================
 *  拼图：最近邻采样，支持缩放
 * ============================================================ */
bool map_blit(lv_color_t *dst, int w, int h, double lat, double lon, int z, float zoom, bool is_gcj02)
{
    if (!dst) return false;
    if (zoom < 0.2f) zoom = 0.2f;

    /* 板载 GPS 输出 WGS-84 → 底图 GCJ-02；手机推来的已是 GCJ-02，直接用 */
    double glat = lat, glon = lon;
    if (!is_gcj02) map_wgs84_to_gcj02(lat, lon, &glat, &glon);

    double fx, fy;
    map_lonlat_to_tile_f(glon, glat, z, &fx, &fy);

    int ctx = (int)floor(fx);          // 中心瓦片号
    int cty = (int)floor(fy);
    int ox  = ctx - 1;                 // 3×3 左上角瓦片号
    int oy  = cty - 1;

    /* 中心点在 3×3 大图（768×768）里的像素坐标 */
    double cx_px = (fx - ox) * TILE_SIZE;
    double cy_px = (fy - oy) * TILE_SIZE;

    s_inited = true;
    s_key_z = z;

    /* 先确保 9 张瓦片都可读，有一张读不到就直接告诉调用方退回矢量图 */
    for (int ty = 0; ty < MOSAIC; ty++) {
        for (int tx = 0; tx < MOSAIC; tx++) {
            if (!ensure_tile(ty * MOSAIC + tx, z, ox + tx, oy + ty)) return false;
        }
    }

    const int world = MOSAIC * TILE_SIZE;

    for (int sy = 0; sy < h; sy++) {
        double wy = cy_px + (sy - h / 2.0) / zoom;
        int py = (int)floor(wy);
        if (py < 0 || py >= world) {
            for (int sx = 0; sx < w; sx++) dst[sy * w + sx] = lv_color_hex(0xE8E0D8);
            continue;
        }
        for (int sx = 0; sx < w; sx++) {
            double wx = cx_px + (sx - w / 2.0) / zoom;
            int px = (int)floor(wx);
            if (px < 0 || px >= world) { dst[sy * w + sx] = lv_color_hex(0xE8E0D8); continue; }

            int tidx = (py / TILE_SIZE) * MOSAIC + (px / TILE_SIZE);
            int inx  = px % TILE_SIZE;
            int iny  = py % TILE_SIZE;
            uint16_t c = s_buf[tidx][iny * TILE_SIZE + inx];

            lv_color_t col;
            col.full = c;                // 小端 RGB565，与 LV_COLOR_16_SWAP=0 对应
            dst[sy * w + sx] = col;
        }
    }
    return true;
}

/* ============================================================
 *  经纬度 → 屏幕像素（Web Mercator，正北朝上）
 * ============================================================ */
static void to_mercator(double lat, double lon, double *mx, double *my)
{
    double l = lat;
    if (l > 85.05112878) l = 85.05112878;
    if (l < -85.05112878) l = -85.05112878;
    *mx = lon * 20037508.34 / 180.0;
    *my = log(tan((90.0 + l) * PI / 360.0)) / (PI / 180.0) * 20037508.34 / 180.0;
}

/* 该层级下 1 像素代表多少米（乘缩放后为屏幕像素） */
static double meters_per_pixel(double lat, int z)
{
    double n = (double)(1 << z);
    return 156543.03392 * cos(lat * PI / 180.0) / n;
}

void map_lonlat_to_screen(double clat, double clon, int z, float zoom,
                          int w, int h, double lat, double lon, int *sx, int *sy)
{
    double cmx, cmy, mx, my;
    to_mercator(clat, clon, &cmx, &cmy);
    to_mercator(lat, lon, &mx, &my);
    double mpp = meters_per_pixel(clat, z);
    if (mpp <= 0) mpp = 1.0;
    *sx = (int)(w / 2.0 + (mx - cmx) / mpp * zoom);
    *sy = (int)(h / 2.0 - (my - cmy) / mpp * zoom);   /* 屏幕 y 向下，纬度越高越靠上 */
}

/* ============================================================
 *  在画布上叠加折线（Bresenham + 方块笔刷）
 * ============================================================ */
static void put_px(lv_color_t *dst, int w, int h, int x, int y, uint16_t c)
{
    if (x < 0 || y < 0 || x >= w || y >= h) return;
    dst[y * w + x].full = c;
}

static void brush(lv_color_t *dst, int w, int h, int x, int y, int r, uint16_t c)
{
    for (int dy = -r; dy <= r; dy++)
        for (int dx = -r; dx <= r; dx++)
            put_px(dst, w, h, x + dx, y + dy, c);
}

static void thick_line(lv_color_t *dst, int w, int h,
                       int x0, int y0, int x1, int y1, int r, uint16_t c)
{
    int dx = abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
    int dy = -abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
    int err = dx + dy;
    while (1) {
        brush(dst, w, h, x0, y0, r, c);
        if (x0 == x1 && y0 == y1) break;
        int e2 = 2 * err;
        if (e2 >= dy) { err += dy; x0 += sx; }
        if (e2 <= dx) { err += dx; y0 += sy; }
    }
}

void map_draw_route(lv_color_t *dst, int w, int h,
                    double clat, double clon, int z, float zoom,
                    const double *latlon, int n, uint16_t color565, int width_px)
{
    if (!dst || !latlon || n < 2) return;
    int r = width_px / 2;
    if (r < 0) r = 0;

    int px = 0, py = 0;
    for (int i = 0; i < n; i++) {
        int sx, sy;
        map_lonlat_to_screen(clat, clon, z, zoom, w, h, latlon[i * 2], latlon[i * 2 + 1], &sx, &sy);
        if (i > 0) thick_line(dst, w, h, px, py, sx, sy, r, color565);
        px = sx; py = sy;
    }
}
