#!/usr/bin/env python3
"""pawchive 5 纯图作者批量下载 (多线程+代理+断点续传)
Evil_Rakashaka(7z视频包为主)按用户要求跳过"""
import subprocess, time, shutil

AUTHORS = [
    ("patreon", "75879791",  "eljotafurro",  566),
    ("fanbox",  "90970116",  "MOO2",          63),
    ("patreon", "17838557",  "Alec",          37),
    # bontiage(75帖) 已完成; Lost_Proxy 按用户要求剔除
]
BASE = "/srv/pawchive"
THREADS = 10
DLY = 0.15
PROXY = "http://127.0.0.1:7890"
SCRIPT = "/home/debug/qqbot/qqbot/new-zaiens/astrbot/data/workspaces/zaiens_GroupMessage_1103115281/pawchive_dl_threaded.py"

def free_gb():
    return shutil.disk_usage("/srv").free / 1024**3

print(f"===== 批量下载开始 [{time.strftime('%H:%M:%S')}] 可用 {free_gb():.2f}G =====", flush=True)
for svc, uid, name, mp in AUTHORS:
    free = free_gb()
    if free < 1.0:
        print(f"[停止] 空间不足 {free:.2f}G, 中断", flush=True)
        break
    print(f"\n===== [{time.strftime('%H:%M:%S')}] {name} ({svc}/{uid} max={mp}) | 可用 {free:.2f}G =====", flush=True)
    r = subprocess.run(
        ["python3", SCRIPT, svc, uid, name, BASE, str(mp), str(THREADS), str(DLY), PROXY],
        capture_output=True, text=True, timeout=7200
    )
    lines = (r.stdout + r.stderr).strip().splitlines()
    print("\n".join(lines[-8:]), flush=True)
    print(f"===== [{time.strftime('%H:%M:%S')}] {name} 结束 rc={r.returncode} | 可用 {free_gb():.2f}G =====", flush=True)

print(f"\n===== 全部完成 [{time.strftime('%H:%M:%S')}] 最终可用 {free_gb():.2f}G =====", flush=True)
