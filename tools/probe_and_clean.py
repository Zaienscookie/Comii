#!/usr/bin/env python3
"""探测各作者真实可获取帖数 + 清理 bontiage 重复目录"""
import urllib.request, json, os, shutil, sys
UA='Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'

def gj(u):
    req=urllib.request.Request(u,headers={'User-Agent':UA,'Accept':'application/json'})
    try:
        with urllib.request.urlopen(req,timeout=8) as r: return json.loads(r.read())
    except Exception as e: return ('ERR',str(e)[:40])

AUTHORS=[("patreon","3537817","bontiage"),("patreon","75879791","eljotafurro"),
        ("fanbox","90970116","MOO2"),("patreon","17838557","Alec")]

print("=== 作者真实可获取量(首页帖数+唯一ID) ===")
for svc,uid,name in AUTHORS:
    d=gj(f'https://pawchive.pw/api/v1/{svc}/user/{uid}')
    if isinstance(d,tuple):
        print(f'{name}: ERR {d[1]}'); continue
    ids=[str(x.get('id')) for x in d]
    uniq=len(set(ids))
    # 统计图片数/压缩包数
    imgs=0; arch=0
    for x in d:
        f=x.get('file') or {}
        if f.get('path'):
            nm=str(f.get('name','')).lower()
            if nm.endswith(('.zip','.7z','.rar','.tar','.gz')): arch+=1
            else: imgs+=1
        for a in (x.get('attachments') or []):
            an=str(a.get('name','')).lower()
            if an.endswith(('.zip','.7z','.rar','.tar','.gz')): arch+=1
            elif a.get('path'): imgs+=1
    print(f'{name} ({svc}/{uid}): 首页{len(d)}帖 唯一{uniq} | 图片{imgs} 压缩包{arch}')

# 清理 bontiage 重复目录: 按标题去重, 保留编号最小的
print("\n=== 清理 bontiage 重复 ===")
d='/srv/pawchive/bontiage'
if os.path.isdir(d):
    groups={}
    for e in sorted(os.listdir(d)):
        num,sep,title=e.partition('_')
        if not sep: continue
        groups.setdefault(title,[]).append(e)
    dup=0; kept=0
    for title,entries in groups.items():
        keep=min(entries,key=lambda x:int(x.split('_')[0]) if x.split('_')[0].isdigit() else 1<<30)
        kept+=1
        for e in entries:
            if e!=keep:
                shutil.rmtree(os.path.join(d,e),ignore_errors=True); dup+=1
    print(f'保留 {kept} 帖(唯一标题), 删除 {dup} 重复目录')
    print('清理后帖数:', len(os.listdir(d)))
else:
    print('目录不存在')
