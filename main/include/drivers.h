#pragma once
#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ---------------- GPS / 定位状态 ---------------- */
typedef struct {
    bool   valid;        // 定位是否有效
    double lat;          // 纬度
    double lon;          // 经度
    float  speed_kmh;    // 速度
    float  course;       // 航向角（度，0=正北）
    int    hour, minute; // UTC+8 时间
    int    sats;         // 可见卫星数
} gps_state_t;

void  gps_init(void);
const gps_state_t *gps_get(void);

/* ---------------- EC11 旋钮 ---------------- */
typedef enum {
    KNOB_NONE = 0,
    KNOB_CW,        // 顺时针
    KNOB_CCW,       // 逆时针
    KNOB_CLICK,     // 短按
    KNOB_LONG,      // 长按
} knob_event_t;

void knob_init(void);
/* 非阻塞取出一个事件，没有则返回 KNOB_NONE */
knob_event_t knob_poll(void);

/* ---------------- SD 卡 + 地图瓦片 ---------------- */
bool sd_init(void);
/* 读取一个瓦片到 buffer，成功返回 true。tile 尺寸 TILE_SIZE×TILE_SIZE RGB565 */
bool map_load_tile(int z, int x, int y, uint16_t *out_buf, int buf_px);

#ifdef __cplusplus
}
#endif
