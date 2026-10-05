#!/usr/bin/env python
"""将本地图片批量导入 LuminaVault（sha256 去重、断点续传）。

去重三层：本地状态文件 -> 启动时拉取服务端已有哈希预检 -> 服务端上传判重（409）。
"""

import argparse
import hashlib
import json
import mimetypes
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

import requests

IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp"}


def sha256_of(path: Path, chunk_size: int = 1024 * 1024) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while True:
            chunk = handle.read(chunk_size)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


class ApiClient:
    def __init__(self, base: str, username: str, password: str):
        self.base = base.rstrip("/")
        self.username = username
        self.password = password
        self.lock = threading.Lock()
        self.session = requests.Session()
        self.refresh_token = None
        self.authenticate()

    def authenticate(self):
        response = self.session.post(
            f"{self.base}/api/auth/login",
            json={"username": self.username, "password": self.password},
            timeout=30,
        )
        if response.status_code != 200:
            response = self.session.post(
                f"{self.base}/api/auth/register",
                json={"username": self.username, "password": self.password, "nickname": "导入器"},
                timeout=30,
            )
        payload = response.json()
        if payload.get("code") != 200:
            raise SystemExit(f"认证失败: {payload.get('message')}")
        self.apply_tokens(payload["data"])

    def apply_tokens(self, tokens):
        self.refresh_token = tokens["refreshToken"]
        with self.lock:
            self.session.headers["Authorization"] = "Bearer " + tokens["accessToken"]

    def refresh(self) -> bool:
        response = requests.post(
            f"{self.base}/api/auth/refresh",
            json={"refreshToken": self.refresh_token},
            timeout=30,
        )
        if response.status_code != 200:
            return False
        payload = response.json()
        if payload.get("code") != 200:
            return False
        self.apply_tokens(payload["data"])
        return True

    def upload(self, path: Path):
        content_type = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
        for attempt in (1, 2):
            try:
                with path.open("rb") as handle:
                    response = self.session.post(
                        f"{self.base}/api/assets",
                        files={"file": (path.name, handle, content_type)},
                        timeout=600,
                    )
            except requests.RequestException as exc:
                return None, str(exc)
            if response.status_code == 401 and attempt == 1 and self.refresh():
                continue
            return response, None
        return None, "unauthorized"


def fetch_server_hashes(client: "ApiClient") -> set[str]:
    """分页拉取服务端在库资源的 sha256，作为预检集合。"""
    hashes: set[str] = set()
    page = 1
    while True:
        response = client.session.get(
            f"{client.base}/api/assets",
            params={"page": page, "size": 100},
            timeout=120,
        )
        if response.status_code != 200:
            print(f"警告: 无法获取服务端已有资源 (HTTP {response.status_code})，跳过预检")
            return hashes
        data = response.json().get("data") or {}
        for record in data.get("records") or []:
            if record.get("sha256"):
                hashes.add(record["sha256"])
        if page >= int(data.get("pages") or 1):
            break
        page += 1
    return hashes


def main() -> None:
    parser = argparse.ArgumentParser(description="Batch import images into LuminaVault")
    parser.add_argument("--base", default="http://localhost:8080")
    parser.add_argument("--username", default="importer")
    parser.add_argument("--password", default="lumina123")
    parser.add_argument("--dir", required=True)
    parser.add_argument("--state", default=str(Path(__file__).with_name("import-state.json")))
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--concurrency", type=int, default=4)
    parser.add_argument("--no-server-check", action="store_true", help="跳过启动时的服务端哈希预检")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    root = Path(args.dir)
    if not root.is_dir():
        raise SystemExit(f"目录不存在: {root}")
    files = sorted(path for path in root.rglob("*") if path.suffix.lower() in IMAGE_EXTENSIONS)
    if args.limit > 0:
        files = files[: args.limit]
    print(f"待导入 {len(files)} 张，目录 {root}")

    state_path = Path(args.state)
    state = {"assets": {}, "sha": {}}
    if state_path.exists():
        state = json.loads(state_path.read_text(encoding="utf-8"))
        state.setdefault("assets", {})
        state.setdefault("sha", {})
    if args.dry_run:
        return

    client = ApiClient(args.base, args.username, args.password)
    server_sha = set() if args.no_server_check else fetch_server_hashes(client)
    if server_sha:
        print(f"服务端已有 {len(server_sha)} 张（按 sha256 预检，命中将跳过）")
    completed = {"ok": 0, "skip": 0, "fail": 0}
    dirty = 0
    seen_lock = threading.Lock()
    in_flight: set[str] = set()

    def save_state():
        tmp = state_path.with_suffix(".tmp")
        tmp.write_text(json.dumps(state, ensure_ascii=False, indent=1), encoding="utf-8")
        tmp.replace(state_path)

    def process(path: Path):
        resolved = str(path.resolve())
        if resolved in state["assets"]:
            return path, "skip", "已记录", None
        digest = sha256_of(path)
        if digest in state["sha"]:
            return path, "skip", "状态文件已存在", digest
        if digest in server_sha:
            return path, "skip", "服务端已存在", digest
        with seen_lock:
            if digest in in_flight:
                return path, "skip", "本批重复", digest
            in_flight.add(digest)
        response, error = client.upload(path)
        if error:
            with seen_lock:
                in_flight.discard(digest)
            return path, "fail", error, digest
        if response.status_code == 201:
            data = response.json()["data"]
            if data.get("sha256") != digest:
                with seen_lock:
                    in_flight.discard(digest)
                return path, "fail", "哈希校验不一致（文件可能被改动）", digest
            return path, "ok", data["id"], digest
        if response.status_code == 409:
            return path, "skip", "服务端判重", digest
        with seen_lock:
            in_flight.discard(digest)
        return path, "fail", f"HTTP {response.status_code}: {response.text[:160]}", digest

    with ThreadPoolExecutor(max_workers=max(args.concurrency, 1)) as pool:
        futures = [pool.submit(process, path) for path in files]
        for index, future in enumerate(as_completed(futures), start=1):
            path, status, extra, digest = future.result()
            resolved = str(path.resolve())
            if status == "ok":
                state["assets"][resolved] = {"sha256": digest, "assetId": extra, "file": path.name}
                state["sha"][digest] = extra
                completed["ok"] += 1
            elif status == "skip":
                state["assets"].setdefault(resolved, {"sha256": digest, "assetId": None, "file": path.name})
                if digest:
                    state["sha"].setdefault(digest, None)
                completed["skip"] += 1
            else:
                completed["fail"] += 1
            extra_text = ""
            if extra and status == "ok":
                extra_text = " -> " + str(extra)
            elif extra and status == "skip":
                extra_text = f" ({extra})"
            print(
                f"[{index}/{len(files)}] {status.upper():4} {path.name}{extra_text}",
                flush=True,
            )
            dirty += 1
            if dirty >= 10:
                save_state()
                dirty = 0
    save_state()
    print(f"完成: 成功 {completed['ok']}, 跳过 {completed['skip']}, 失败 {completed['fail']}")


if __name__ == "__main__":
    main()
