#include "ble_nav.h"
#include "config.h"

#include "freertos/FreeRTOS.h"
#include "esp_log.h"
#include "esp_system.h"
#include "esp_bt.h"
#include "esp_bt_main.h"
#include "esp_gap_ble_api.h"
#include "esp_gatts_api.h"
#include "esp_gatt_common_api.h"
#include "esp_timer.h"

#include <string.h>
#include <stdio.h>
#include <stdlib.h>

static const char *TAG = "ble_nav";

/* ---- GATT 定义（与 docs/ble_protocol.md 一致）---- */
#define GATTS_SERVICE_UUID   0xFF10
#define GATTS_CHAR_DATA_UUID 0xFF11   // 手机 → 屏（写）
#define GATTS_CHAR_STAT_UUID 0xFF12   // 屏 → 手机（通知）
#define NAVSCREEN_APP_ID     0x55
#define DEVICE_NAME          "NavScreen"

enum {
    IDX_SVC,
    IDX_CHAR_DATA,
    IDX_CHAR_DATA_VAL,
    IDX_CHAR_STAT,
    IDX_CHAR_STAT_VAL,
    IDX_CHAR_STAT_CFG,
    IDX_NUM,
};

static uint16_t s_handle_table[IDX_NUM];
static uint16_t s_conn_id = 0;
static bool     s_connected = false;
static esp_gatt_if_t s_gatts_if = 0;

static nav_state_t s_nav = {0};
static nav_pos_t   s_pos = {0};

/* 规划路线缓冲（GCJ-02） */
static nav_pt_t s_route[NAV_ROUTE_MAX];
static int      s_route_n = 0;

/* ============================================================
 *  帧解析：AA 55 TYPE LEN PAYLOAD CRC8
 * ============================================================ */
typedef enum { WAIT_SOF1, WAIT_SOF2, READ_TYPE, READ_LEN, READ_PAYLOAD, READ_CRC } parse_st_t;

typedef struct {
    parse_st_t st;
    uint8_t    type, len, idx;
    uint8_t    buf[200];
} parser_t;

static parser_t s_p = { .st = WAIT_SOF1 };

static uint8_t crc8(const uint8_t *d, int n)
{
    uint8_t s = 0;
    for (int i = 0; i < n; i++) s += d[i];
    return (uint8_t)(~s & 0xFF);
}

static uint16_t rd_u16(const uint8_t *p) { return (uint16_t)(p[0] | (p[1] << 8)); }
static uint32_t rd_u32(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}
static double rd_f64(const uint8_t *p)
{
    uint64_t v = 0;
    for (int i = 7; i >= 0; i--) v = (v << 8) | p[i];
    double d; memcpy(&d, &v, 8); return d;
}

/* 取一段 UTF-8 文本，安全截断 + 补 '\0' */
static void take_text(char *dst, int cap, const uint8_t *src, int n)
{
    if (n > cap - 1) n = cap - 1;
    if (n > 0) memcpy(dst, src, n);
    dst[n] = 0;
}

static void handle_frame(uint8_t type, const uint8_t *p, uint8_t len)
{
    uint32_t now = (uint32_t)(esp_timer_get_time() / 1000);
    s_nav.last_frame_ms = now;

    switch (type) {
    case 0x01: {   /* NAV_STATE：手机端算路的转向指令 */
        if (len < 11) return;
        s_nav.turn      = p[0];
        s_nav.dist_m    = rd_u16(p + 1);
        s_nav.remain_m  = rd_u32(p + 3);
        s_nav.remain_s  = rd_u16(p + 7);
        s_nav.speed_kmh = p[9];
        uint8_t n = p[10];
        take_text(s_nav.road_name, sizeof(s_nav.road_name), p + 11, n);
        s_nav.navigating = true;
        ESP_LOGD(TAG, "turn=%u dist=%um remain=%um road=%s",
                 s_nav.turn, s_nav.dist_m, (unsigned)s_nav.remain_m, s_nav.road_name);
        break;
    }
    case 0x02: {   /* POSITION：手机定位（GCJ-02），优先于板载 GPS */
        if (len < 20) return;
        s_pos.lat       = rd_f64(p);
        s_pos.lon       = rd_f64(p + 8);
        s_pos.course    = rd_u16(p + 16) / 10.0f;
        s_pos.speed_kmh = p[18];
        s_pos.valid     = p[19] != 0;
        s_pos.last_ms   = now;
        break;
    }
    case 0x03: {   /* TEXT：0=目的地 1=提示 2=限速 3=车道 */
        if (len < 2) return;
        uint8_t sub = p[0], n = p[1];
        int avail = len - 2;
        if (n > avail) n = avail;
        if (sub == 0) {
            take_text(s_nav.dest, sizeof(s_nav.dest), p + 2, n);
        } else if (sub == 2) {
            char tmp[16];
            take_text(tmp, sizeof(tmp), p + 2, n);
            int v = atoi(tmp);
            s_nav.limit_speed = (v > 0 && v < 200) ? (uint8_t)v : 0;
            take_text(s_nav.tip, sizeof(s_nav.tip), p + 2, n);
        } else {
            take_text(s_nav.tip, sizeof(s_nav.tip), p + 2, n);
        }
        break;
    }
    case 0x04:     /* HEARTBEAT */
        if (len >= 1) s_nav.phone_batt = p[0];
        break;
    case 0x05: {   /* ROUTE：规划路线点（GCJ-02） */
        if (len < 2) return;
        uint8_t seq = p[0];
        uint8_t n   = p[1];
        if (len < 2 + n * 16) return;
        if (seq == 0) s_route_n = 0;                 // 新路线，清空旧的
        for (uint8_t i = 0; i < n && s_route_n < NAV_ROUTE_MAX; i++) {
            const uint8_t *q = p + 2 + i * 16;
            s_route[s_route_n].lat = rd_f64(q);
            s_route[s_route_n].lon = rd_f64(q + 8);
            s_route_n++;
        }
        ESP_LOGD(TAG, "路线点 seq=%u +%u 累计=%d", seq, n, s_route_n);
        break;
    }
    default:
        break;
    }
}

static void feed_bytes(const uint8_t *data, uint16_t len)
{
    for (uint16_t i = 0; i < len; i++) {
        uint8_t c = data[i];
        switch (s_p.st) {
        case WAIT_SOF1: if (c == 0xAA) s_p.st = WAIT_SOF2; break;
        case WAIT_SOF2: s_p.st = (c == 0x55) ? READ_TYPE : WAIT_SOF1; break;
        case READ_TYPE: s_p.type = c; s_p.st = READ_LEN; break;
        case READ_LEN:
            s_p.len = c; s_p.idx = 0;
            if (c > sizeof(s_p.buf)) { s_p.st = WAIT_SOF1; break; }   // 非法长度，丢弃
            s_p.st = (c == 0) ? READ_CRC : READ_PAYLOAD;
            break;
        case READ_PAYLOAD:
            s_p.buf[s_p.idx++] = c;
            if (s_p.idx >= s_p.len) s_p.st = READ_CRC;
            break;
        case READ_CRC: {
            uint8_t tmp[2 + sizeof(s_p.buf)];
            tmp[0] = s_p.type; tmp[1] = s_p.len;
            memcpy(tmp + 2, s_p.buf, s_p.len);
            if (crc8(tmp, s_p.len + 2) == c) handle_frame(s_p.type, s_p.buf, s_p.len);
            else ESP_LOGW(TAG, "CRC 校验失败，丢弃一帧");
            s_p.st = WAIT_SOF1;
            break;
        }
        }
    }
}

/* ============================================================
 *  GATT Server
 * ============================================================ */
static const uint8_t s_adv_service_uuid128[16] = {
    0xFB, 0x34, 0x9B, 0x5F, 0x80, 0x00, 0x00, 0x80,
    0x00, 0x10, 0x00, 0x00, 0x10, 0xFF, 0x00, 0x00,
};

static esp_ble_adv_data_t s_adv_data = {
    .set_scan_rsp = false,
    .include_name = true,
    .include_txpower = true,
    .min_interval = 0x20,
    .max_interval = 0x40,
    .service_uuid_len = 16,
    .p_service_uuid = (uint8_t *)s_adv_service_uuid128,
    .flag = (ESP_BLE_ADV_FLAG_GEN_DISC | ESP_BLE_ADV_FLAG_BREDR_NOT_SPT),
};

static esp_ble_adv_params_t s_adv_params = {
    .adv_int_min = 0x20, .adv_int_max = 0x40,
    .adv_type = ADV_TYPE_IND,
    .own_addr_type = BLE_ADDR_TYPE_PUBLIC,
    .channel_map = ADV_CHNL_ALL,
    .adv_filter_policy = ADV_FILTER_ALLOW_SCAN_ANY_CON_ANY,
};

/* ---- 属性表 ---- */
static const uint16_t s_uuid_pri     = ESP_GATT_UUID_PRI_SERVICE;
static const uint16_t s_uuid_char    = ESP_GATT_UUID_CHAR_DECLARE;
static const uint16_t s_uuid_ccc     = ESP_GATT_UUID_CHAR_CLIENT_CONFIG;
static const uint16_t s_uuid_svc     = GATTS_SERVICE_UUID;
static const uint16_t s_uuid_data    = GATTS_CHAR_DATA_UUID;
static const uint16_t s_uuid_stat    = GATTS_CHAR_STAT_UUID;
static const uint8_t  s_prop_write   = ESP_GATT_CHAR_PROP_BIT_WRITE;
static const uint8_t  s_prop_notify  = ESP_GATT_CHAR_PROP_BIT_NOTIFY;
static uint8_t s_data_val[200] = {0};
static uint8_t s_stat_val[1]   = {0};
static uint8_t s_stat_ccc[2]   = {0, 0};

static const esp_gatts_attr_db_t s_gatt_db[IDX_NUM] = {
    [IDX_SVC] = {
        {ESP_GATT_AUTO_RSP},
        {ESP_UUID_LEN_16, (uint8_t *)&s_uuid_pri, ESP_GATT_PERM_READ,
         sizeof(uint16_t), sizeof(uint16_t), (uint8_t *)&s_uuid_svc},
    },
    [IDX_CHAR_DATA] = {
        {ESP_GATT_AUTO_RSP},
        {ESP_UUID_LEN_16, (uint8_t *)&s_uuid_char, ESP_GATT_PERM_READ,
         sizeof(uint8_t), sizeof(uint8_t), (uint8_t *)&s_prop_write},
    },
    [IDX_CHAR_DATA_VAL] = {
        {ESP_GATT_RSP_BY_APP},
        {ESP_UUID_LEN_16, (uint8_t *)&s_uuid_data, ESP_GATT_PERM_WRITE,
         sizeof(s_data_val), 0, s_data_val},
    },
    [IDX_CHAR_STAT] = {
        {ESP_GATT_AUTO_RSP},
        {ESP_UUID_LEN_16, (uint8_t *)&s_uuid_char, ESP_GATT_PERM_READ,
         sizeof(uint8_t), sizeof(uint8_t), (uint8_t *)&s_prop_notify},
    },
    [IDX_CHAR_STAT_VAL] = {
        {ESP_GATT_RSP_BY_APP},
        {ESP_UUID_LEN_16, (uint8_t *)&s_uuid_stat, ESP_GATT_PERM_READ,
         sizeof(s_stat_val), 0, s_stat_val},
    },
    [IDX_CHAR_STAT_CFG] = {
        {ESP_GATT_AUTO_RSP},
        {ESP_UUID_LEN_16, (uint8_t *)&s_uuid_ccc,
         ESP_GATT_PERM_READ | ESP_GATT_PERM_WRITE,
         sizeof(uint16_t), sizeof(s_stat_ccc), (uint8_t *)s_stat_ccc},
    },
};

/* 屏 → 手机 的状态通知：0x00 空闲 / 0x01 已连接 / 0x02 收到帧 / 0xFF 校验错误 */
static void notify_status(uint8_t v)
{
    if (!s_connected) return;
    if (!(s_stat_ccc[0] & 0x01)) return;      // 手机没订阅通知就不发
    s_stat_val[0] = v;
    esp_ble_gatts_send_indicate(s_gatts_if, s_conn_id,
                                s_handle_table[IDX_CHAR_STAT_VAL], 1, s_stat_val, false);
}

static void gatts_event_handler(esp_gatts_cb_event_t ev, esp_gatt_if_t iface, esp_ble_gatts_cb_param_t *p)
{
    if (ev == ESP_GATTS_REG_EVT) {
        s_gatts_if = iface;
        esp_ble_gap_set_device_name(DEVICE_NAME);
        esp_ble_gap_config_adv_data(&s_adv_data);
        return;
    }
    if (ev == ESP_GATTS_CREATE_EVT) {
        s_handle_table[IDX_SVC] = p->create.service_handle;
        esp_ble_gatts_create_attr_tab(s_gatt_db, iface, IDX_NUM, IDX_SVC);
        return;
    }

    switch (ev) {
    case ESP_GATTS_CREAT_ATTR_TAB_EVT: {
        if (p->add_attr_tab.status != ESP_GATT_OK) { ESP_LOGE(TAG, "属性表创建失败"); break; }
        memcpy(s_handle_table, p->add_attr_tab.handles, sizeof(uint16_t) * p->add_attr_tab.num_handle);
        esp_ble_gatts_start_service(s_handle_table[IDX_SVC]);
        ESP_LOGI(TAG, "GATT 服务已启动，等待手机连接…");
        break;
    }
    case ESP_GATTS_CONNECT_EVT:
        s_conn_id   = p->connect.conn_id;
        s_connected = true;
        s_nav.connected = true;
        s_p.st = WAIT_SOF1;                    // 新连接重置解析器
        ESP_LOGI(TAG, "手机已连接");
        notify_status(0x01);
        esp_ble_gap_start_advertising(&s_adv_params);   /* 断连后可再连 */
        break;
    case ESP_GATTS_DISCONNECT_EVT:
        s_connected = false;
        s_nav.connected  = false;
        s_nav.navigating = false;
        s_pos.valid      = false;
        s_route_n        = 0;
        ESP_LOGW(TAG, "手机断开，回退到板载 GPS");
        esp_ble_gap_start_advertising(&s_adv_params);
        break;
    case ESP_GATTS_WRITE_EVT: {
        if (p->write.handle == s_handle_table[IDX_CHAR_STAT_CFG]) {
            /* 手机订阅/取消订阅状态通知 */
            s_stat_ccc[0] = p->write.value[0];
            s_stat_ccc[1] = p->write.value[1];
            ESP_LOGI(TAG, "状态通知 %s", (s_stat_ccc[0] & 0x01) ? "已开启" : "已关闭");
        } else {
            feed_bytes(p->write.value, p->write.len);
            notify_status(0x02);
        }
        if (p->write.need_rsp) {
            esp_ble_gatts_send_response(iface, p->write.conn_id, p->write.trans_id, ESP_GATT_OK, NULL);
        }
        break;
    }
    case ESP_GATTS_MTU_EVT:
        ESP_LOGI(TAG, "MTU=%d", p->mtu.mtu);
        break;
    default:
        break;
    }
}

static void gap_event_handler(esp_gap_ble_cb_event_t ev, esp_ble_gap_cb_param_t *p)
{
    if (ev == ESP_GAP_BLE_ADV_DATA_SET_COMPLETE_EVT) esp_ble_gap_start_advertising(&s_adv_params);
}

/* ============================================================
 *  初始化
 * ============================================================ */
void ble_nav_init(void)
{
#if FEAT_BLE_NAV
    esp_bt_controller_config_t bt_cfg = BT_CONTROLLER_INIT_CONFIG_DEFAULT();
    bt_cfg.mode = ESP_BT_MODE_BLE;               /* 只用 BLE，省内存给地图 */

    esp_err_t e;
    e = esp_bt_controller_mem_release(ESP_BT_MODE_CLASSIC);
    if (e != ESP_OK) ESP_LOGW(TAG, "释放经典蓝牙内存：%s", esp_err_to_name(e));
    if ((e = esp_bt_controller_init(&bt_cfg)) != ESP_OK) { ESP_LOGE(TAG, "bt init: %s", esp_err_to_name(e)); return; }
    if ((e = esp_bt_controller_enable(ESP_BT_MODE_BLE)) != ESP_OK) { ESP_LOGE(TAG, "bt enable: %s", esp_err_to_name(e)); return; }
    if ((e = esp_bluedroid_init()) != ESP_OK) { ESP_LOGE(TAG, "bluedroid init: %s", esp_err_to_name(e)); return; }
    if ((e = esp_bluedroid_enable()) != ESP_OK) { ESP_LOGE(TAG, "bluedroid enable: %s", esp_err_to_name(e)); return; }
    ESP_ERROR_CHECK(esp_ble_gatts_register_callback(gatts_event_handler));
    ESP_ERROR_CHECK(esp_ble_gap_register_callback(gap_event_handler));
    ESP_ERROR_CHECK(esp_ble_gatts_app_register(NAVSCREEN_APP_ID));
    ESP_ERROR_CHECK(esp_ble_gatt_set_local_mtu(185));
    ESP_LOGI(TAG, "BLE 导航通道就绪（设备名 %s）", DEVICE_NAME);
#else
    ESP_LOGW(TAG, "BLE 导航未启用（config.h FEAT_BLE_NAV=0）");
#endif
}

const nav_state_t *ble_nav_get(void) { return &s_nav; }
const nav_pos_t   *ble_nav_get_pos(void) { return &s_pos; }

int ble_nav_get_route(const nav_pt_t **pts)
{
    *pts = s_route;
    return s_route_n;
}

bool ble_nav_is_stale(uint32_t timeout_ms)
{
    if (!s_nav.connected) return true;
    uint32_t now = (uint32_t)(esp_timer_get_time() / 1000);
    return (now - s_nav.last_frame_ms) > timeout_ms;
}

bool ble_nav_pos_is_stale(uint32_t timeout_ms)
{
    if (!s_pos.valid) return true;
    uint32_t now = (uint32_t)(esp_timer_get_time() / 1000);
    return (now - s_pos.last_ms) > timeout_ms;
}
