# 虚拟机部署（Docker）：LangBot + NapCat + 发送桥

本目录把 QQ 助手在 Linux 虚拟机上的运行方式固化成一份 compose：**LangBot（含插件运行时）+ NapCat + send_bridge**。
Spring Boot、Dify、MySQL 不在这里——它们各自的位置见最后一节「地址对照表」。

```
QQ ── NapCat(容器) ──WS 3001── LangBot(容器) ──插件──┐
      └──HTTP 3000──────────────────────────────┐     │
                                                  ▼     ▼
Windows: Spring Boot :8080 ◄── send_bridge(容器) :5301
                                   └── 只读挂载 ./data，读写插件产物文件
```

## 0. 前置条件

- 虚拟机装好 Docker + compose 插件（`docker compose version` 有输出即可）
- 虚拟机有固定局域网 IP（下文记作 `<VM_IP>`），Windows 记作 `<WIN_IP>`
- 防火墙放行：`5301`（Spring → 桥）。`5300/6098` 建议只走 SSH 隧道，不对外开
- 仓库已拉到虚拟机，或至少把 `deploy/` 和 `QQMessageAssistantPython/` 一起拷过去

> 首次登录 QQ 必须扫码，建议在能开浏览器的机器上用 SSH 隧道：
> `ssh -L 5300:127.0.0.1:5300 -L 6098:127.0.0.1:6098 user@<VM_IP>`
> （NapCat WebUI 映射到宿主机 6098；容器内仍是 6099，由 `.env` 的 `NAPCAT_WEBUI_PORT` 控制）

## 1. 起容器

```bash
cd deploy
cp .env.example .env
vim .env                       # 至少填 NAPCAT_WEBUI_TOKEN / SPRING_BASE / LANGBOT_* （见下）
docker compose up -d
docker compose ps              # 4 个服务都应是 running
```

`.env` 里先能填的：

| 变量 | 怎么来 |
|---|---|
| `NAPCAT_WEBUI_TOKEN` / `NAPCAT_HTTP_TOKEN` | 自己定的强口令（HTTP 那个稍后在 NapCat WebUI 里填同一个） |
| `SPRING_BASE` | `http://<WIN_IP>:8080`，虚拟机 curl 得通再往下走 |
| `NAPCAT_UID` / `NAPCAT_GID` | 虚拟机 `id -u` / `id -g` |
| `LANGBOT_BOT_UUID` / `LANGBOT_API_KEY` | 第 3 步拿到后再补，补完 `docker compose up -d send_bridge` 重启桥 |

## 2. NapCat：登录 QQ + 打开 HTTP/WS

1. 浏览器开 `http://127.0.0.1:6098/webui/`（走 SSH 隧道），用 `NAPCAT_WEBUI_TOKEN` 登录
   - 这个版本的 NapCat WebUI 是**明文 HTTP**（实测 `curl -I http://127.0.0.1:6098/webui` 返回 `301 → /webui/`）；用 `https://` 会报 `SSL received a record that exceeded the maximum permissible length`
   - 在 Windows 浏览器里访问时，`127.0.0.1` 指的是 Windows 自己，必须先建隧道或把 `NAPCAT_WEBUI_BIND` 改成 `0.0.0.0`
2. 扫码登录 QQ。登录态落在 `./napcat/qq`，重建容器不用重扫
3. **网络配置**里开两个服务，**Host 必须填 `0.0.0.0`**（默认 `127.0.0.1` 只监听容器自己的 loopback，别的容器连 `napcat:3001` 会 `ConnectionRefusedError`）：
   - **HTTP 服务器**：Host `0.0.0.0`，端口 `3000`，Token 填 `NAPCAT_HTTP_TOKEN` —— 插件下载/回传文件和发送桥发文件都走它
   - **WebSocket 服务器**：Host `0.0.0.0`，端口 `3001`，Token 与 LangBot 机器人的「访问令牌」一致 —— LangBot 的平台适配器走它
4. 两个服务都只在容器网络内，宿主机不用映射端口（`0.0.0.0` 指的是容器内网卡，不是宿主机）

> 扫码登录后若提示设备锁/异地登录，按 QQ 提示在手机上确认即可。

## 3. LangBot：机器人 + API 密钥

1. 浏览器开 `http://127.0.0.1:5300`，首次进入创建管理员
2. **机器人 → 新建**：平台选 `aiocqhttp`（OneBot），连接地址填
   `ws://napcat:3001`，保存后状态应为已连接
3. 拿机器人 **UUID**：v4.11 的机器人详情页不直接显示 UUID，它就在地址栏里——
   `/home/bots?id=98cdd79b-ad16-492b-8420-276c86cc8d94` 的 `id` 就是。
   也可用 API 查（需要先有 API 密钥）：
   `curl -H "X-API-Key: <key>" http://127.0.0.1:5300/api/v1/platform/bots`
   把它填进 `.env` 的 `LANGBOT_BOT_UUID`
4. 左侧 **API 集成**（或 设置 → API 密钥）里生成/复制密钥 → `.env` 的 `LANGBOT_API_KEY`
5. `docker compose up -d send_bridge` 让桥读到新值；`curl http://127.0.0.1:5301/health` 应返回 `{"code":0,...}`

## 4. 安装插件并配置

1. LangBot WebUI → 插件 → 安装 → 上传
   `QQMessageAssistantPython/qq_assistant_plugin/dist/Marian-qq_assistant_plugin-0.2.24.lbpkg`
2. 打开插件配置，按容器网络填：

| 配置项 | 值 |
|---|---|
| `spring_base_url` | `http://<WIN_IP>:8080` |
| `dify_base_url` | Dify 的地址（Windows 或别的机器），如 `http://<WIN_IP>/v1` |
| `dify_api_key` | Dify 应用密钥 |
| `dify_file_workflow_key` | 文件填写 Workflow 的密钥 |
| `napcat_api_url` | `http://napcat:3000` |
| `napcat_token` | 与 `NAPCAT_HTTP_TOKEN` 一致 |
| `bot_uuid` | 第 3 步的 UUID |
| `bot_qq` | 机器人 QQ 号 |
| `data_dir` | **留空**（自动落到 `/app/data/qq_assistant/data`） |

3. 启用插件，给机器人发一条私聊消息，看是否回复

## 5. 插件产物路径与桥的对应关系

插件把文件写到 `<LangBot data>/qq_assistant/data/files/<taskRef>/`，容器内即
`/app/data/qq_assistant/data/files/...`。`send_bridge` 以**同一路径**只读挂载 `./data`，
所以插件回传的 `local:///app/data/qq_assistant/data/files/...` 在桥里能直接打开。
这是两个容器必须挂同一宿主目录、同一容器路径的原因，改路径就会「文件不存在」。

手工确认一次：

```bash
docker compose exec send_bridge ls /app/data/qq_assistant/data/files
docker compose exec send_bridge python -c "import os; print(os.path.isfile('/app/data/qq_assistant/data/files'))"
```

插件把每次处理的痕迹写到同一个目录的 `_debug.log`（`dify ok ... answer=... action=...`、
异常栈）。`docker compose exec` 后面跟的是**服务名**（`langbot_plugin_runtime`），不是容器名：

```bash
cd deploy
docker compose exec langbot_plugin_runtime tail -n 60 /app/data/qq_assistant/data/files/_debug.log
```

## 6. Spring Boot 与数据库

- `QQMessageAssistantJava/src/main/resources/application.yaml`：
  `langbot.base-url: http://<VM_IP>:5301`，重启后端
- Windows 防火墙放行 8080（给插件和桥调用）
- MySQL 跑在虚拟机上的话，Spring 的 JDBC 地址照旧用 `192.168.100.128:3307`
- 已有库记得执行 `db/migrations/2026-10-27-phase3-file-task-and-log.sql`
  以及 `db/migrations/2026-10-31-embedding-retrieval.sql`（两张表 + `message_log.ft_content` 全文索引，
  需要 MySQL 8.0+ 的 `ngram` 分词；脚本可重复执行）

### 6.1 语义检索模型（可选，但 `/api/ask` 的语义通道需要）

模型文件**不进仓库**（`QQMessageAssistantJava/models/` 已在 `.gitignore`）。在 `QQMessageAssistantJava/`
下执行仓库自带脚本：

```bash
cd QQMessageAssistantJava
bash scripts/fetch-embedding-model.sh
```

也可以手动下载同样两个文件（保持文件名与目录）：

```text
https://hf-mirror.com/Xenova/bge-small-zh-v1.5/resolve/main/onnx/model.onnx
https://hf-mirror.com/Xenova/bge-small-zh-v1.5/resolve/main/vocab.txt
```

放到 `QQMessageAssistantJava/models/bge-small-zh-v1.5/`（`model.onnx` 和 `vocab.txt` 同级），
并校验字节数：

| 本地文件 | 来源 | 大小（字节） |
|---|---|---|
| `model.onnx` | 远端 `onnx/model.onnx` | **94851877** |
| `vocab.txt` | 远端 `vocab.txt` | **109540** |

> 计划文档里写的 94951877 是笔误，以脚本与上表为准。

对应配置在 `src/main/resources/application.yaml` 的 `app.embedding.*`：

| 配置键 | 默认 | 说明 |
|---|---|---|
| `app.embedding.enabled` | `true` | `false` 或模型文件缺失 → 语义通道关闭，其余六路照常 |
| `app.embedding.mode` | `local` | `local`（进程内 ONNX）\| `http`（OpenAI 兼容 `/embeddings`） |
| `app.embedding.model-dir` | `./models/bge-small-zh-v1.5` | 相对模块根目录（运行目录 = `QQMessageAssistantJava`） |
| `app.embedding.model-id` | `bge-small-zh-v1.5` | 换模型 = 全量重建 |
| `app.embedding.dim` | `512` | 向量维度 |
| `app.embedding.http.base-url` | 空 | `mode=http` 时使用，例：`http://127.0.0.1:8081/v1` |
| `app.embedding.http.api-key` | 空 | **只从环境变量 `EMBEDDING_HTTP_API_KEY` 注入，不要写进仓库** |
| `app.embedding.http.model` | 空 | 外部端点模型名，环境变量 `EMBEDDING_HTTP_MODEL` |
| `app.retrieval.pre-run-index-limit` | `500` | 每次 `/api/ask` 前同步补跑向量的上限；超出部分计入 `scope.lag` |

部署后用 `GET /api/memory/embeddings/status` 验证：`semantic=true` 即模型就绪。
模型缺失或 ONNX 初始化失败时应用照常启动，只是语义通道关闭——不会让 `/api/ask` 500。

## 7. 验收（真机）

按 `design/dify_chatflow.md` §11：

1. 私聊发「我要发个文件，把姓名填上」→ 回「发过来吧」
2. 发 xlsx → 不再反问，直接填写/进草稿
3. 低置信时去 Vue 草稿中心确认 → QQ 真的收到文件
4. 群聊里 @机器人 才触发，普通群消息不打扰

## 8. 排错对照表

| 现象 | 先查 |
|---|---|
| `send_bridge` 起不来、日志报缺环境变量 | `.env` 里 `LANGBOT_BOT_UUID` / `LANGBOT_API_KEY` / `SPRING_BASE` 是否为空（compose 里的 `:?` 会直接拒绝启动） |
| QQ 发消息没反应，NapCat 正常 | LangBot 机器人是否「已连接」；日志里 `ConnectionRefusedError` 基本就是 NapCat 的 HTTP/WS 服务 Host 还写着 `127.0.0.1`，改成 `0.0.0.0` 并保存 |
| 插件/桥连 NapCat 被拒 | 同上：HTTP 服务器的 Host 也要 `0.0.0.0`；Token 三处（NapCat、插件 `napcat_token`、`.env` 的 `NAPCAT_HTTP_TOKEN`）要一致 |
| 插件日志 `httpx.ConnectError: All connection attempts failed` | 插件跑在 `langbot_plugin_runtime` 容器里，`localhost` / `127.0.0.1` 指的是**容器自己**。`spring_base_url`、`dify_base_url` 必须填容器可达的地址（Windows 的局域网 IP）；同时 Windows 防火墙放行 8080 与 Dify 端口 |
| LangBot 显示处理成功但 QQ 无回复、Dify 无日志、MySQL 无记录 | 插件在调 Dify/Spring 之前就抛异常了（先弹 profile 请求）。看 `docker compose logs qq-langbot-runtime` 里第一条 `ConnectError` 指向哪个地址 |
| Dify 返回 400 `{"code":"invalid_param","message":"not supported value type integer"}`，**任何消息都失败且秒回** | 会话变量 `pending_file_expires_at` 默认值写成了 `0`，DSL 里存成 `value_type: integer`；Dify 加载变量池时不认这个类型，节点一个都没跑 | Dify 里把该变量默认值改成 `0.1`（保存后导出 DSL 应显示 `value_type: number`）；UI 改不动就改 DSL 重新导入，并同步插件里的 `dify_api_key`。见 `design/dify_chatflow.md` §6.1 |
| Dify 返回 400 `1 validation error for HttpRequestNodeData` / `authorization Field required`，**每条消息都 400** | 工作流里 HTTP 请求节点（`OP_HTTP`）的「授权」没选。在节点里把授权选成「无」（token 是填在 Headers 里的），保存即可；重新导入 `design/QQAssistant.chatflow.yml` 也行，但 secret 要重填 |
| 插件报 502 / 下载失败 | 插件 `napcat_api_url` 是否 `http://napcat:3000`、Token 是否一致 |
| VM / 容器访问 Windows 上的 Spring 返回 `http_code=000` | Windows 防火墙或监听地址问题：`netstat -ano \| findstr :8080` 要看到 `0.0.0.0:8080`；再加显式规则 `New-NetFirewallRule -DisplayName "SpringBoot 8080" -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow -Profile Any`（虚拟机网卡常被判为「公用」profile） |
| 桥返回 400「fileRef 指向的文件不存在」 | 第 5 节两条路径是否一致；插件 `data_dir` 是否被改过 |
| 桥返回 409 | 同一 `bizRef` 正在发送中，属预期保护；稍后重试会返回上次 `messageId` |
| 提醒只收到第一条 | 确认后端是本次改动后的版本（bizRef 按 `<memoId>:<reminderId>`） |
| Java 调桥超时 | `langbot.read-timeout-ms` 必须 > 25s（当前 35000）；VM 到 Windows 是否通 |
| `docker compose up` 拉镜像超时 | Docker Hub 不可达：`LANGBOT_IMAGE` 换官方国内源，`PYTHON_IMAGE` 换国内 registry 后重建 |
| 浏览器打不开 NapCat WebUI | 先分清是谁在访问：VM 上 `curl -I http://127.0.0.1:<宿主机端口>/webui/` 通就说明服务正常，问题在同机访问/隧道；HTTPS 报 SSL 错说明用了 `https://`，改 `http://` |
| 端口被旧容器占用 | `docker ps` 里若有更早手工起的 `langbot-napcat` 之类，先 `docker stop <旧容器>`（可回滚，不要急着 `rm`）；同类容器跑两份会互相顶掉 QQ 登录 |

> 本目录的 compose 已通过 `docker compose config` 校验；镜像构建与真机联调需在虚拟机（或能访问镜像仓库的机器）上执行。

## 9. Dify → Spring 的写入口（`AGENT_OPS_TOKEN`）

Phase 6 起，Dify 的 `OP_HTTP` 节点会直连 Spring 的 `POST /api/agent/ops` 去改/取消备忘。
这条链路需要一个共享口令，**两边必须一致**：

| 位置 | 怎么配 |
|---|---|
| Spring Boot（Windows） | 启动前设环境变量 `AGENT_OPS_TOKEN=<32 位随机串>`；也可以直接 `setx` 或写进 IDE 的 Run Configuration。**不要**写进 `application.yaml` |
| Dify 控制台 | 「环境变量」里建 `AGENT_OPS_TOKEN`，类型选 **Secret**，值同上；另建 `SPRING_BASE_URL` = Dify 容器能访问到的 Spring 地址（见地址对照表） |

```bash
# 生成口令（任选其一）
openssl rand -hex 16
python -c "import secrets;print(secrets.token_hex(16))"
```

- **留空 = 功能关闭**：Spring 未配置 token 时 `/api/agent/*` 一律 503（fail closed），
  不会意外开一个无鉴权的写入口。
- secret 类型**不随 DSL 导出**：重新导入 `design/QQAssistant.chatflow.yml` 后，值一定是空的，
  必须在 UI 里重填——忘了填的表现就是 QQ 上一句“刚才没办成，后台没连上”。
- Dify 走 `ssrf_proxy`，默认拦私网。VM/容器里的 Dify 访问 Windows 上的 8080 前先验：
  `docker compose exec -T dify-api curl -s -o /dev/null -w '%{http_code}\n' http://<WIN_IP>:8080/api/agent/ops`
  返回 `401`/`503` 就说明通了（这两个都是“鉴权没过”，不是“连不上”）。
- 若 Dify 日志里出现 `blocked by SSRF protection`，要在 **Dify 自己的** `docker/.env` 里放行 Spring 地址所在的网段
  （值是 CIDR，不是 `true`），然后重启 `ssrf_proxy api worker`：

  ```bash
  # 1) 先看 host.docker.internal（或你的 SPRING_BASE_URL 主机）解析成哪个 IP
  docker compose -f docker/docker-compose.yaml exec api \
    python -c "import socket;print(socket.gethostbyname('host.docker.internal'))"
  # 2) 写进 docker/.env
  SSRF_PROXY_ALLOW_PRIVATE_IPS=172.21.0.0/16
  docker compose -f docker/docker-compose.yaml up -d ssrf_proxy api worker
  ```

  放行私网等于给 Dify 的 HTTP 节点开了内网出口，只适合自家/实验室环境。

## 10. 安全与运维

- **不要**把 `5401` 映射到宿主机（插件运行器调试口，官方安全通告指出空密钥可被劫持）
- `5300` / `6098` 默认只绑 `127.0.0.1`，用 SSH 隧道访问；确需局域网访问时改 `.env` 的 `*_BIND` 并加防火墙白名单
- NapCat 宿主机端口由 `NAPCAT_WEBUI_PORT` 决定（默认 6098）；以后再冲突改这个值即可，容器内不用动
- 所有 Token / API Key 都放 `.env`，不要写进 compose
- 备份：`./data`（LangBot 配置与数据库、插件、任务文件）、`./napcat/qq`（QQ 登录态）、`./bridge-data`（幂等状态）
- 升级：改 `LANGBOT_IMAGE` 到固定版本 → `docker compose pull && docker compose up -d`；`./data` 不要删

## 11. 地址对照表

| 调用方 | 目标 | 地址 |
|---|---|---|
| 插件（langbot 容器内） | Spring Boot | `http://<WIN_IP>:8080` |
| 插件 | Dify | `http://<WIN_IP>`（按实际） |
| 插件 | NapCat HTTP | `http://napcat:3000` |
| 插件 | LangBot（发送） | 经 LangBot 内部 API，无需配置 |
| send_bridge | LangBot | `http://langbot:5300` |
| send_bridge | NapCat | `http://napcat:3000` |
| send_bridge | Spring Boot | `http://<WIN_IP>:8080` |
| Spring Boot | 发送桥 | `http://<VM_IP>:5301` |
| LangBot 平台适配 | NapCat WS | `ws://napcat:3001` |
