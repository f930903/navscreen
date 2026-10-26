#pragma once
#include <stdint.h>

/* ============================================================
 * 圆形导航屏 · 硬件配置
 * ⚠️ 唯一需要你按实际硬件修改的文件
 * ============================================================ */

/* ---------- 显示屏（3.4" 圆形 480×480，RGB565 并口）---------- */
#define DISP_HRES           480
#define DISP_VRES           480
#define DISP_PCLK_HZ        (12 * 1000 * 1000)   // 屏厂给的参数，跑不动就降到 8MHz

/* RGB 并口引脚：B0..B4 / G0..G5 / R0..R4 共 16 根（顺序按你的屏定义改） */
#define PIN_LCD_DE          40
#define PIN_LCD_VSYNC       41
#define PIN_LCD_HSYNC       39
#define PIN_LCD_PCLK       42
#define PIN_LCD_DISP_EN     (-1)   // 有的屏有使能脚，没有就填 -1
#define PIN_LCD_BL          4      // 背光 PWM
#define LCD_DATA_GPIO { \
    47, 48, 45, 38, 9,   /* B0..B4 */ \
    10, 11, 12, 13, 14, 21,  /* G0..G5 */ \
    35, 36, 37, 16, 15   /* R0..R4 */ \
}

/* 屏时序（务必用屏厂资料替换，这里给的是 480×480 的常用值） */
#define LCD_HSYNC_PULSE     8
#define LCD_HSYNC_BACK_PORCH 16
#define LCD_HSYNC_FRONT_PORCH 16
#define LCD_VSYNC_PULSE     4
#define LCD_VSYNC_BACK_PORCH 12
#define LCD_VSYNC_FRONT_PORCH 12

/* 触摸（I2C CTP），没有触摸就把 TOUCH_ENABLE 置 0 */
#define TOUCH_ENABLE        1
#define PIN_TOUCH_SDA       8
#define PIN_TOUCH_SCL       18
#define PIN_TOUCH_INT       (-1)

/* ---------- GPS（UART，9600bps）---------- */
#define PIN_GPS_TX          17     // 模块 TX → ESP32 RX
#define PIN_GPS_RX          18     // 模块 RX ← ESP32 TX
#define GPS_UART_BAUD       9600
#define GPS_UART_NUM        UART_NUM_1

/* ---------- EC11 旋钮（旋转编码器 + 按压）---------- */
#define PIN_KNOB_A          1
#define PIN_KNOB_B          2
#define PIN_KNOB_SW         3      // 按压开关，长按 800ms = 回主界面

/* ---------- 音频：已取消（不带喇叭，不做播报）---------- */
/* 如日后想加语音播报，再启用下面三个脚并接 MAX98357A
#define PIN_I2S_BCLK        5
#define PIN_I2S_LRCK        6
#define PIN_I2S_DOUT        7
*/

/* ---------- microSD（SPI）---------- */
#define SD_MOUNT_POINT      "/sdcard"
#define PIN_SD_MOSI         36
#define PIN_SD_MISO         37
#define PIN_SD_CLK          34
#define PIN_SD_CS           33

/* ---------- 罗盘（I2C，与触摸共用总线或另开）---------- */
#define COMPASS_ENABLE      0
#define PIN_COMPASS_SDA     8
#define PIN_COMPASS_SCL     18

/* ---------- 地图瓦片 ---------- */
/* 瓦片为 RGB565 原始二进制，文件名格式 /sdcard/tiles/<z>/<x>_<y>.raw */
#define TILE_SIZE           256
#define MAP_TILE_ROOT       "/sdcard/tiles"

/* ---------- 功能开关 ---------- */
#define FEAT_GPS            1
#define FEAT_SD             1
#define FEAT_BLE_NAV        1     // BLE 导航通道：手机推导航指令给屏（第三条路线）
#define FEAT_BT             0     // 经典蓝牙（音乐/通话）已按需求永久关闭
#define FEAT_AUDIO          0     // 不带喇叭，音频关闭
#define FEAT_DEMO_MOVE      1     // 没有 GPS 信号时用模拟数据演示（调试用，装车前关掉）
