# 推送到 GitHub 并自动编译 APK

代码已经在本地建好仓库、提交完成，**只差两步授权**（涉及账号，必须你本人操作）。

## 第 1 步：把 SSH 公钥加到 GitHub

公钥已复制到剪贴板，内容是这一行：

```
ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIEse2c8KiQP1UIl/ALP96HPRYJOFNl2OBqgxV8wAvvtn abin-navscreen
```

1. 浏览器打开 https://github.com/settings/ssh/new
2. Title 随便填（比如 `WorkBuddy`），Key 类型保持 **Authentication Key**
3. Key 框里粘贴上面那行（已复制，直接 Ctrl+V）
4. 点 **Add SSH key**（可能要输一次 GitHub 密码确认）

## 第 2 步：在 GitHub 上建一个空仓库

1. 打开 https://github.com/new
2. Repository name 填 `navscreen`（或你喜欢的名字）
3. 选 **Public** 或 Private 都行
4. ⚠️ **不要**勾选 "Add a README file"、"Add .gitignore"、"Choose a license" —— 保持完全空白
5. 点 **Create repository**

## 第 3 步：把仓库地址发给我

建好后页面上会显示一个 SSH 地址，形如：

```
git@github.com:你的用户名/navscreen.git
```

把这个地址（或者只告诉我你的 GitHub 用户名）发过来，我执行剩下的：

```bash
git remote add origin git@github.com:用户名/navscreen.git
git branch -M main
git push -u origin main
```

推完约 3~5 分钟，Actions 会自动编译出 APK。

## 查看编译结果

打开 `https://github.com/用户名/navscreen/actions` → 点最新的 `Android APK` 运行记录
→ 页面最下方 **Artifacts** 区 → 下载 `navscreen-lite-apk` → 解压得到 `app-lite-debug.apk`
→ 传到手机安装（需允许「未知来源」安装）。

---

## 备选方案：不想配 SSH 也能做

直接在 GitHub 网页操作：新建空仓库 → 页面上点 **"uploading an existing file"** →
把 `navscreen-firmware` 里的 `android`、`.github`、`main`、`docs`、`tools` 这几个文件夹
和根目录的文件拖进去 → 提交。同样会触发 Actions 编译。

（缺点：后续改代码要重新手动传，没有 SSH 方便。）

## 隐私说明

`.gitignore` 已经把高德的 `.aar` 文件排除掉了——那些是商业 SDK，不能公开上传。
你之后下载的高德 aar 放在本地 `android/app/libs/` 里不会被推上去。
