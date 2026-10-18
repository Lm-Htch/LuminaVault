# LuminaVault MCP Server

把 LuminaVault 图库接到任意 MCP 客户端（Claude Desktop、Cursor、自建 Agent 等）：**搜索、上传、管理与读取图片**，全部通过网站的 API 密钥远程调用。

- **零依赖**：纯 Python 标准库实现，不需要 `pip install`，不联网下载包。
- **标准协议**：JSON-RPC 2.0 over stdio，协议版本 `2024-11-05`，暴露 21 个工具。
- **服务端无状态**：所有能力都作用于正在运行的 LuminaVault 后端（`/api/open/v1`），密钥在网站里随时可吊销。

## 1. 快速开始

```text
① 启动 LuminaVault 后端（默认 http://localhost:8080）
② 网站顶栏「API 密钥」→ 创建密钥 → 复制 lv_… （明文只显示一次）
③ 把 MCP server 配置进客户端（见第 3 节），重启客户端
```

## 2. 环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `LUMINA_VAULT_URL` | `http://localhost:8080` | 后端地址；远程部署时改成实际地址 |
| `LUMINA_VAULT_API_KEY` | 空 | 网站创建的 `lv_…` 密钥；为空时工具调用会返回鉴权失败 |
| `LUMINA_VAULT_TIMEOUT` | `30` | 单次 HTTP 请求超时（秒），大图上传/语义搜索可调大 |
| `LUMINA_VAULT_MCP_PY` | 同目录脚本 | 仅冒烟测试使用：覆盖被测试的脚本路径 |

权限由密钥的 scopes 决定：`read` 只能查询与取图，`write` 才能上传 / 修改 / 删除 / 建文件夹。

## 3. 客户端配置

**Claude Desktop**（`claude_desktop_config.json`）：

```json
{
  "mcpServers": {
    "lumina-vault": {
      "command": "python",
      "args": ["E:\\Program\\LuminaVault\\mcp-server\\lumina_vault_mcp.py"],
      "env": {
        "LUMINA_VAULT_URL": "http://localhost:8080",
        "LUMINA_VAULT_API_KEY": "lv_xxxxxxxxxxxxxxxxxxxxxxxx"
      }
    }
  }
}
```

**Cursor / 其他支持 MCP 的客户端**（`mcp.json` 或等价配置）：

```json
{
  "mcpServers": {
    "lumina-vault": {
      "command": "python",
      "args": ["/absolute/path/to/mcp-server/lumina_vault_mcp.py"],
      "env": {
        "LUMINA_VAULT_URL": "http://localhost:8080",
        "LUMINA_VAULT_API_KEY": "lv_xxxxxxxxxxxxxxxxxxxxxxxx"
      }
    }
  }
}
```

Windows 上如果 `python` 不在 PATH，把 `command` 换成解释器绝对路径（例如 `C:\\Python312\\python.exe`）。

## 4. 工具一览（21 个）

| 分类 | 工具 | 说明 |
| --- | --- | --- |
| 账户 | `get_account` | 当前密钥的用户、权限、到期与最近使用时间 |
| | `get_stats` | 图库规模：资产 / 回收站 / 文件夹 / 角色 / 相似边 |
| 浏览 | `list_assets` | 分页浏览（关键词、文件夹、未归类、排序） |
| | `get_asset` | 单张资产详情（含元数据、预签名 URL） |
| | `get_asset_image` | **内联图片**给模型看（`thumbnail=true` 默认；超 4MB 提示改用缩略图） |
| 搜索 | `search_assets` | 按文件名 / 作者精确或模糊检索 |
| | `semantic_search` | 自然语言语义搜索（复用站内检索管线，可选 `image_path` 作为附件） |
| | `search_by_image` | 以图搜图（上传本地图片作为查询） |
| | `find_similar` | 某张资产的图谱相似邻居 |
| 写入 | `upload_asset` | 上传本地图片（可带描述、直接归入文件夹） |
| | `update_asset` | 修改文件名 / 作者 / 描述 |
| | `delete_asset` | 移入回收站（软删除） |
| | `list_trash` / `restore_asset` | 回收站列表与恢复 |
| | `purge_asset` | 彻底删除：**默认 dryRun**，需 `confirm=true` 才真删，不可恢复 |
| 组织 | `list_folders` / `create_folder` / `delete_folder` | 文件夹增删查 |
| | `assign_folder` | 批量分配：`mode=assign`（覆盖）/ `add`（追加）/ `remove`（移出） |
| | `list_characters` | 角色列表（含出现次数） |
| 分析 | `enrich_asset` | 按需触发 AI 分析（默认异步） |

## 5. 冒烟测试

```bash
# 协议层（不需要后端密钥）
python mcp-server/smoke_test.py

# 端到端：读写全链路（会临时上传一张随机 PNG，并在结束时彻底删除）
LUMINA_VAULT_API_KEY=lv_xxx python mcp-server/smoke_test.py
```

Windows PowerShell：

```powershell
$env:LUMINA_VAULT_API_KEY="lv_xxx"; python mcp-server\smoke_test.py
```

实测结果（本机，后端在跑、密钥含 write 权限）：**22 项全部通过**——协议 6 项、只读工具 5 项、写工具 9 项、错误处理 2 项。

## 6. 安全说明

- 密钥只在创建时明文返回一次；服务端仅保存 sha256 摘要与 12 字符前缀，数据库泄露也无法还原密钥。
- 泄露后立即在「API 密钥」抽屉吊销；吊销即时生效（已在用同一密钥的客户端会立刻收到 401）。
- 密钥**不能**签发 / 吊销密钥：`/api/keys/**` 只接受登录用户（`ROLE_USER`）。
- 建议给自动化脚本单独建 `read` 密钥，只在确实需要写入时使用 `write`。
- `purge_asset` 不可恢复，调用前会先返回 dryRun 结果，确认后才执行。

## 7. 已知限制

- 传输为 stdio：一个 MCP 客户端进程对应一个 MCP server 进程，不支持多客户端同时共享同一进程（各自起一个即可）。
- 图片内联上限 4MB（按 base64 前的字节数判断）：更大的图请用 `thumbnail=true` 或 `get_asset` 取预签名 URL。
- 依赖后端正在运行；后端不可用时工具会返回明确错误文本（HTTP 状态 + 原因），不会静默失败。
