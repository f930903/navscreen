#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_log.h"
#include "esp_err.h"
#include "nvs_flash.h"
#include "driver/gpio.h"
#include "driver/ledc.h"
#include "esp_lcd_panel_io.h"
#include "esp_lcd_panel_vendor.h"
#include "esp_lcd_panel_ops.h"
#include "esp_lcd_panel_rgb.h"
#include "esp_lvgl_port.h"
#include "lvgl.h"

#include "config.h"
#include "drivers.h"
#include "ble_nav.h"
#include "ui_screens.h"

static const char *TAG = "navscreen";

/* ---------------- 背光（PWM）---------------- */
static void backlight_init(void)
{
    ledc_timer_config_t t = {
        .speed_mode = LEDC_LOW_SPEED_MODE,
        .duty_resolution = LEDC_TIMER_10_BIT,
        .timer_num = LEDC_TIMER_0,
        .freq_hz = 5000,
        .clk_cfg = LEDC_AUTO_CLK,
    };
    ESP_ERROR_CHECK(ledc_timer_config(&t));

    ledc_channel_config_t c = {
        .speed_mode = LEDC_LOW_SPEED_MODE,
        .channel = LEDC_CHANNEL_0,
        .timer_sel = LEDC_TIMER_0,
        .gpio_num = PIN_LCD_BL,
        .duty = 800,               // 约 78%
        .hpoint = 0,
    };
    ESP_ERROR_CHECK(ledc_channel_config(&c));
}

/* ---------------- RGB 并口屏 ---------------- */
static esp_lcd_panel_handle_t display_init(void)
{
    const int data_pins[] = LCD_DATA_GPIO;

    esp_lcd_rgb_panel_config_t cfg = {
        .data_width = 16,
        .psram_trans_align = 64,
        .num_fbs = 2,
        .clk_src = LCD_CLK_SRC_DEFAULT,
        .disp_gpio_num = PIN_LCD_DISP_EN,
        .pclk_gpio_num = PIN_LCD_PCLK,
        .vsync_gpio_num = PIN_LCD_VSYNC,
        .hsync_gpio_num = PIN_LCD_HSYNC,
        .de_gpio_num = PIN_LCD_DE,
        .timings = {
            .pclk_hz = DISP_PCLK_HZ,
            .h_res = DISP_HRES,
            .v_res = DISP_VRES,
            .hsync_pulse_width = LCD_HSYNC_PULSE,
            .hsync_back_porch  = LCD_HSYNC_BACK_PORCH,
            .hsync_front_porch = LCD_HSYNC_FRONT_PORCH,
            .vsync_pulse_width = LCD_VSYNC_PULSE,
            .vsync_back_porch  = LCD_VSYNC_BACK_PORCH,
            .vsync_front_porch = LCD_VSYNC_FRONT_PORCH,
            .flags = { .pclk_active_neg = false },
        },
        .flags = {
            .fb_in_psram = true,     // 帧缓冲放 PSRAM，必须开
            .double_fb = false,
        },
    };
    memcpy(cfg.data_gpio_nums, data_pins, sizeof(data_pins));

    esp_lcd_panel_handle_t panel = NULL;
    ESP_ERROR_CHECK(esp_lcd_new_rgb_panel(&cfg, &panel));
    ESP_ERROR_CHECK(esp_lcd_panel_reset(panel));
    ESP_ERROR_CHECK(esp_lcd_panel_init(panel));
    ESP_LOGI(TAG, "RGB 屏 %dx%d 初始化完成", DISP_HRES, DISP_VRES);
    return panel;
}

/* ---------------- 触摸（可选）---------------- */
static void touch_init(void)
{
#if TOUCH_ENABLE
    /* 若屏带 I2C 触摸（如 GT911 / CST816S），用 esp_lcd_touch 组件接入：
     *   esp_lcd_touch_config_t tp_cfg = { .x_max=DISP_HRES, .y_max=DISP_VRES, ... };
     *   esp_lcd_touch_new_i2c_gt911(io_handle, &tp_cfg, &tp);
     *   lvgl_port_add_touch(tp);
     * 具体型号按屏厂资料替换。未接入时旋钮即可完成全部操作。 */
    ESP_LOGW(TAG, "触摸未接入（如需请补 esp_lcd_touch 初始化）");
#endif
}

/* ---------------- UI 刷新任务 ---------------- */
static void ui_task(void *arg)
{
    ui_init();
    while (1) {
        knob_event_t e = knob_poll();
        if (e != KNOB_NONE) {
            lvgl_port_lock(0);
            ui_handle_knob(e);
            lvgl_port_unlock();
        }

        static int sec = 0;
        if (++sec >= 10) {          // 约 1s（本任务 100ms 周期）
            sec = 0;
            lvgl_port_lock(0);
            ui_tick();
            lvgl_port_unlock();
        }
        vTaskDelay(pdMS_TO_TICKS(100));
    }
}

void app_main(void)
{
    ESP_LOGI(TAG, "圆形导航屏启动 · ESP32-S3 · %dx%d", DISP_HRES, DISP_VRES);

    ESP_ERROR_CHECK(nvs_flash_init());

    /* 1. 显示 + LVGL */
    esp_lcd_panel_handle_t panel = display_init();
    backlight_init();

    const lvgl_port_cfg_t lvgl_cfg = ESP_LVGL_PORT_INIT_CONFIG();
    ESP_ERROR_CHECK(lvgl_port_init(&lvgl_cfg));

    const lvgl_port_display_cfg_t disp_cfg = {
        .io_handle = NULL,            // RGB 屏无需 IO 句柄
        .panel_handle = panel,
        .buffer_size = DISP_HRES * 40,
        .double_buffer = true,
        .hres = DISP_HRES,
        .vres = DISP_VRES,
        .monochrome = false,
        .color_format = LV_COLOR_FORMAT_RGB565,
        .rotation = {
            .swap_xy = false,
            .mirror_x = false,
            .mirror_y = false,
        },
        .flags = {
            .buff_dma = true,
            .swap_bytes = false,
        },
    };
    lvgl_port_add_disp(&disp_cfg);
    touch_init();

    /* 2. 外设 */
    gps_init();
    knob_init();
    sd_init();
    ble_nav_init();          /* BLE 导航通道：手机端 App 推送转向/距离/路名 */
    /* 经典蓝牙与音频已按需求移除：不做音乐、来电、语音播报 */

    /* 3. 起 UI 任务 */
    xTaskCreatePinnedToCore(ui_task, "ui", 8192, NULL, 6, NULL, 1);

    ESP_LOGI(TAG, "系统就绪");
}
