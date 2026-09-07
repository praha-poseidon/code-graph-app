# code-graph-app

独立的 Code Graph 工作台应用。前端、任务中心、Worker 监控、MCP 入口和图谱 API 都在本仓库；底层解析引擎和存储适配器继续由 [`code-graph-engine`](https://github.com/praha-poseidon/code-graph-engine) 提供。

## 构建

首次构建会把 Engine 的公共模块安装到本机 Maven 仓库，然后构建 App：

```bash
export CODEGRAPH_ENGINE_TOKEN=你的只读仓库令牌
./scripts/build.sh
java -jar target/code-graph-app-0.0.1-SNAPSHOT.jar
```

Engine 仓库是私有的，`CODEGRAPH_ENGINE_TOKEN` 只用于构建时读取 Engine；不要把它写进 URL、`.env`、Dockerfile 或提交记录。GitHub Actions 使用仓库 Secret `ENGINE_READ_TOKEN`，用户直接拉取已发布镜像时不需要这个令牌。

也可以指定已有 Engine 工作区：

```bash
CODEGRAPH_ENGINE_DIR=/path/to/code-graph-engine ./scripts/build.sh
```

脚本会先构建前端并复制到 Spring Boot 静态资源目录，再执行 Maven 测试和打包。

## 运行

```bash
cp .env.example .env
java -jar target/code-graph-app-0.0.1-SNAPSHOT.jar
```

默认访问 `http://localhost:8084/workbench`。生产部署仍应使用 Engine 仓库提供的基础设施 compose（MySQL、Neo4j、Qdrant 或 Apache AGE/PostgreSQL）。

## Docker 中扩展解析 Worker

Compose 将 API/UI 与解析 Worker 分开：`app` 只提供接口和工作台，`worker` 负责领取数据库中的分析任务。任务通过 MySQL 租约分配，同一任务只会被一个 Worker 执行；多个 Worker 可以安全地并行处理不同任务。

```bash
export CODEGRAPH_ENGINE_TOKEN=你的只读仓库令牌
docker compose build --build-arg CODEGRAPH_ENGINE_REF=master app worker
docker compose up -d --scale worker=3
```

如果直接使用已发布的镜像，则不需要 `CODEGRAPH_ENGINE_TOKEN`；只有从公开 App 源码重新构建时才需要它。

`worker=3` 表示启动 3 个 Worker 容器，不需要多台机器。每个容器自动生成独立的 Worker ID，共享 MySQL、Neo4j、Qdrant 和工作区卷。工作台的 Worker 页面会显示每个容器的在线状态、当前任务和心跳时间。

## 依赖边界

App 依赖 Engine 发布到本机 Maven 仓库的公共模块。`scripts/build.sh` 会自动从 GitHub 获取 Engine，因此 App 可以独立克隆和构建；不会把 Engine 源码复制进本仓库。
