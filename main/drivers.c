#include "drivers.h"
#include "config.h"

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/queue.h"
#include "driver/uart.h"
#include "driver/gpio.h"
#include "driver/spi_master.h"
#include "driver/sdspi_host.h"
#include "sdmmc_cmd.h"
#include "esp_vfs_fat.h"
#include "esp_log.h"
#include "esp_timer.h"

#include <stdio.h>
#include <string.h>
#include <stdlib.h>

static const char *TAG = "drivers";

/* ============================================================
 *  GPS：UART 接收 + 最小 NMEA 解析（GNRMC / GNGGA）
 * ============================================================ */
#if FEAT_GPS

static gps_state_t s_gps = {0};

/* NMEA 语句按逗号取第 n 个字段 */
static const char *nmea_field(const char *line, int idx, char *out, size_t outsz)
{
    int cur = 0;
    const char *p = line;
    while (*p) {
        if (cur == idx) {
            const char *end = strchr(p, ',');
            size_t len = end ? (size_t)(end - p) : strlen(p);
            if (len >= outsz) len = outsz - 1;
            memcpy(out, p, len);
            out[len] = 0;
            return out;
        }
        if (*p == ',') cur++;
        p++;
    }
    out[0] = 0;
    return out;
}

/* 度分格式 (ddmm.mmmm) → 十进制度 */
static double dm2deg(const char *s)
{
    if (!s || !*s) return 0;
    double v = atof(s);
    int deg = (int)(v / 100);
    double min = v - deg * 100.0;
    return deg + min / 60.0;
}

static void parse_line(char *line)
{
    char f[32];

    if (strstr(line, "RMC")) {
        nmea_field(line, 2, f, sizeof(f));            // 状态 A/V
        bool valid = (f[0] == 'A');

        nmea_field(line, 1, f, sizeof(f));            // UTC 时间 hhmmss
        if (strlen(f) >= 6) {
            int hh = (f[0] - '0') * 10 + (f[1] - '0');
            int mm = (f[2] - '0') * 10 + (f[3] - '0');
            s_gps.hour = (hh + 8) % 24;                // 转北京时间
            s_gps.minute = mm;
        }
        if (valid) {
            nmea_field(line, 3, f, sizeof(f));
            s_gps.lat = dm2deg(f);
            nmea_field(line, 4, f, sizeof(f));
            if (f[0] == 'S') s_gps.lat = -s_gps.lat;

            nmea_field(line, 5, f, sizeof(f));
            s_gps.lon = dm2deg(f);
            nmea_field(line, 6, f, sizeof(f));
            if (f[0] == 'W') s_gps.lon = -s_gps.lon;

            nmea_field(line, 7, f, sizeof(f));
            s_gps.speed_kmh = atof(f) * 1.852f;        // 节 → km/h

            nmea_field(line, 8, f, sizeof(f));
            s_gps.course = atof(f);
        }
        s_gps.valid = valid;
    } else if (strstr(line, "GGA")) {
        nmea_field(line, 7, f, sizeof(f));
        s_gps.sats = atoi(f);
    }
}

static void gps_task(void *arg)
{
    static char line[256];
    size_t idx = 0;
    uint8_t buf[128];

    while (1) {
        int len = uart_read_bytes(GPS_UART_NUM, buf, sizeof(buf) - 1, pdMS_TO_TICKS(200));
        if (len > 0) {
            for (int i = 0; i < len; i++) {
                char c = (char)buf[i];
                if (c == '\n' || c == '\r') {
                    if (idx > 0) { line[idx] = 0; parse_line(line); idx = 0; }
                } else if (idx < sizeof(line) - 1) {
                    line[idx++] = c;
                }
            }
        }
#if FEAT_DEMO_MOVE
        /* 无 GPS 信号时，用模拟数据让界面动起来（调试用，装车前关掉） */
        if (!s_gps.valid) {
            static uint32_t t = 0;
            t++;
            s_gps.speed_kmh = 30 + (t % 9);
            s_gps.course = (t * 3) % 360;
        }
#endif
    }
}

void gps_init(void)
{
    uart_config_t cfg = {
        .baud_rate  = GPS_UART_BAUD,
        .data_bits  = UART_DATA_8_BITS,
        .parity     = UART_PARITY_DISABLE,
        .stop_bits  = UART_STOP_BITS_1,
        .flow_ctrl  = UART_HW_FLOWCTRL_DISABLE,
        .source_clk = UART_SCLK_DEFAULT,
    };
    ESP_ERROR_CHECK(uart_driver_install(GPS_UART_NUM, 1024, 0, 0, NULL, 0));
    ESP_ERROR_CHECK(uart_param_config(GPS_UART_NUM, &cfg));
    ESP_ERROR_CHECK(uart_set_pin(GPS_UART_NUM, PIN_GPS_RX, PIN_GPS_TX,
                                 UART_PIN_NO_CHANGE, UART_PIN_NO_CHANGE));

    xTaskCreatePinnedToCore(gps_task, "gps", 3072, NULL, 5, NULL, 1);
    ESP_LOGI(TAG, "GPS UART%d @%d 已启动", GPS_UART_NUM, GPS_UART_BAUD);
}

const gps_state_t *gps_get(void) { return &s_gps; }

#else

static gps_state_t s_gps = { .valid = false, .speed_kmh = 0 };
void gps_init(void) {}
const gps_state_t *gps_get(void) { return &s_gps; }

#endif /* FEAT_GPS */

/* ============================================================
 *  EC11 旋钮：A/B 相正交解码 + 按压/长按
 * ============================================================ */
typedef struct {
    int      a_gpio, b_gpio, sw_gpio;
    int      last;
    int      dir_acc;
    int64_t  press_start;
    bool     sw_down;
} knob_ctx_t;

static knob_ctx_t s_knob = {
    .a_gpio = PIN_KNOB_A, .b_gpio = PIN_KNOB_B, .sw_gpio = PIN_KNOB_SW,
    .last = -1, .dir_acc = 0, .press_start = 0, .sw_down = false
};

/* 正交解码：返回 +1(顺时针) / -1(逆时针) / 0 */
static int quad_decode(int a, int b)
{
    static const int table[16] = {
         0, -1,  1,  0,
         1,  0,  0, -1,
        -1,  0,  0,  1,
         0,  1, -1,  0
    };
    static int prev = 0;
    int cur = (a << 1) | b;
    int idx = (prev << 2) | cur;
    prev = cur;
    return table[idx & 0x0F];
}

static void knob_task(void *arg)
{
    gpio_config_t io = {
        .pin_bit_mask = (1ULL << s_knob.a_gpio) | (1ULL << s_knob.b_gpio) | (1ULL << s_knob.sw_gpio),
        .mode = GPIO_MODE_INPUT,
        .pull_up_en = GPIO_PULLUP_ENABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };
    gpio_config(&io);

    int64_t now;
    while (1) {
        int a = gpio_get_level(s_knob.a_gpio);
        int b = gpio_get_level(s_knob.b_gpio);
        int d = quad_decode(a, b);
        if (d != 0) s_knob.dir_acc += d;

        /* 按压（低电平按下），带 20ms 消抖 */
        int sw = gpio_get_level(s_knob.sw_gpio);
        now = esp_timer_get_time() / 1000;
        if (sw == 0 && !s_knob.sw_down) { s_knob.sw_down = true; s_knob.press_start = now; }
        else if (sw == 1 && s_knob.sw_down) {
            s_knob.sw_down = false;
            if (now - s_knob.press_start >= 800) {
                /* 长按：独占标记，poll 时取走 */
                s_knob.dir_acc = 0;
                s_knob.last = 2;               // 2 = 长按
            } else if (now - s_knob.press_start > 20) {
                s_knob.last = 1;               // 1 = 短按
            }
        }
        vTaskDelay(pdMS_TO_TICKS(5));
    }
}

void knob_init(void)
{
    xTaskCreatePinnedToCore(knob_task, "knob", 2048, NULL, 5, NULL, 1);
}

knob_event_t knob_poll(void)
{
    if (s_knob.last != -1) {
        knob_event_t e = (s_knob.last == 2) ? KNOB_LONG : KNOB_CLICK;
        s_knob.last = -1;
        return e;
    }
    if (s_knob.dir_acc >= 4)      { s_knob.dir_acc = 0; return KNOB_CW;  }
    if (s_knob.dir_acc <= -4)     { s_knob.dir_acc = 0; return KNOB_CCW; }
    return KNOB_NONE;
}

/* ============================================================
 *  SD 卡（SPI）+ 地图瓦片读取
 * ============================================================ */
static sdmmc_card_t *s_card = NULL;
static bool s_sd_ok = false;

bool sd_init(void)
{
#if FEAT_SD
    esp_vfs_fat_sdmmc_mount_config_t mount = {
        .format_if_mount_failed = false,
        .max_files = 5,
        .allocation_unit_size = 16 * 1024,
    };
    spi_bus_config_t bus = {
        .mosi_io_num = PIN_SD_MOSI,
        .miso_io_num = PIN_SD_MISO,
        .sclk_io_num = PIN_SD_CLK,
        .quadwp_io_num = -1,
        .quadhd_io_num = -1,
        .max_transfer_sz = 4000,
    };
    esp_err_t ret = spi_bus_initialize(SPI2_HOST, &bus, SPI_DMA_CH_AUTO);
    if (ret != ESP_OK) { ESP_LOGE(TAG, "SPI 初始化失败: %s", esp_err_to_name(ret)); return false; }

    sdspi_device_config_t slot = SDSPI_DEVICE_CONFIG_DEFAULT();
    slot.gpio_cs = PIN_SD_CS;
    slot.host_id = SPI2_HOST;

    ret = esp_vfs_fat_sdspi_mount(SD_MOUNT_POINT, &slot, &mount, &s_card);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "SD 卡挂载失败: %s（离线地图不可用，界面仍可跑）", esp_err_to_name(ret));
        return false;
    }
    s_sd_ok = true;
    ESP_LOGI(TAG, "SD 卡已挂载到 %s", SD_MOUNT_POINT);
    return true;
#else
    return false;
#endif
}

bool map_load_tile(int z, int x, int y, uint16_t *out_buf, int buf_px)
{
    if (!s_sd_ok) return false;

    char path[128];
    snprintf(path, sizeof(path), "%s/%d/%d_%d.raw", MAP_TILE_ROOT, z, x, y);

    FILE *f = fopen(path, "rb");
    if (!f) return false;

    size_t want = TILE_SIZE * TILE_SIZE * sizeof(uint16_t);
    if ((int)(want / sizeof(uint16_t)) > buf_px) { fclose(f); return false; }
    size_t got = fread(out_buf, 1, want, f);
    fclose(f);
    return (got == want);
}
