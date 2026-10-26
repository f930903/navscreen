#pragma once
#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/* 屏端最多缓存多少个路线点（GCJ-02），手机端负责抽稀 */
#define NAV_ROUTE_MAX       160

typedef struct {
    double lat;
    double lon;
} nav_pt_t;

/* 手机推送过来的导航状态（协议见 docs/ble_protocol.md） */
typedef struct {
    bool     connected;      // 手机是否已连接
    bool     navigating;     // 是否在导航中（收到过 NAV_STATE）
    uint8_t  turn;           // 转向指令，见协议 0x01
    uint16_t dist_m;         // 距下一动作米数
    uint32_t remain_m;       // 全程剩余米数
    uint16_t remain_s;       // 全程剩余秒数
    uint8_t  speed_kmh;      // 车速
    char     road_name[48];  // 当前道路名
    char     dest[48];       // 目的地
    char     tip[48];        // 提示文本（限速/电子眼/车道）
    uint8_t  limit_speed;    // 限速值
    uint8_t  phone_batt;     // 手机电量
    uint32_t last_frame_ms;  // 最近一帧时间（用于断连判定）
} nav_state_t;

/* 手机推送过来的位置（协议 0x02，GCJ-02，与高德底图同一坐标系，无需纠偏） */
typedef struct {
    bool     valid;
    double   lat;
    double   lon;
    float    course;      // 航向角（度，0=正北）
    uint8_t  speed_kmh;
    uint32_t last_ms;
} nav_pos_t;

/* 启动 BLE GATT Server（设备名 NavScreen） */
void ble_nav_init(void);

/* 取当前导航状态；未连接时 navigating=false，界面应回退到板载 GPS */
const nav_state_t *ble_nav_get(void);

/* 取手机位置；valid=false 时界面应使用板载 GPS */
const nav_pos_t *ble_nav_get_pos(void);

/* 取规划路线：返回点数，pts 指向内部静态数组（GCJ-02） */
int ble_nav_get_route(const nav_pt_t **pts);

/* 距最后一帧是否超过 timeout_ms（用于判定手机掉线） */
bool ble_nav_is_stale(uint32_t timeout_ms);

/* 手机位置是否还是新鲜的（超过 timeout_ms 没收到 0x02 帧） */
bool ble_nav_pos_is_stale(uint32_t timeout_ms);

#ifdef __cplusplus
}
#endif
