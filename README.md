# code-graph-app

独立的 Code Graph 工作台应用。前端、任务中心、Worker 监控、MCP 入口和图谱 API 都在本仓库；底层解析引擎和存储适配器继续由 [`code-graph-engine`](https://github.com/praha-poseidon/code-graph-engine) 提供。

## 构建

首次构建会把 Engine 的公共模块安装到本机 Maven 仓库，然后构建 App：

```bash
./scripts/build.sh
java -jar target/code-graph-app-0.0.1-SNAPSHOT.jar
```

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

## 依赖边界

App 依赖 Engine 发布到本机 Maven 仓库的公共模块。`scripts/build.sh` 会自动从 GitHub 获取 Engine，因此 App 可以独立克隆和构建；不会把 Engine 源码复制进本仓库。
