#!/usr/bin/env python3
"""LuminaVault MCP server 冒烟测试（纯标准库，零依赖）。

用法：
    # 只验证协议层（initialize / tools/list / ping），不需要后端
    python mcp-server/smoke_test.py

    # 端到端：连着真实后端跑一遍读写工具
    LUMINA_VAULT_API_KEY=lv_xxx python mcp-server/smoke_test.py
    # Windows PowerShell:
    #   $env:LUMINA_VAULT_API_KEY="lv_xxx"; python mcp-server/smoke_test.py
    # Windows cmd:
    #   set LUMINA_VAULT_API_KEY=lv_xxx && python mcp-server/smoke_test.py

环境变量：
    LUMINA_VAULT_URL       后端地址，默认 http://localhost:8080
    LUMINA_VAULT_API_KEY   API 密钥；设置后才会跑读写工具（会临时上传一张图并彻底删除）
    LUMINA_VAULT_TIMEOUT   单次请求超时秒数，默认 30
    LUMINA_VAULT_MCP_PY    覆盖被测试的 MCP server 脚本路径

退出码：0 全部通过；1 有失败项。
"""

from __future__ import annotations

import json
import os
import random
import struct
import subprocess
import sys
import tempfile
import zlib
from pathlib import Path

HERE = Path(__file__).resolve().parent
SERVER = Path(os.environ.get("LUMINA_VAULT_MCP_PY", HERE / "lumina_vault_mcp.py"))

EXPECTED_TOOLS = {
    "get_account",
    "get_stats",
    "list_assets",
    "search_assets",
    "semantic_search",
    "search_by_image",
    "get_asset",
    "get_asset_image",
    "upload_asset",
    "update_asset",
    "delete_asset",
    "list_trash",
    "restore_asset",
    "purge_asset",
    "find_similar",
    "list_folders",
    "create_folder",
    "delete_folder",
    "assign_folder",
    "list_characters",
    "enrich_asset",
}

passed: list[str] = []
failed: list[str] = []


def check(label: str, ok: bool, detail: str = "") -> None:
    if ok:
        passed.append(label)
        print(f"  [PASS] {label}{' — ' + detail if detail else ''}")
    else:
        failed.append(label)
        print(f"  [FAIL] {label}{' — ' + detail if detail else ''}")


class McpServer:
    """以子进程方式驱动 MCP server，按行收发 JSON-RPC 消息。"""

    def __init__(self) -> None:
        env = os.environ.copy()
        env.setdefault("PYTHONIOENCODING", "utf-8")
        self.proc = subprocess.Popen(  # noqa: S603 - 固定解释器 + 固定脚本
            [sys.executable, str(SERVER)],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=env,
            text=True,
            encoding="utf-8",
            bufsize=1,
        )
        self.next_id = 0

    def _read_message(self, want_id: int) -> dict:
        assert self.proc.stdout is not None
        while True:
            line = self.proc.stdout.readline()
            if not line:
                err = (self.proc.stderr.read() if self.proc.stderr else "") or ""
                raise RuntimeError(f"MCP server 提前退出，stderr：{err[-2000:]}")
            line = line.strip()
            if not line:
                continue
            try:
                message = json.loads(line)
            except json.JSONDecodeError:
                raise RuntimeError(f"MCP server 输出了非法 JSON：{line[:300]}") from None
            if message.get("id") == want_id:
                return message

    def request(self, method: str, params: dict | None = None) -> dict:
        self.next_id += 1
        request_id = self.next_id
        payload = {"jsonrpc": "2.0", "id": request_id, "method": method}
        if params is not None:
            payload["params"] = params
        assert self.proc.stdin is not None
        self.proc.stdin.write(json.dumps(payload, ensure_ascii=False) + "\n")
        self.proc.stdin.flush()
        message = self._read_message(request_id)
        if "error" in message:
            raise RuntimeError(f"{method} 返回错误：{message['error']}")
        return message["result"]

    def notify(self, method: str, params: dict | None = None) -> None:
        payload = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            payload["params"] = params
        assert self.proc.stdin is not None
        self.proc.stdin.write(json.dumps(payload, ensure_ascii=False) + "\n")
        self.proc.stdin.flush()

    def call_tool(self, name: str, arguments: dict) -> dict:
        return self.request("tools/call", {"name": name, "arguments": arguments})

    def close(self) -> None:
        try:
            if self.proc.stdin:
                self.proc.stdin.close()
            self.proc.wait(timeout=10)
        except Exception:  # noqa: BLE001 - 收尾阶段尽力而为
            self.proc.kill()


def json_payload(result: dict) -> object:
    """从 tools/call 结果里取出第一个 text 内容并解析为 JSON。"""
    for item in result.get("content", []):
        if item.get("type") == "text":
            try:
                return json.loads(item.get("text", ""))
            except json.JSONDecodeError:
                return item.get("text")
    raise AssertionError("工具结果里没有 text 内容")


def assert_ok(result: dict, label: str) -> object:
    if result.get("isError"):
        raise AssertionError(f"{label} 返回 isError=true：{json_payload(result)}")
    return json_payload(result)


def make_png(width: int = 12, height: int = 12) -> bytes:
    """生成一张随机纯色 PNG（每次内容不同，避免 sha256 去重命中旧资产）。"""

    def chunk(tag: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    color = bytes([random.randrange(256), random.randrange(256), random.randrange(256)])
    raw = b"".join(b"\x00" + color * width for _ in range(height))
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw))
        + chunk(b"IEND", b"")
    )


def run() -> int:
    base_url = os.environ.get("LUMINA_VAULT_URL", "http://localhost:8080")
    api_key = os.environ.get("LUMINA_VAULT_API_KEY", "").strip()

    print(f"LuminaVault MCP 冒烟测试")
    print(f"  脚本   : {SERVER}")
    print(f"  后端   : {base_url}")
    print(f"  密钥   : {'已提供' if api_key else '未提供（仅协议层测试）'}")
    print()

    if not SERVER.is_file():
        print(f"找不到 MCP server 脚本：{SERVER}")
        return 1

    server = McpServer()
    uploaded_id: int | None = None
    try:
        print("协议层：")
        init = server.request(
            "initialize",
            {
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "clientInfo": {"name": "lumina-vault-smoke", "version": "1.0"},
            },
        )
        server.notify("notifications/initialized")
        check("initialize 返回 serverInfo", init.get("serverInfo", {}).get("name") == "lumina-vault",
              json.dumps(init.get("serverInfo", {}), ensure_ascii=False))
        check("initialize 声明 tools 能力", "tools" in init.get("capabilities", {}))

        ping = server.request("ping")
        check("ping 返回空结果", ping == {})

        listing = server.request("tools/list")
        tools = listing.get("tools", [])
        names = {tool.get("name") for tool in tools}
        check(f"tools/list 至少 21 个工具（实际 {len(tools)}）", len(tools) >= 21)
        missing = EXPECTED_TOOLS - names
        check("工具集合完整", not missing, f"缺少 {sorted(missing)}" if missing else "")
        check(
            "每个工具都有 name/description/inputSchema",
            all(t.get("name") and t.get("description") and isinstance(t.get("inputSchema"), dict) for t in tools),
        )

        if not api_key:
            print()
            print("未提供 LUMINA_VAULT_API_KEY，跳过读写工具测试。")
            print("在网站右上角「API 密钥」抽屉里创建密钥后重跑，可做端到端验证。")
            return 0 if not failed else 1

        print()
        print("只读工具：")
        account = assert_ok(server.call_tool("get_account", {}), "get_account")
        check("get_account 返回用户名与 scopes",
              isinstance(account, dict) and bool(account.get("username")) and isinstance(account.get("scopes"), list),
              json.dumps(account, ensure_ascii=False)[:160])

        stats = assert_ok(server.call_tool("get_stats", {}), "get_stats")
        check("get_stats 返回资产计数", isinstance(stats, dict) and "assets" in stats,
              json.dumps(stats, ensure_ascii=False)[:160])

        folders = assert_ok(server.call_tool("list_folders", {}), "list_folders")
        check("list_folders 返回列表", isinstance(folders, list), f"{len(folders)} 个文件夹" if isinstance(folders, list) else "")

        listing_result = assert_ok(server.call_tool("list_assets", {"page": 1, "size": 3}), "list_assets")
        check("list_assets 返回分页结构", isinstance(listing_result, dict) and "records" in listing_result,
              f"total={listing_result.get('total')}" if isinstance(listing_result, dict) else "")

        search_result = assert_ok(server.call_tool("search_assets", {"filename": "", "size": 3}), "search_assets")
        check("search_assets 返回分页结构", isinstance(search_result, dict) and "records" in search_result)

        can_write = isinstance(account, dict) and "write" in (account.get("scopes") or [])
        if not can_write:
            print()
            print("该密钥没有 write 权限，跳过写工具测试。")
            return 0 if not failed else 1

        print()
        print("写工具（临时上传一张随机 PNG，测试结束会彻底删除）：")
        with tempfile.TemporaryDirectory() as tmp:
            image_path = Path(tmp) / "lumina-mcp-smoke.png"
            image_path.write_bytes(make_png())
            uploaded = assert_ok(
                server.call_tool("upload_asset", {"path": str(image_path), "description": "MCP 冒烟测试资产"}),
                "upload_asset",
            )
            if isinstance(uploaded, dict):
                uploaded_id = uploaded.get("id")
            check("upload_asset 返回资产 id", isinstance(uploaded_id, int),
                  json.dumps(uploaded, ensure_ascii=False)[:200] if uploaded else "")

        if isinstance(uploaded_id, int):
            detail = assert_ok(server.call_tool("get_asset", {"id": uploaded_id}), "get_asset")
            check("get_asset 命中刚上传的资产",
                  isinstance(detail, dict) and detail.get("id") == uploaded_id,
                  (detail or {}).get("originalFilename", "") if isinstance(detail, dict) else "")

            image_result = server.call_tool("get_asset_image", {"id": uploaded_id, "thumbnail": True})
            image_items = [item for item in image_result.get("content", []) if item.get("type") == "image"]
            check("get_asset_image 返回内联图片", bool(image_items) and not image_result.get("isError"),
                  f"{len(image_items)} 张，mime={image_items[0].get('mimeType') if image_items else '-'}")

            updated = assert_ok(
                server.call_tool("update_asset", {"id": uploaded_id, "description": "MCP 冒烟测试资产（已更新）"}),
                "update_asset",
            )
            check("update_asset 生效",
                  isinstance(updated, dict) and updated.get("description", "").endswith("（已更新）"))

            similar = assert_ok(server.call_tool("find_similar", {"id": uploaded_id, "limit": 3}), "find_similar")
            check("find_similar 返回列表", isinstance(similar, list), f"{len(similar)} 个邻居" if isinstance(similar, list) else "")

            assert_ok(server.call_tool("delete_asset", {"id": uploaded_id}), "delete_asset")
            trash = assert_ok(server.call_tool("list_trash", {"page": 1, "size": 20}), "list_trash")
            trash_ids = [item.get("id") for item in trash.get("records", [])] if isinstance(trash, dict) else []
            check("delete_asset 后出现在回收站", uploaded_id in trash_ids)

            restored = assert_ok(server.call_tool("restore_asset", {"id": uploaded_id}), "restore_asset")
            check("restore_asset 生效", isinstance(restored, dict) and restored.get("restored") == uploaded_id)

            dry_run = assert_ok(server.call_tool("purge_asset", {"id": uploaded_id}), "purge_asset(dryRun)")
            check("purge_asset 默认 dryRun 不删除", isinstance(dry_run, dict) and dry_run.get("dryRun") is True)

            assert_ok(server.call_tool("delete_asset", {"id": uploaded_id}), "delete_asset(again)")
            purged = assert_ok(server.call_tool("purge_asset", {"id": uploaded_id, "confirm": True}), "purge_asset")
            check("purge_asset confirm=true 彻底删除", isinstance(purged, dict) and purged.get("purged") == uploaded_id)
            uploaded_id = None

        print()
        print("错误处理：")
        bad = server.call_tool("get_asset", {"id": 999999999})
        check("不存在的资产返回 isError", bad.get("isError") is True,
              str(json_payload(bad))[:120])
        unknown = server.request("tools/call", {"name": "no_such_tool", "arguments": {}})
        check("未知工具返回 isError", unknown.get("isError") is True)
        return 0 if not failed else 1
    except Exception as exc:  # noqa: BLE001 - 冒烟测试要把失败原因打出来
        print(f"\n[ERROR] {type(exc).__name__}: {exc}")
        failed.append("运行时异常")
        return 1
    finally:
        server.close()
        print(f"\n结果：{len(passed)} 通过 / {len(failed)} 失败")
        if failed:
            for item in failed:
                print(f"  - {item}")


if __name__ == "__main__":
    sys.exit(run())
