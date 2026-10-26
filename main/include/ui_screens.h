#pragma once
#include "drivers.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    UI_NAV = 0,      // 导航（主界面）
    UI_DASH,         // 仪表
    UI_SETTINGS,     // 设置
    UI_COUNT
} ui_screen_t;      // 共 3 个界面：无音乐、无来电

/* 初始化全部界面（需在 LVGL 就绪后调用） */
void ui_init(void);

/* 切换界面 */
void ui_show(ui_screen_t s);

/* 周期性刷新（1s 调用一次即可） */
void ui_tick(void);

/* 把旋钮事件交给界面处理 */
void ui_handle_knob(knob_event_t e);

#ifdef __cplusplus
}
#endif
