"""轮询 GitHub Actions，等 Android APK 编译出结果（最多等 15 分钟）"""
import json
import time
import urllib.request

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
    if not runs:
        time.sleep(30)
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
