#!/usr/bin/env python3
"""LuminaVault MCP server.

把 LuminaVault 图库开放 API（/api/open/v1）包装成 MCP 工具，供任意 MCP 客户端
（Claude Desktop、Cursor、DSH…）通过 stdio 调用：浏览 / 搜索 / 上传 / 管理图库。

依赖：仅 Python 3.10+ 标准库（无需 pip install）。
配置（环境变量）：
    LUMINA_VAULT_URL      站点地址，默认 http://localhost:8080
    LUMINA_VAULT_API_KEY  在网站「API 密钥」抽屉里创建的密钥（lv_ 开头）
    LUMINA_VAULT_TIMEOUT  请求超时秒数，默认 30

协议：JSON-RPC 2.0 over stdio，每行一个消息（MCP stdio transport）。
stdout 只写协议消息，日志一律走 stderr。
"""

from __future__ import annotations

import base64
import json
import mimetypes
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
from typing import Any

PROTOCOL_VERSION = "2024-11-05"
SERVER_NAME = "lumina-vault"
SERVER_VERSION = "1.0.0"
API_PREFIX = "/api/open/v1"
IMAGE_INLINE_LIMIT = 4 * 1024 * 1024


def log(message: str) -> None:
    print(f"[lumina-vault-mcp] {message}", file=sys.stderr, flush=True)


class ApiError(Exception):
    pass


class LuminaClient:
    def __init__(self, base_url: str, api_key: str, timeout: float = 30.0) -> None:
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.timeout = timeout

    # ------------------------------------------------------------ 基础请求

    def _headers(self, extra: dict[str, str] | None = None) -> dict[str, str]:
        headers = {"X-API-Key": self.api_key, "Accept": "application/json"}
        if extra:
            headers.update(extra)
        return headers

    def _url(self, path: str, params: dict[str, Any] | None = None) -> str:
        url = f"{self.base_url}{path}"
        if params:
            clean = {k: v for k, v in params.items() if v is not None and v != ""}
            if clean:
                url = f"{url}?{urllib.parse.urlencode(clean, doseq=True)}"
        return url

    def request(
        self,
        method: str,
        path: str,
        params: dict[str, Any] | None = None,
        body: Any = None,
        timeout: float | None = None,
    ) -> Any:
        data = None
        headers = self._headers()
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json; charset=utf-8"
        req = urllib.request.Request(self._url(path, params), data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=timeout or self.timeout) as response:
                payload = response.read()
        except urllib.error.HTTPError as exc:
            raise ApiError(self._error_message(exc)) from exc
        except urllib.error.URLError as exc:
            raise ApiError(f"无法连接 LuminaVault（{self.base_url}）：{exc.reason}") from exc

        if not payload:
            return None
        try:
            parsed = json.loads(payload.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise ApiError(f"响应不是合法 JSON：{exc}") from exc
        if isinstance(parsed, dict) and "code" in parsed and "message" in parsed:
            code = parsed.get("code")
            if code != 200:
                raise ApiError(f"{parsed.get('message')}（code={code}）")
            return parsed.get("data")
        return parsed

    def _error_message(self, exc: urllib.error.HTTPError) -> str:
        try:
            raw = exc.read().decode("utf-8", errors="replace")
        except Exception:  # noqa: BLE001 - 读流失败时退回状态码
            return f"HTTP {exc.code}"
        try:
            parsed = json.loads(raw)
        except json.JSONDecodeError:
            return f"HTTP {exc.code} {raw[:200]}"
        message = parsed.get("message") or parsed.get("error") or raw[:200]
        return f"HTTP {exc.code} {message}"

    # ------------------------------------------------------------ 上传 / 下载

    def upload_file(
        self,
        path: str,
        file_path: str,
        fields: dict[str, Any] | None = None,
        timeout: float | None = None,
    ) -> Any:
        if not os.path.isfile(file_path):
            raise ApiError(f"文件不存在：{file_path}")
        filename = os.path.basename(file_path)
        content_type = mimetypes.guess_type(filename)[0] or "application/octet-stream"
        with open(file_path, "rb") as handle:
            payload = handle.read()

        boundary = "----LuminaVaultBoundary" + uuid.uuid4().hex
        chunks: list[bytes] = []
        for name, value in (fields or {}).items():
            if value is None or value == "":
                continue
            chunks.append(
                (
                    f"--{boundary}\r\n"
                    f'Content-Disposition: form-data; name="{name}"\r\n\r\n'
                    f"{value}\r\n"
                ).encode("utf-8")
            )
        chunks.append(
            (
                f"--{boundary}\r\n"
                f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'
                f"Content-Type: {content_type}\r\n\r\n"
            ).encode("utf-8")
        )
        chunks.append(payload)
        chunks.append(f"\r\n--{boundary}--\r\n".encode("utf-8"))
        data = b"".join(chunks)

        headers = self._headers(
            {
                "Content-Type": f"multipart/form-data; boundary={boundary}",
                "Content-Length": str(len(data)),
            }
        )
        req = urllib.request.Request(self._url(path), data=data, headers=headers, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=timeout or max(self.timeout, 120.0)) as response:
                result = json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            raise ApiError(self._error_message(exc)) from exc
        except urllib.error.URLError as exc:
            raise ApiError(f"无法连接 LuminaVault（{self.base_url}）：{exc.reason}") from exc
        if result.get("code") != 200:
            raise ApiError(f"{result.get('message')}（code={result.get('code')}）")
        return result.get("data")

    def download(self, path: str, params: dict[str, Any] | None = None) -> tuple[bytes, str]:
        req = urllib.request.Request(self._url(path, params), headers={"X-API-Key": self.api_key}, method="GET")
        try:
            with urllib.request.urlopen(req, timeout=max(self.timeout, 120.0)) as response:
                return response.read(), response.headers.get("Content-Type", "application/octet-stream")
        except urllib.error.HTTPError as exc:
            raise ApiError(self._error_message(exc)) from exc
        except urllib.error.URLError as exc:
            raise ApiError(f"无法连接 LuminaVault（{self.base_url}）：{exc.reason}") from exc


# ------------------------------------------------------------------ 工具定义

def tool_specs() -> list[dict[str, Any]]:
    folder_param = {"type": "integer", "description": "文件夹 id；不传表示不限"}
    page_params = {
        "page": {"type": "integer", "description": "页码，从 1 开始", "default": 1},
        "size": {"type": "integer", "description": "每页条数（1-100），默认 20", "default": 20},
    }
    return [
        {
            "name": "get_account",
            "description": "查看当前 API 密钥的身份与权限（用户名、密钥名、scopes、过期时间）。",
            "inputSchema": {"type": "object", "properties": {}},
        },
        {
            "name": "get_stats",
            "description": "图库概览：资产数、回收站数、文件夹数、角色数、相似关系边数。",
            "inputSchema": {"type": "object", "properties": {}},
        },
        {
            "name": "list_assets",
            "description": "分页浏览图库资产（按上传时间倒序等）。支持关键字模糊匹配文件名/描述/作者。",
            "inputSchema": {
                "type": "object",
                "properties": {
                    **page_params,
                    "keyword": {"type": "string", "description": "关键字，模糊匹配"},
                    "folder_id": folder_param,
                    "uncategorized": {"type": "boolean", "description": "只看未归类资产", "default": False},
                    "sort": {"type": "string", "description": "排序，如 created_at,desc / original_filename,asc"},
                },
            },
        },
        {
            "name": "search_assets",
            "description": "按文件名 / 作者精确或模糊检索资产。",
            "inputSchema": {
                "type": "object",
                "properties": {
                    **page_params,
                    "filename": {"type": "string", "description": "文件名（或片段）"},
                    "author": {"type": "string", "description": "作者"},
                    "exact": {"type": "boolean", "description": "是否精确匹配，默认 false", "default": False},
                    "folder_id": folder_param,
                    "uncategorized": {"type": "boolean", "default": False},
                    "sort": {"type": "string"},
                },
            },
        },
        {
            "name": "semantic_search",
            "description": "自然语言语义搜索（AI 理解查询意图，返回相关图片与置信度）。",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "自然语言查询，如「蓝天下的雪山」"},
                    "limit": {"type": "integer", "description": "返回条数 1-50", "default": 12},
                    "min_confidence": {"type": "number", "description": "最低置信度 0-1"},
                    "image_path": {"type": "string", "description": "可选：以本地图片作为查询附件（以图搜图）"},
                },
                "required": ["query"],
            },
        },
        {
            "name": "search_by_image",
            "description": "以图搜图：传入本地图片路径，返回库内相似图。",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "image_path": {"type": "string", "description": "本地图片绝对路径"},
                    "limit": {"type": "integer", "default": 12},
                },
                "required": ["image_path"],
            },
        },
        {
            "name": "get_asset",
            "description": "查看单个资产详情：尺寸、sha256、EXIF 元数据、所属文件夹、预签名 URL。",
            "inputSchema": {
                "type": "object",
                "properties": {"id": {"type": "integer", "description": "资产 id"}},
                "required": ["id"],
            },
        },
        {
            "name": "get_asset_image",
            "description": "取回图片本身（直接返回图像内容，便于模型看图）。默认取缩略图，thumbnail=false 取原图。",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "id": {"type": "integer", "description": "资产 id"},
                    "thumbnail": {"type": "boolean", "description": "是否取缩略图，默认 true", "default": True},
                },
                "required": ["id"],
            },
        },
        {
            "name": "upload_asset",
            "description": "上传本地图片到图库，可选描述与归属文件夹。返回新资产（含 id、sha256）。",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "path": {"type": "string", "description": "本地图片绝对路径"},
                    "description": {"type": "string", "description": "图片描述"},
                    "folder_id": folder_param,
                },
                "required": ["path"],
            },
        },
        {
            "name": "update_asset",
            "description": "修改资产的文件名 / 作者 / 描述。",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "id": {"type": "integer"},
                    "original_filename": {"type": "string", "description": "新文件名"},
                    "author": {"type": "string"},
                    "description": {"type": "string"},
                },
                "required": ["id"],
            },
        },
        {
            "name": "delete_asset",
            "description": "把资产移入回收站（软删除，可 restore_asset 恢复）。",
            "inputSchema": {
                "type": "object",
                "properties": {"id": {"type": "integer"}},
                "required": ["id"],
            },
        },
        {
            "name": "list_trash",
            "description": "浏览回收站资产。",
            "inputSchema": {"type": "object", "properties": {**page_params}},
        },
        {
            "name": "restore_asset",
            "description": "从回收站恢复资产。",
            "inputSchema": {
                "type": "object",
                "properties": {"id": {"type": "integer"}},
                "required": ["id"],
            },
        },
        {
            "name": "purge_asset",
            "description": "彻底删除资产（不可恢复，同时删除存储对象）。默认只做预览：confirm=true 才真正执行。",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "id": {"type": "integer"},
                    "confirm": {"type": "boolean", "description": "必须显式传 true 才会真正删除", "default": False},
                },
                "required": ["id"],
            },
        },
        {
            "name": "find_similar",
            "description": "查找与指定资产相似的图片（向量相似度 + 相似图图谱）。",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "id": {"type": "integer"},
                    "limit": {"type": "integer", "default": 12},
                },
                "required": ["id"],
            },
        },
        {
            "name": "list_folders",
            "description": "列出全部文件夹及各自的资产数量。",
            "inputSchema": {"type": "object", "properties": {}},
        },
        {
            "name": "create_folder",
            "description": "新建文件夹。",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "name": {"type": "string", "description": "文件夹名（最长 128 位）"},
                    "description": {"type": "string"},
                },
                "required": ["name"],
            },
        },
        {
            "name": "delete_folder",
            "description": "删除文件夹（资产本身不受影响）。",
            "inputSchema": {
                "type": "object",
                "properties": {"id": {"type": "integer"}},
                "required": ["id"],
            },
        },
        {
            "name": "assign_folder",
            "description": "把资产放入 / 加入 / 移出文件夹。mode=assign 覆盖式归类（默认），add 追加，remove 移出。",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "folder_id": {"type": "integer"},
                    "asset_ids": {"type": "array", "items": {"type": "integer"}, "description": "资产 id 列表"},
                    "mode": {"type": "string", "enum": ["assign", "add", "remove"], "default": "assign"},
                },
                "required": ["folder_id", "asset_ids"],
            },
        },
        {
            "name": "list_characters",
            "description": "列出角色库（角色名、别名、作品、参考图数量）。",
            "inputSchema": {"type": "object", "properties": {}},
        },
        {
            "name": "enrich_asset",
            "description": "触发对资产的 AI 分析（生成描述、标签、角色识别）。默认异步返回。",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "id": {"type": "integer"},
                    "async_mode": {"type": "boolean", "description": "是否异步，默认 true", "default": True},
                },
                "required": ["id"],
            },
        },
    ]


# ------------------------------------------------------------------ 工具实现

def call_tool(client: LuminaClient, name: str, args: dict[str, Any]) -> list[dict[str, Any]]:
    handler = HANDLERS.get(name)
    if handler is None:
        raise ApiError(f"未知工具：{name}")
    return handler(client, args)


def _text(payload: Any) -> list[dict[str, Any]]:
    return [{"type": "text", "text": json.dumps(payload, ensure_ascii=False, indent=2)}]


def _get_account(client: LuminaClient, _args: dict[str, Any]) -> list[dict[str, Any]]:
    return _text(client.request("GET", f"{API_PREFIX}/account"))


def _get_stats(client: LuminaClient, _args: dict[str, Any]) -> list[dict[str, Any]]:
    return _text(client.request("GET", f"{API_PREFIX}/stats"))


def _list_assets(client: LuminaClient, args: dict[str, Any]) -> list[dict[str, Any]]:
    return _text(
        client.request(
            "GET",
            f"{API_PREFIX}/assets",
            params={
                "page": args.get("page", 1),
                "size": args.get("size", 20),
                "keyword": args.get("keyword"),
                "folderId": args.get("folder_id"),
                "uncategorized": args.get("uncategorized", False),
                "sort": args.get("sort"),
            },
        )
    )


def _search_assets(client: LuminaClient, args: dict[str, Any]) -> list[dict[str, Any]]:
    return _text(
        client.request(
            "GET",
            f"{API_PREFIX}/assets/search",
            params={
                "page": args.get("page", 1),
                "size": args.get("size", 20),
                "filename": args.get("filename"),
                "author": args.get("author"),
                "exact": args.get("exact", False),
                "folderId": args.get("folder_id"),
                "uncategorized": args.get("uncategorized", False),
                "sort": args.get("sort"),
            },
        )
    )


def _semantic_search(client: LuminaClient, args: dict[str, Any]) -> list[dict[str, Any]]:
    body: dict[str, Any] = {"query": args["query"]}
    if args.get("limit") is not None:
        body["limit"] = args["limit"]
    if args.get("min_confidence") is not None:
        body["minConfidence"] = args["min_confidence"]
    image_path = args.get("image_path")
    if image_path:
        attachment = client.upload_file(f"{API_PREFIX}/search/attachment", image_path)
        body["attachmentId"] = attachment.get("attachmentId") or attachment.get("id")
    return _text(client.request("POST", f"{API_PREFIX}/search", body=body, timeout=180.0))


def _search_by_image(client: LuminaClient, args: dict[str, Any]) -> list[dict[str, Any]]:
    return _text(
        client.upload_file(
            f"{API_PREFIX}/assets/similar-search",
            args["image_path"],
            {"limit": args.get("limit", 12)},
        )
    )


def _get_asset(client: LuminaClient, args: dict[str, Any]) -> list[dict[str, Any]]:
    return _text(client.request("GET", f"{API_PREFIX}/assets/{int(args['id'])}"))


def _get_asset_image(client: LuminaClient, args: dict[str, Any]) -> list[dict[str, Any]]:
    asset_id = int(args["id"])
    thumbnail = bool(args.get("thumbnail", True))
    payload, content_type = client.download(
        f"{API_PREFIX}/assets/{asset_id}/content", {"thumbnail": "true" if thumbnail else "false"}
    )
    if len(payload) > IMAGE_INLINE_LIMIT:
        raise ApiError(
            f"图片 {len(payload) / 1048576:.1f}MB 超过内联上限 "
            f"{IMAGE_INLINE_LIMIT / 1048576:.0f}MB，请改用 thumbnail=true 或 get_asset 取预签名 URL"
        )
    mime = content_type.split(";")[0].strip() or "image/png"
    return [
        {"type": "text", "text": f"资产 {asset_id}（{'缩略图' if thumbnail else '原图'}，{mime}，{len(payload)} 字节）"},
        {"type": "image", "data": base64.b64encode(payload).decode("ascii"), "mimeType": mime},
    ]


def _upload_asset(client: LuminaClient, args: dict[str, Any]) -> list[dict[str, Any]]:
    return _text(
        client.upload_file(
            f"{API_PREFIX}/assets",
            args["path"],
            {"description": args.get("description"), "folderId": args.get("folder_id")},
        )
    )


def _update_asset(client: LuminaClient, args: dict[str, Any]) -> list[dict[str, Any]]:
    body = {
        "originalFilename": args.get("original_filename"),
        "author": args.get("author"),
        "description": args.get("description"),
    }
    body = {k: v for k, v in body.items() if v is not None}
    if not body:
        raise ApiError("至少提供一个要修改的字段")
    return _text(client.request("PUT", f"{API_PREFIX}/assets/{int(args['id'])}", body=body))


def _delete_asset(client: LuminaClient, args: dict[str, Any]) -> list[dict[str, Any]]:
    client.request("DELETE", f"{API_PREFIX}/assets/{int(args['id'])}")
    return _text({"deleted": int(args["id"]), "hint": "已移入回收站，可用 restore_asset 恢复"})


def _list_trash(client: LuminaClient, args: dict[str, Any]) -> list[dict[str, Any]]:
    return _text(
        client.request(
            "GET",
            f"{API_PREFIX}/assets/trash",
            params={"page": args.get("page", 1), "size": args.get("size", 20)},
        )
    )


def _restore_asset(client: LuminaClient, args: dict[str, Any]) -> list[dict[str, Any]]:
    client.request("POST", f"{API_PREFIX}/assets/{int(args['id'])}/restore")
    return _text({"restored": int(args["id"])})


def _purge_asset(client: LuminaClient, args: dict[str, Any]) -> list[dict[str, Any]]:
    asset_id = int(args["id"])
    if not args.get("confirm", False):
        return _text(
            {
                "dryRun": True,
                "assetId": asset_id,
                "hint": "彻底删除不可恢复。确认后请再次调用并传 confirm=true。",
            }
        )
    client.request("DELETE", f"{API_PREFIX}/assets/{asset_id}/purge")
    return _text({"purged": asset_id})


def _find_similar(client: LuminaClient, args: dict[str, Any]) -> list[dict[str, Any]]:
    return _text(
        client.request(
            "GET",
            f"{API_PREFIX}/assets/{int(args['id'])}/similar",
            params={"limit": args.get("limit", 12)},
        )
    )


def _list_folders(client: LuminaClient, _args: dict[str, Any]) -> list[dict[str, Any]]:
    return _text(client.request("GET", f"{API_PREFIX}/folders"))


def _create_folder(client: LuminaClient, args: dict[str, Any]) -> list[dict[str, Any]]:
    return _text(
        client.request(
            "POST",
            f"{API_PREFIX}/folders",
            body={"name": args["name"], "description": args.get("description")},
        )
    )


def _delete_folder(client: LuminaClient, args: dict[str, Any]) -> list[dict[str, Any]]:
    client.request("DELETE", f"{API_PREFIX}/folders/{int(args['id'])}")
    return _text({"deleted": int(args["id"])})


def _assign_folder(client: LuminaClient, args: dict[str, Any]) -> list[dict[str, Any]]:
    mode = str(args.get("mode", "assign")).lower()
    suffix = {"assign": "", "add": "/add", "remove": "/remove"}.get(mode)
    if suffix is None:
        raise ApiError(f"mode 只能是 assign / add / remove，收到 {mode}")
    folder_id = int(args["folder_id"])
    asset_ids = [int(i) for i in args["asset_ids"]]
    affected = client.request(
        "POST",
        f"{API_PREFIX}/folders/{folder_id}/assets{suffix}",
        body={"assetIds": asset_ids},
    )
    return _text({"folderId": folder_id, "mode": mode, "affected": affected})


def _list_characters(client: LuminaClient, _args: dict[str, Any]) -> list[dict[str, Any]]:
    return _text(client.request("GET", f"{API_PREFIX}/characters"))


def _enrich_asset(client: LuminaClient, args: dict[str, Any]) -> list[dict[str, Any]]:
    async_mode = bool(args.get("async_mode", True))
    client.request(
        "POST",
        f"{API_PREFIX}/assets/{int(args['id'])}/enrich",
        params={"async": "true" if async_mode else "false"},
        timeout=600.0,
    )
    return _text({"assetId": int(args["id"]), "async": async_mode, "hint": "分析已提交"})


HANDLERS = {
    "get_account": _get_account,
    "get_stats": _get_stats,
    "list_assets": _list_assets,
    "search_assets": _search_assets,
    "semantic_search": _semantic_search,
    "search_by_image": _search_by_image,
    "get_asset": _get_asset,
    "get_asset_image": _get_asset_image,
    "upload_asset": _upload_asset,
    "update_asset": _update_asset,
    "delete_asset": _delete_asset,
    "list_trash": _list_trash,
    "restore_asset": _restore_asset,
    "purge_asset": _purge_asset,
    "find_similar": _find_similar,
    "list_folders": _list_folders,
    "create_folder": _create_folder,
    "delete_folder": _delete_folder,
    "assign_folder": _assign_folder,
    "list_characters": _list_characters,
    "enrich_asset": _enrich_asset,
}


# ------------------------------------------------------------------ 协议层

def send(message: dict[str, Any]) -> None:
    sys.stdout.write(json.dumps(message, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def send_result(request_id: Any, result: Any) -> None:
    send({"jsonrpc": "2.0", "id": request_id, "result": result})


def send_error(request_id: Any, code: int, message: str, data: Any = None) -> None:
    error: dict[str, Any] = {"code": code, "message": message}
    if data is not None:
        error["data"] = data
    send({"jsonrpc": "2.0", "id": request_id, "error": error})


def handle(client: LuminaClient, message: dict[str, Any]) -> None:
    method = message.get("method")
    request_id = message.get("id")

    if method == "initialize":
        client_version = (message.get("params") or {}).get("protocolVersion") or PROTOCOL_VERSION
        send_result(
            request_id,
            {
                "protocolVersion": client_version if client_version == PROTOCOL_VERSION else PROTOCOL_VERSION,
                "capabilities": {"tools": {"listChanged": False}},
                "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION},
            },
        )
        return

    if method in ("notifications/initialized", "initialized", "notifications/cancelled"):
        return

    if method == "ping":
        send_result(request_id, {})
        return

    if method == "tools/list":
        send_result(request_id, {"tools": tool_specs()})
        return

    if method == "tools/call":
        params = message.get("params") or {}
        name = params.get("name")
        arguments = params.get("arguments") or {}
        if not isinstance(arguments, dict):
            arguments = {}
        try:
            content = call_tool(client, name, arguments)
            send_result(request_id, {"content": content, "isError": False})
        except ApiError as exc:
            send_result(
                request_id,
                {"content": [{"type": "text", "text": f"调用失败：{exc}"}], "isError": True},
            )
        except Exception as exc:  # noqa: BLE001 - 任何异常都要回给客户端，不能静默
            log(f"工具 {name} 异常：{exc!r}")
            send_result(
                request_id,
                {"content": [{"type": "text", "text": f"内部错误：{exc}"}], "isError": True},
            )
        return

    if request_id is not None:
        send_error(request_id, -32601, f"不支持的方法：{method}")


def main() -> int:
    base_url = os.environ.get("LUMINA_VAULT_URL", "http://localhost:8080")
    api_key = os.environ.get("LUMINA_VAULT_API_KEY", "").strip()
    timeout = float(os.environ.get("LUMINA_VAULT_TIMEOUT", "30"))

    if not api_key:
        log("未设置 LUMINA_VAULT_API_KEY —— 请先在网站「API 密钥」抽屉里创建密钥")

    client = LuminaClient(base_url, api_key, timeout)
    log(f"启动：{base_url}（协议 {PROTOCOL_VERSION}，{len(HANDLERS)} 个工具）")

    for raw_line in sys.stdin:
        line = raw_line.strip()
        if not line:
            continue
        try:
            message = json.loads(line)
        except json.JSONDecodeError:
            log(f"忽略非法 JSON 行：{line[:120]}")
            continue
        if not isinstance(message, dict):
            continue
        try:
            handle(client, message)
        except Exception as exc:  # noqa: BLE001 - 单条消息失败不应终止服务
            log(f"处理消息失败：{exc!r}")
            if message.get("id") is not None:
                send_error(message["id"], -32603, f"内部错误：{exc}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
