# -*- coding: utf-8 -*-
"""将本地骨科知识库种子写入飞书多维表格。凭证一律从环境变量读取，勿写死在仓库中。"""
import json
import os
import urllib.request
from pathlib import Path

APP_ID = os.environ.get("FEISHU_APP_ID", "").strip()
APP_SECRET = os.environ.get("FEISHU_APP_SECRET", "").strip()
APP_TOKEN = os.environ.get("FEISHU_BITABLE_APP_TOKEN", "").strip()
TABLES = {
    "nodes": os.environ.get("FEISHU_BITABLE_TABLE_NODES", "").strip(),
    "rules": os.environ.get("FEISHU_BITABLE_TABLE_RULES", "").strip(),
    "domains": os.environ.get("FEISHU_BITABLE_TABLE_DOMAINS", "").strip(),
    "replies": os.environ.get("FEISHU_BITABLE_TABLE_REPLIES", "").strip(),
    "briefing": os.environ.get("FEISHU_BITABLE_TABLE_BRIEFING", "").strip(),
}
_ROOT = Path(__file__).resolve().parents[1]
KB = Path(
    os.environ.get(
        "CARELOOP_KB_JSON",
        str(_ROOT / "src" / "main" / "resources" / "kb" / "ortho-common.json"),
    )
)

if not APP_ID or not APP_SECRET or not APP_TOKEN or not all(TABLES.values()):
    raise SystemExit(
        "缺少环境变量。请设置 FEISHU_APP_ID / FEISHU_APP_SECRET / "
        "FEISHU_BITABLE_APP_TOKEN 以及 FEISHU_BITABLE_TABLE_* 后再运行。"
    )


def api(method, url, data=None, token=None):
    body = None if data is None else json.dumps(data, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("Content-Type", "application/json; charset=utf-8")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read().decode("utf-8"))


def list_ids(table, token):
    ids = []
    page = None
    while True:
        url = (
            f"https://open.feishu.cn/open-apis/bitable/v1/apps/{APP_TOKEN}/tables/{table}/records"
            f"?page_size=100"
        )
        if page:
            url += f"&page_token={page}"
        r = api("GET", url, token=token)
        if r.get("code") != 0:
            raise SystemExit(r)
        for it in r["data"].get("items") or []:
            ids.append(it["record_id"])
        if not r["data"].get("has_more"):
            break
        page = r["data"].get("page_token")
    return ids


def batch_delete(table, ids, token):
    for i in range(0, len(ids), 50):
        chunk = ids[i : i + 50]
        if not chunk:
            continue
        r = api(
            "POST",
            f"https://open.feishu.cn/open-apis/bitable/v1/apps/{APP_TOKEN}/tables/{table}/records/batch_delete",
            {"records": chunk},
            token=token,
        )
        print("delete", table, r.get("code"), len(chunk))


def batch_create(table, field_rows, token):
    for i in range(0, len(field_rows), 50):
        chunk = field_rows[i : i + 50]
        records = [{"fields": f} for f in chunk]
        r = api(
            "POST",
            f"https://open.feishu.cn/open-apis/bitable/v1/apps/{APP_TOKEN}/tables/{table}/records/batch_create",
            {"records": records},
            token=token,
        )
        if r.get("code") != 0:
            raise SystemExit({"table": table, "err": r})
        print("create", table, len(chunk))


def join(arr):
    return ",".join(arr)


def main():
    tok = api(
        "POST",
        "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal",
        {"app_id": APP_ID, "app_secret": APP_SECRET},
    )
    token = tok["tenant_access_token"]
    print("token ok")

    with open(KB, "r", encoding="utf-8") as f:
        kb = json.load(f)

    for key, tid in TABLES.items():
        ids = list_ids(tid, token)
        print(key, "existing", len(ids))
        batch_delete(tid, ids, token)

    nodes = [
        {
            "天数": n["day"],
            "标题": n["title"],
            "问题": n["question"],
            "关注域": join(n.get("focus", [])),
            "教练提示": join(n.get("coachTips", [])),
        }
        for n in kb["followupNodes"]
    ]
    batch_create(TABLES["nodes"], nodes, token)

    rules = [
        {
            "级别": r["level"],
            "类别": r["category"],
            "关键词": join(r.get("keywords", [])),
            "原因模板": r.get("reasonTemplate", ""),
        }
        for r in kb["triageRules"]
    ]
    batch_create(TABLES["rules"], rules, token)

    domains = [
        {
            "域ID": d["id"],
            "名称": d["name"],
            "目的": d["why"],
            "绿信号": join(d.get("greenSignals", [])),
            "黄信号": join(d.get("yellowSignals", [])),
            "红信号": join(d.get("redSignals", [])),
        }
        for d in kb["monitoringDomains"]
    ]
    batch_create(TABLES["domains"], domains, token)

    replies = []
    meta = {
        "title": kb["title"],
        "specialty": kb["specialty"],
        "version": kb["version"],
        "disclaimer": kb["disclaimer"],
        "careTips": kb["careTips"],
        "welcomeExtra": kb["welcomeExtra"],
    }
    meta.update(kb["patientReplies"])
    for k, v in meta.items():
        replies.append({"键": k, "内容": v})
    batch_create(TABLES["replies"], replies, token)

    briefing = [
        {"序号": i, "检查项": item}
        for i, item in enumerate(kb["briefingAskList"], 1)
    ]
    batch_create(TABLES["briefing"], briefing, token)

    print(
        "SEED_DONE",
        {
            "nodes": len(nodes),
            "rules": len(rules),
            "domains": len(domains),
            "replies": len(replies),
            "briefing": len(briefing),
        },
    )


if __name__ == "__main__":
    main()
