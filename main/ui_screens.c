#include "ui_screens.h"
#include "config.h"
#include "map.h"
#include "ble_nav.h"
#include "drivers.h"

#include "lvgl.h"
#include "esp_log.h"
#include "esp_heap_caps.h"
#include "freertos/FreeRTOS.h"

#include <stdio.h>
#include <string.h>
#include <math.h>

static const char *TAG = "ui";

/* ============================================================
 *  全局：3 个界面容器，同层叠放，靠 HIDDEN 切换
 * ============================================================ */
static lv_obj_t *g_screens[UI_COUNT];
static ui_screen_t g_cur = UI_NAV;

/* 导航界面元素 */
static lv_obj_t *g_map_canvas;
static lv_color_t *g_canvas_buf = NULL;
/* 转向箭头小画布（叠在底部蓝条左侧，蓝底白箭头） */
static lv_obj_t *g_turn_canvas;
static lv_color_t *g_turn_buf = NULL;
#define TURN_CANVAS_PX   56
static lv_obj_t *g_lbl_status, *g_lbl_eta, *g_lbl_eta_sub;
static lv_obj_t *g_lbl_dist, *g_lbl_road, *g_lbl_speed, *g_lbl_zoom;
static lv_obj_t *g_lbl_limit;
/* 仪表界面元素 */
static lv_obj_t *g_lbl_kmh, *g_arc_speed, *g_lbl_dash_info;
/* 设置界面 */
static lv_obj_t *g_sw_night;

static int   g_zoom_pct = 100;      // 地图缩放 80% ~ 220%
static bool  g_heading_up = false;  // 车头朝上 / 正北朝上
static bool  g_night = false;
static int   g_tile_z = 15;         // 离线瓦片层级（SD 卡里要有这一层）
static bool  g_use_tiles = true;    // 优先用真实瓦片，失败自动退回矢量图

/* 地图中心（GCJ-02），每次渲染前更新 */
static double g_center_lat = 29.8400;
static double g_center_lon = 115.2400;

#define PI_F 3.14159265f

/* ============================================================
 *  小工具：在 canvas 上画线段 / 箭头 / 三角
 * ============================================================ */
static void draw_seg(lv_obj_t *cv, int x0, int y0, int x1, int y1, int w, lv_color_t col)
{
    lv_point_t p[2] = { {x0, y0}, {x1, y1} };
    lv_draw_line_dsc_t d;
    lv_draw_line_dsc_init(&d);
    d.width = w; d.color = col;
    d.round_start = true; d.round_end = true;
    lv_canvas_draw_line(cv, p, 2, &d);
}

/* ang_deg：0=正上，90=正右，180=正下，270=正左（屏幕坐标系，顺时针） */
static void draw_head(lv_obj_t *cv, int x, int y, float ang_deg, int size, lv_color_t col)
{
    float a  = ang_deg * PI_F / 180.0f;
    float ux = sinf(a),  uy = -cosf(a);          // 单位方向
    float px = -uy,      py = ux;                // 垂直方向

    lv_point_t tri[3] = {
        { (int)(x + ux * size),                          (int)(y + uy * size) },
        { (int)(x - ux * size * 0.45f + px * size * 0.6f), (int)(y - uy * size * 0.45f + py * size * 0.6f) },
        { (int)(x - ux * size * 0.45f - px * size * 0.6f), (int)(y - uy * size * 0.45f - py * size * 0.6f) },
    };
    lv_draw_polygon_dsc_t d;
    lv_draw_polygon_dsc_init(&d);
    d.bg_color = col; d.bg_opa = LV_OPA_COVER; d.border_width = 0;
    lv_canvas_draw_polygon(cv, tri, 3, &d);
}

/* 相对坐标（-1000..1000 → -r..r）折线 + 末端箭头 */
static void draw_arrow_shape(lv_obj_t *cv, int cx, int cy, int r,
                             const int16_t *pts, int n, float head_ang)
{
    int lw = r / 3; if (lw < 4) lw = 4;
    for (int i = 0; i < n - 1; i++) {
        int x0 = cx + (int)((float)pts[i * 2]     * r / 1000.0f);
        int y0 = cy + (int)((float)pts[i * 2 + 1] * r / 1000.0f);
        int x1 = cx + (int)((float)pts[(i + 1) * 2]     * r / 1000.0f);
        int y1 = cy + (int)((float)pts[(i + 1) * 2 + 1] * r / 1000.0f);
        draw_seg(cv, x0, y0, x1, y1, lw, lv_color_white());
    }
    int ex = cx + (int)((float)pts[(n - 1) * 2]     * r / 1000.0f);
    int ey = cy + (int)((float)pts[(n - 1) * 2 + 1] * r / 1000.0f);
    draw_head(cv, ex, ey, head_ang, r / 2, lv_color_white());
}

/* 转向指令 → 箭头图形（与协议 0x01 的 turn 枚举一一对应） */
static void draw_turn_icon(lv_obj_t *cv, int cx, int cy, int r, uint8_t turn)
{
    static const int16_t A_STRAIGHT[] = {   0, 900,   0,-650 };
    static const int16_t A_LEFT[]     = {   0, 900,   0,   0, -850,   0 };
    static const int16_t A_RIGHT[]    = {   0, 900,   0,   0,  850,   0 };
    static const int16_t A_SLEFT[]    = { 480, 900,-330,-560 };
    static const int16_t A_SRIGHT[]   = {-480, 900, 330,-560 };
    static const int16_t A_UTURN[]    = { 420, 900, 420,-120,   0,-520,-420,-120,-420, 700 };
    static const int16_t A_KEEP_L[]   = { 300, 900, -60, 260,-620,-320 };
    static const int16_t A_KEEP_R[]   = {-300, 900,  60, 260, 620,-320 };

    switch (turn) {
    case 0:  draw_arrow_shape(cv, cx, cy, r, A_STRAIGHT, 2,   0); break;
    case 1:  draw_arrow_shape(cv, cx, cy, r, A_LEFT,     3, 270); break;
    case 2:  draw_arrow_shape(cv, cx, cy, r, A_RIGHT,    3,  90); break;
    case 3:  draw_arrow_shape(cv, cx, cy, r, A_SLEFT,    2, 327); break;
    case 4:  draw_arrow_shape(cv, cx, cy, r, A_SRIGHT,   2,  33); break;
    case 5:  draw_arrow_shape(cv, cx, cy, r, A_UTURN,    5, 180); break;
    case 6:  draw_arrow_shape(cv, cx, cy, r, A_KEEP_L,   3, 320); break;
    case 7:  draw_arrow_shape(cv, cx, cy, r, A_KEEP_R,   3,  40); break;
    case 8: {  /* 到达目的地：旗子 */
        draw_seg(cv, cx, cy + r, cx, cy - r, r / 3, lv_color_white());
        lv_point_t flag[3] = { {cx, cy - r}, {cx + r * 3 / 4, cy - r * 5 / 8}, {cx, cy - r / 4} };
        lv_draw_polygon_dsc_t d; lv_draw_polygon_dsc_init(&d);
        d.bg_color = lv_color_white(); d.bg_opa = LV_OPA_COVER; d.border_width = 0;
        lv_canvas_draw_polygon(cv, flag, 3, &d);
        break;
    }
    case 9:
    case 10: { /* 环岛：整圆 + 切向箭头 */
        const int N = 20;
        int px = cx + (int)(r * 0.75f), py = cy;
        int lw = r / 4; if (lw < 3) lw = 3;
        for (int i = 1; i <= N; i++) {
            float a = 2.0f * PI_F * i / N;
            int x = cx + (int)(r * 0.75f * cosf(a));
            int y = cy + (int)(r * 0.75f * sinf(a));
            draw_seg(cv, px, py, x, y, lw, lv_color_white());
            px = x; py = y;
        }
        draw_head(cv, cx, cy - (int)(r * 0.75f), (turn == 9) ? 0 : 180, r / 2, lv_color_white());
        break;
    }
    case 11: { /* 重新规划：270° 圆弧 + 箭头 */
        const int N = 18;
        int px = cx + r * 3 / 4, py = cy;
        int lw = r / 4; if (lw < 3) lw = 3;
        for (int i = 1; i <= N; i++) {
            float a = 2.0f * PI_F * 0.75f * i / N;
            int x = cx + (int)(r * 0.75f * cosf(a));
            int y = cy + (int)(r * 0.75f * sinf(a));
            draw_seg(cv, px, py, x, y, lw, lv_color_white());
            px = x; py = y;
        }
        draw_head(cv, px, py, 90, r / 2, lv_color_white());
        break;
    }
    default:
        draw_arrow_shape(cv, cx, cy, r, A_STRAIGHT, 2, 0);
        break;
    }
}

/* ============================================================
 *  地图渲染：底图（瓦片 / 矢量）+ 路线 + 定位箭头
 * ============================================================ */
static void draw_self_marker(void)
{
    if (!g_canvas_buf) return;
    const int W = DISP_HRES, H = DISP_VRES;

    /* 航向：优先手机给的（与导航同源），否则板载 GPS */
    float course = 0;
    const nav_pos_t *p = ble_nav_get_pos();
    const gps_state_t *g = gps_get();
    if (p->valid && !ble_nav_pos_is_stale(5000)) course = p->course;
    else course = g->course;

    /* 车头朝上模式：地图不转时，三角箭头固定指向正上方 */
    if (g_heading_up) course = 0;

    int cx = W / 2, cy = H / 2, r = 16;

    /* 方向三角（尖端指向 course，0=正北/上方） */
    float a = course * PI_F / 180.0f;
    float ux = sinf(a), uy = -cosf(a);
    float px = -uy,     py = ux;

    lv_point_t tri[3] = {
        { (int)(cx + ux * r),                      (int)(cy + uy * r) },
        { (int)(cx - ux * r * 0.5f + px * r * 0.7f), (int)(cy - uy * r * 0.5f + py * r * 0.7f) },
        { (int)(cx - ux * r * 0.5f - px * r * 0.7f), (int)(cy - uy * r * 0.5f - py * r * 0.7f) },
    };
    lv_draw_polygon_dsc_t pd;
    lv_draw_polygon_dsc_init(&pd);
    pd.bg_color = lv_color_hex(0x2B7CF3);
    pd.border_color = lv_color_white();
    pd.border_width = 3;
    pd.bg_opa = LV_OPA_COVER;
    lv_canvas_draw_polygon(g_map_canvas, tri, 3, &pd);
}

static void map_render(void)
{
    if (!g_canvas_buf || !g_map_canvas) return;

    const int W = DISP_HRES, H = DISP_VRES;
    float z = g_zoom_pct / 100.0f;

    const nav_pos_t  *p = ble_nav_get_pos();
    const gps_state_t *g = gps_get();

    /* ── 中心点：手机位置（GCJ-02）优先，否则板载 GPS（WGS-84）── */
    bool phone_fresh = p->valid && !ble_nav_pos_is_stale(5000);
    if (phone_fresh) {
        g_center_lat = p->lat; g_center_lon = p->lon;
    } else if (g->valid) {
        double glat, glon;
        map_wgs84_to_gcj02(g->lat, g->lon, &glat, &glon);
        g_center_lat = glat; g_center_lon = glon;
    }

    /* ── 1. 底图：优先 SD 卡瓦片，失败退回矢量图 ── */
    bool tiled = false;
    if (g_use_tiles) {
        tiled = map_blit(g_canvas_buf, W, H, g_center_lat, g_center_lon,
                         g_tile_z, z, true);   // 以上都是 GCJ-02
    }
    if (!tiled) {
        lv_color_t bg = g_night ? lv_color_hex(0x1B2530) : lv_color_hex(0xF3ECE5);
        lv_canvas_fill_bg(g_map_canvas, bg, LV_OPA_COVER);

        float cx = W / 2.0f, cy = H / 2.0f;
#define SX(x) ((int)(cx + ((x) - 240) * z))
#define SY(y) ((int)(cy + ((y) - 240) * z))
        lv_draw_rect_dsc_t rds; lv_draw_rect_dsc_init(&rds);
        rds.bg_opa = LV_OPA_COVER; rds.radius = 4;
        rds.bg_color = g_night ? lv_color_hex(0x243040) : lv_color_hex(0xE8DDD4);
        lv_canvas_draw_rect(g_map_canvas, SX(60), SY(80), (int)(110 * z), (int)(80 * z), &rds);
        lv_canvas_draw_rect(g_map_canvas, SX(330), SY(150), (int)(120 * z), (int)(90 * z), &rds);
        rds.bg_color = g_night ? lv_color_hex(0x1F3A2C) : lv_color_hex(0xCFE3C4);
        lv_canvas_draw_rect(g_map_canvas, SX(80), SY(330), (int)(130 * z), (int)(70 * z), &rds);
        rds.bg_color = g_night ? lv_color_hex(0x16324A) : lv_color_hex(0xBCDCF0);
        lv_canvas_draw_rect(g_map_canvas, SX(300), SY(380), (int)(160 * z), (int)(90 * z), &rds);

        lv_draw_line_dsc_t lds; lv_draw_line_dsc_init(&lds);
        lds.width = (int)(10 * z); if (lds.width < 2) lds.width = 2;
        lds.color = g_night ? lv_color_hex(0x38475C) : lv_color_white();
        lds.round_start = true; lds.round_end = true;
        lv_point_t roads[][2] = {
            {{-40, 150}, {520, 120}}, {{150, -40}, {165, 520}}, {{-40, 300}, {520, 285}},
            {{330, -40}, {310, 520}}, {{-40, 420}, {520, 400}},
        };
        for (unsigned i = 0; i < sizeof(roads) / sizeof(roads[0]); i++) {
            lv_point_t pp[2] = { { SX(roads[i][0].x), SY(roads[i][0].y) },
                                 { SX(roads[i][1].x), SY(roads[i][1].y) } };
            lv_canvas_draw_line(g_map_canvas, pp, 2, &lds);
        }

        lv_draw_label_dsc_t tds; lv_draw_label_dsc_init(&tds);
        tds.color = g_night ? lv_color_hex(0xC3CCD6) : lv_color_hex(0x6F665C);
        tds.font = &lv_font_montserrat_14;
        lv_canvas_draw_text(g_map_canvas, SX(60), SY(96), (int)(90 * z), &tds, "原市花园");
        lv_canvas_draw_text(g_map_canvas, SX(340), SY(170), (int)(90 * z), &tds, "竹江景苑");
        lv_canvas_draw_text(g_map_canvas, SX(180), SY(140), (int)(70 * z), &tds, "东一路");
        lv_canvas_draw_text(g_map_canvas, SX(120), SY(336), (int)(70 * z), &tds, "竹江路");
#undef SX
#undef SY
    }

    /* ── 2. 叠加：手机推送的规划路线（GCJ-02）── */
    const nav_pt_t *rpts = NULL;
    int rn = ble_nav_get_route(&rpts);
    if (rn >= 2) {
        /* 转成交错的 lat/lon 数组（栈上，最多 NAV_ROUTE_MAX×2 个 double 太大，
         * 这里逐段调用，避免大栈分配） */
        double seg[2 * 2];
        seg[0] = rpts[0].lat; seg[1] = rpts[0].lon;
        for (int i = 1; i < rn; i++) {
            seg[2] = rpts[i].lat; seg[3] = rpts[i].lon;
            map_draw_route(g_canvas_buf, W, H, g_center_lat, g_center_lon,
                           g_tile_z, z, seg, 2, 0x2B7CF3, (int)(7 * z) + 3);
            seg[0] = seg[2]; seg[1] = seg[3];
        }
    }

    /* ── 3. 叠加：当前位置三角 ── */
    draw_self_marker();

    lv_obj_invalidate(g_map_canvas);
}

/* ============================================================
 *  导航界面
 * ============================================================ */
static void build_nav(lv_obj_t *parent)
{
    /* 地图 canvas（480×480 RGB565 ≈ 460KB，放 PSRAM） */
    g_canvas_buf = (lv_color_t *)heap_caps_malloc(
        DISP_HRES * DISP_VRES * sizeof(lv_color_t), MALLOC_CAP_SPIRAM);
    if (!g_canvas_buf) {
        ESP_LOGE(TAG, "canvas 缓冲区申请失败（PSRAM 不足？）");
        return;
    }
    g_map_canvas = lv_canvas_create(parent);
    lv_canvas_set_buffer(g_map_canvas, g_canvas_buf, DISP_HRES, DISP_VRES, LV_IMG_CF_TRUE_COLOR);
    lv_obj_align(g_map_canvas, LV_ALIGN_CENTER, 0, 0);

    /* 顶部状态条 */
    lv_obj_t *bar = lv_obj_create(parent);
    lv_obj_set_size(bar, 320, 30);
    lv_obj_align(bar, LV_ALIGN_TOP_MID, 0, 30);
    lv_obj_set_style_bg_color(bar, lv_color_white(), 0);
    lv_obj_set_style_bg_opa(bar, LV_OPA_90, 0);
    lv_obj_set_style_radius(bar, 15, 0);
    lv_obj_set_style_border_width(bar, 0, 0);
    g_lbl_status = lv_label_create(bar);
    lv_obj_center(g_lbl_status);
    lv_label_set_text(g_lbl_status, "GPS  --   09:41   未连手机");

    /* 剩余里程卡 */
    lv_obj_t *card = lv_obj_create(parent);
    lv_obj_set_size(card, 190, 56);
    lv_obj_align(card, LV_ALIGN_TOP_MID, 0, 66);
    lv_obj_set_style_radius(card, 16, 0);
    lv_obj_set_style_bg_color(card, lv_color_white(), 0);
    lv_obj_set_style_border_width(card, 0, 0);
    g_lbl_eta = lv_label_create(card);
    lv_obj_align(g_lbl_eta, LV_ALIGN_TOP_MID, 0, 6);
    lv_obj_set_style_text_font(g_lbl_eta, &lv_font_montserrat_24, 0);
    lv_label_set_text(g_lbl_eta, "-- km");
    g_lbl_eta_sub = lv_label_create(card);
    lv_obj_align(g_lbl_eta_sub, LV_ALIGN_BOTTOM_MID, 0, -6);
    lv_label_set_text(g_lbl_eta_sub, "等待手机导航");

    /* 底部转向条（蓝色） */
    lv_obj_t *turn = lv_obj_create(parent);
    lv_obj_set_size(turn, 340, 76);
    lv_obj_align(turn, LV_ALIGN_BOTTOM_MID, 0, 44);
    lv_obj_set_style_bg_color(turn, lv_color_hex(0x2B7CF3), 0);
    lv_obj_set_style_radius(turn, 20, 0);
    lv_obj_set_style_border_width(turn, 0, 0);
    lv_obj_set_style_pad_left(turn, 76, 0);
    g_lbl_road = lv_label_create(turn);
    lv_obj_align(g_lbl_road, LV_ALIGN_TOP_MID, 24, 10);
    lv_obj_set_style_text_color(g_lbl_road, lv_color_white(), 0);
    lv_obj_set_style_text_font(g_lbl_road, &lv_font_montserrat_20, 0);
    lv_label_set_text(g_lbl_road, "等待指令");
    g_lbl_dist = lv_label_create(turn);
    lv_obj_align(g_lbl_dist, LV_ALIGN_BOTTOM_MID, 24, -10);
    lv_obj_set_style_text_color(g_lbl_dist, lv_color_white(), 0);
    lv_label_set_text(g_lbl_dist, "未连接手机");

    /* 转向箭头画布：压在蓝条之上，蓝底白箭头 */
    g_turn_buf = (lv_color_t *)heap_caps_malloc(
        TURN_CANVAS_PX * TURN_CANVAS_PX * sizeof(lv_color_t), MALLOC_CAP_SPIRAM);
    if (g_turn_buf) {
        g_turn_canvas = lv_canvas_create(parent);
        lv_canvas_set_buffer(g_turn_canvas, g_turn_buf,
                             TURN_CANVAS_PX, TURN_CANVAS_PX, LV_IMG_CF_TRUE_COLOR);
        /* 蓝条：340×76，BOTTOM_MID(0,44) → 左边缘 x=70，中心 y=480-44-38=398 */
        lv_obj_set_pos(g_turn_canvas, 70 + 10, 398 - TURN_CANVAS_PX / 2);
    }

    /* 速度卡 */
    lv_obj_t *sp = lv_obj_create(parent);
    lv_obj_set_size(sp, 78, 48);
    lv_obj_align(sp, LV_ALIGN_BOTTOM_LEFT, 32, -20);
    lv_obj_set_style_radius(sp, 14, 0);
    lv_obj_set_style_border_width(sp, 0, 0);
    g_lbl_speed = lv_label_create(sp);
    lv_obj_center(g_lbl_speed);
    lv_obj_set_style_text_font(g_lbl_speed, &lv_font_montserrat_24, 0);
    lv_label_set_text(g_lbl_speed, "0");

    /* 限速牌（有推送时才显示） */
    g_lbl_limit = lv_label_create(parent);
    lv_obj_align(g_lbl_limit, LV_ALIGN_BOTTOM_RIGHT, -34, -22);
    lv_obj_set_style_bg_color(g_lbl_limit, lv_color_white(), 0);
    lv_obj_set_style_bg_opa(g_lbl_limit, LV_OPA_COVER, 0);
    lv_obj_set_style_radius(g_lbl_limit, 8, 0);
    lv_obj_set_style_pad_all(g_lbl_limit, 4, 0);
    lv_obj_set_style_text_font(g_lbl_limit, &lv_font_montserrat_20, 0);
    lv_obj_add_flag(g_lbl_limit, LV_OBJ_FLAG_HIDDEN);
    lv_label_set_text(g_lbl_limit, "60");

    /* 缩放指示 */
    g_lbl_zoom = lv_label_create(parent);
    lv_obj_align(g_lbl_zoom, LV_ALIGN_RIGHT_MID, -14, 0);
    lv_obj_set_style_text_color(g_lbl_zoom, lv_color_black(), 0);
    lv_label_set_text(g_lbl_zoom, "100%");

    map_render();
}

/* ============================================================
 *  仪表界面
 * ============================================================ */
static void build_dash(lv_obj_t *parent)
{
    lv_obj_set_style_bg_color(parent, lv_color_hex(0x101014), 0);
    lv_obj_set_style_bg_opa(parent, LV_OPA_COVER, 0);

    g_arc_speed = lv_arc_create(parent);
    lv_obj_set_size(g_arc_speed, 330, 330);
    lv_obj_center(g_arc_speed);
    lv_arc_set_range(g_arc_speed, 0, 120);
    lv_arc_set_value(g_arc_speed, 0);
    lv_obj_set_style_arc_width(g_arc_speed, 12, LV_PART_MAIN);
    lv_obj_set_style_arc_color(g_arc_speed, lv_color_hex(0x2C2C32), LV_PART_MAIN);
    lv_obj_set_style_arc_width(g_arc_speed, 12, LV_PART_INDICATOR);
    lv_obj_set_style_arc_color(g_arc_speed, lv_color_hex(0x2B7CF3), LV_PART_INDICATOR);
    lv_obj_remove_style(g_arc_speed, NULL, LV_PART_KNOB);

    g_lbl_kmh = lv_label_create(parent);
    lv_obj_set_style_text_font(g_lbl_kmh, &lv_font_montserrat_48, 0);
    lv_obj_set_style_text_color(g_lbl_kmh, lv_color_white(), 0);
    lv_obj_align(g_lbl_kmh, LV_ALIGN_CENTER, 0, -16);
    lv_label_set_text(g_lbl_kmh, "0");

    lv_obj_t *u = lv_label_create(parent);
    lv_obj_align(u, LV_ALIGN_CENTER, 0, 26);
    lv_obj_set_style_text_color(u, lv_color_hex(0x9B96A0), 0);
    lv_label_set_text(u, "KM / H");

    g_lbl_dash_info = lv_label_create(parent);
    lv_obj_align(g_lbl_dash_info, LV_ALIGN_BOTTOM_MID, 0, -46);
    lv_obj_set_style_text_color(g_lbl_dash_info, lv_color_hex(0xB9B4BE), 0);
    lv_label_set_text(g_lbl_dash_info, "今日 0 km · 离线");
}

/* ============================================================
 *  设置界面
 * ============================================================ */
static void build_settings(lv_obj_t *parent)
{
    lv_obj_set_style_bg_color(parent, lv_color_hex(0xF5F1EC), 0);
    lv_obj_set_style_bg_opa(parent, LV_OPA_COVER, 0);

    lv_obj_t *h = lv_label_create(parent);
    lv_obj_align(h, LV_ALIGN_TOP_MID, 0, 70);
    lv_obj_set_style_text_font(h, &lv_font_montserrat_20, 0);
    lv_label_set_text(h, "骑行设置");

    static const char *items[] = {
        "白天 / 夜间 地图", "屏幕亮度", "优先手机导航", "限速提醒", "偏航自动重算"
    };
    for (int i = 0; i < 5; i++) {
        lv_obj_t *row = lv_obj_create(parent);
        lv_obj_set_size(row, 320, 42);
        lv_obj_align(row, LV_ALIGN_TOP_MID, 0, 110 + i * 46);
        lv_obj_set_style_bg_opa(row, LV_OPA_TRANSP, 0);
        lv_obj_set_style_border_side(row, LV_BORDER_SIDE_BOTTOM, 0);
        lv_obj_set_style_border_width(row, 1, 0);
        lv_obj_set_style_radius(row, 0, 0);

        lv_obj_t *t = lv_label_create(row);
        lv_obj_align(t, LV_ALIGN_LEFT_MID, 0, 0);
        lv_label_set_text(t, items[i]);

        lv_obj_t *sw = lv_switch_create(row);
        lv_obj_set_size(sw, 44, 24);
        lv_obj_align(sw, LV_ALIGN_RIGHT_MID, 0, 0);
        if (i == 0) g_sw_night = sw;
        if (i == 2 || i == 3 || i == 4) lv_obj_add_state(sw, LV_STATE_CHECKED);
    }
}

/* ============================================================
 *  公共接口
 * ============================================================ */
void ui_init(void)
{
    lv_obj_t *scr = lv_scr_act();

    for (int i = 0; i < UI_COUNT; i++) {
        lv_obj_t *c = lv_obj_create(scr);
        lv_obj_set_size(c, DISP_HRES, DISP_VRES);
        lv_obj_center(c);
        lv_obj_set_style_bg_opa(c, LV_OPA_COVER, 0);
        lv_obj_set_style_border_width(c, 0, 0);
        lv_obj_set_style_radius(c, 0, 0);
        lv_obj_set_style_pad_all(c, 0, 0);
        g_screens[i] = c;
    }

    build_nav(g_screens[UI_NAV]);
    build_dash(g_screens[UI_DASH]);
    build_settings(g_screens[UI_SETTINGS]);

    /* 圆形遮罩：黑环盖住方形屏四角，实机圆形屏可去掉 */
    lv_obj_t *mask = lv_obj_create(scr);
    lv_obj_set_size(mask, DISP_HRES + 120, DISP_VRES + 120);
    lv_obj_center(mask);
    lv_obj_set_style_radius(mask, LV_RADIUS_CIRCLE, 0);
    lv_obj_set_style_bg_opa(mask, LV_OPA_TRANSP, 0);
    lv_obj_set_style_border_width(mask, 60, 0);
    lv_obj_set_style_border_color(mask, lv_color_black(), 0);
    lv_obj_set_style_border_opa(mask, LV_OPA_COVER, 0);

    ui_show(UI_NAV);
}

void ui_show(ui_screen_t s)
{
    for (int i = 0; i < UI_COUNT; i++) {
        if (i == s) lv_obj_clear_flag(g_screens[i], LV_OBJ_FLAG_HIDDEN);
        else        lv_obj_add_flag(g_screens[i], LV_OBJ_FLAG_HIDDEN);
    }
    g_cur = s;
    if (s == UI_NAV) map_render();
}

/* 转向指令 → 中文文案 */
static const char *turn_text(uint8_t t)
{
    static const char *m[] = {
        "直行", "左转", "右转", "稍向左", "稍向右", "掉头",
        "靠左行驶", "靠右行驶", "到达目的地", "进入环岛", "驶出环岛", "重新规划中"
    };
    return (t < sizeof(m) / sizeof(m[0])) ? m[t] : "直行";
}

static void refresh_nav(void)
{
    const gps_state_t *g = gps_get();
    const nav_state_t *n = ble_nav_get();
    const nav_pos_t   *p = ble_nav_get_pos();
    char buf[96];

    bool online = n->connected && !ble_nav_is_stale(10000);

    /* ── 速度：手机（与导航同源） > 板载 GPS ── */
    float spd = 0;
    if (online && n->navigating)            spd = n->speed_kmh;
    else if (p->valid && !ble_nav_pos_is_stale(5000)) spd = p->speed_kmh;
    else                                    spd = g->speed_kmh;

    snprintf(buf, sizeof(buf), "%d", (int)spd);
    lv_label_set_text(g_lbl_speed, buf);
    lv_label_set_text(g_lbl_kmh, buf);
    lv_arc_set_value(g_arc_speed, (int)spd);

    /* ── 顶部状态条 ── */
    if (online) {
        snprintf(buf, sizeof(buf), "手机 %d%%   GPS %s   %02d:%02d",
                 n->phone_batt, g->valid ? "OK" : "--", g->hour, g->minute);
    } else {
        snprintf(buf, sizeof(buf), "GPS %s   %02d:%02d   未连手机",
                 g->valid ? "OK" : "--", g->hour, g->minute);
    }
    lv_label_set_text(g_lbl_status, buf);

    /* ── 限速牌 ── */
    if (online && n->limit_speed > 0) {
        snprintf(buf, sizeof(buf), "%d", n->limit_speed);
        lv_label_set_text(g_lbl_limit, buf);
        lv_obj_clear_flag(g_lbl_limit, LV_OBJ_FLAG_HIDDEN);
    } else {
        lv_obj_add_flag(g_lbl_limit, LV_OBJ_FLAG_HIDDEN);
    }

    /* ── 转向条 + 剩余里程 ── */
    if (online && n->navigating) {
        if (n->turn == 8) {
            snprintf(buf, sizeof(buf), "到达目的地");
        } else if (n->turn == 11) {
            snprintf(buf, sizeof(buf), "重新规划路线");
        } else {
            snprintf(buf, sizeof(buf), "%d 米后 %s", n->dist_m, turn_text(n->turn));
        }
        lv_label_set_text(g_lbl_road, buf);

        if (n->tip[0]) {
            lv_label_set_text(g_lbl_dist, n->tip);
        } else {
            snprintf(buf, sizeof(buf), "进入 %s",
                     n->road_name[0] ? n->road_name : "前方道路");
            lv_label_set_text(g_lbl_dist, buf);
        }

        float km = n->remain_m / 1000.0f;
        snprintf(buf, sizeof(buf), "%.1f km", km);
        lv_label_set_text(g_lbl_eta, buf);
        int hh = n->remain_s / 3600, mm = (n->remain_s % 3600) / 60;
        if (hh > 0) snprintf(buf, sizeof(buf), "%d 小时 %d 分", hh, mm);
        else        snprintf(buf, sizeof(buf), "%d 分钟", mm);
        lv_label_set_text(g_lbl_eta_sub, buf);
    } else {
        lv_label_set_text(g_lbl_road, "离线导航");
        snprintf(buf, sizeof(buf), "%s · 跟随定位",
                 n->road_name[0] ? n->road_name : "无算路");
        lv_label_set_text(g_lbl_dist, buf);
        lv_label_set_text(g_lbl_eta, "-- km");
        lv_label_set_text(g_lbl_eta_sub, g->valid ? "板载 GPS 工作中" : "等待定位");
    }

    /* ── 底部转向箭头（画在小画布上，压在蓝条左侧）── */
    if (g_turn_buf && g_turn_canvas) {
        lv_canvas_fill_bg(g_turn_canvas, lv_color_hex(0x2B7CF3), LV_OPA_COVER);
        draw_turn_icon(g_turn_canvas, TURN_CANVAS_PX / 2, TURN_CANVAS_PX / 2, 22,
                       (online && n->navigating) ? n->turn : 0);
        lv_obj_invalidate(g_turn_canvas);
    }
}

void ui_tick(void)
{
    refresh_nav();

    /* 仪表页副信息：里程/连接状态（接 BMS 后可替换里程） */
    const nav_state_t *n = ble_nav_get();
    char info[64];
    snprintf(info, sizeof(info), "今日 -- km · %s",
             n->connected ? "手机导航中" : "离线模式");
    lv_label_set_text(g_lbl_dash_info, info);

    /* 地图跟随（有定位才动，避免一直重绘瓦片） */
    if (g_cur == UI_NAV) map_render();
}

void ui_handle_knob(knob_event_t e)
{
    switch (e) {
    case KNOB_CW:
        if (g_cur == UI_NAV) {
            g_zoom_pct += 10;
            if (g_zoom_pct > 220) g_zoom_pct = 220;
            static char z[12];
            snprintf(z, sizeof(z), "%d%%", g_zoom_pct);
            lv_label_set_text(g_lbl_zoom, z);
            map_render();
        } else if (g_cur == UI_SETTINGS) {
            /* 设置页：旋转切换白天 / 夜间地图 */
            g_night = true;
            if (g_sw_night) lv_obj_add_state(g_sw_night, LV_STATE_CHECKED);
        }
        break;
    case KNOB_CCW:
        if (g_cur == UI_NAV) {
            g_zoom_pct -= 10;
            if (g_zoom_pct < 80) g_zoom_pct = 80;
            static char z2[12];
            snprintf(z2, sizeof(z2), "%d%%", g_zoom_pct);
            lv_label_set_text(g_lbl_zoom, z2);
            map_render();
        } else if (g_cur == UI_SETTINGS) {
            g_night = false;
            if (g_sw_night) lv_obj_clear_state(g_sw_night, LV_STATE_CHECKED);
            map_render();
        }
        break;
    case KNOB_CLICK:
        /* 导航页：切 正北/车头朝上；其它页循环切换 */
        if (g_cur == UI_NAV) {
            g_heading_up = !g_heading_up;
            map_render();
        } else {
            ui_show((ui_screen_t)((g_cur + 1) % UI_COUNT));
        }
        break;
    case KNOB_LONG:
        ui_show(UI_NAV);
        break;
    default:
        break;
    }
}
