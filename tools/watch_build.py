"""轮询 GitHub Actions，等 Android APK 编译出结果（最多等 15 分钟）"""
import json
import sys
import time
import urllib.request

# 可选参数：只统计这个时间点之后创建的运行（避免读到上一次的旧结果）
SINCE = sys.argv[1] if len(sys.argv) > 1 else None

API = "https://api.github.com/repos/f930903/navscreen/actions/runs?per_page=6"
UA = {"User-Agent": "navscreen-watcher", "Accept": "application/vnd.github+json"}


def poll():
    with urllib.request.urlopen(urllib.request.Request(API, headers=UA), timeout=20) as r:
        return json.load(r)


for i in range(30):
    try:
        data = poll()
    except Exception as e:
        print("查询失败：", e)
        time.sleep(30)
        continue

    runs = [r for r in data.get("workflow_runs", []) if r["name"] == "Android APK"]
    if SINCE:
        runs = [r for r in runs if r["created_at"] >= SINCE]
    if not runs:
        print(f"[{i*30}s] 还没有新的运行记录，继续等…")
        time.sleep(20)
        continue
    run = runs[0]  # 列表按时间倒序，第一条就是最新一次运行
    status, concl = run["status"], run["conclusion"]
    print(f"[{i*30}s] Android APK(#{run['run_number']}): status={status} conclusion={concl}")
    if status == "completed":
        print("=== 结束 ===")
        print("结论:", concl)
        print("日志:", run["html_url"])
        if concl == "success":
            print("APK 下载:", run["html_url"] + "#artifacts")
        raise SystemExit(0)
    time.sleep(30)

print("超时：15 分钟内未完成")
