# 🖼️ Ollama 图片智能筛选器 — Java B/S

基于 **Ollama 视觉模型** 的本地图片 AI 分析与筛选工具。Spring Boot + Thymeleaf 全栈应用，所有运算在服务端完成，浏览器只负责展示。

## 功能

- **单张分析** — 选择图片，输入提示词，AI 返回分类结果
- **批量处理** — 后台异步批量分析，实时进度显示，不阻塞页面
- **9 种预设提示词** — 质量筛选、文档判断、人物检测、截图识别等
- **自定义提示词** — 自由输入任意分析需求
- **结果浏览** — 按类别筛选、统计汇总
- **CSV 导出** — 结果导出为 CSV 文件下载
- **多模型支持** — 自动识别视觉模型并优先排序

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端 | Java 21 · Spring Boot 3.3 · Maven |
| 前端 | Thymeleaf · Bootstrap 5 · JavaScript |
| AI | [Ollama](https://ollama.com) 视觉模型（llava / minicpm-v / llama3.2-vision 等） |
| 并发 | `@Async` 独立线程池 · `CompletableFuture` 异步响应 |

## 快速开始

### 前提

- **JDK 21+** 和 **Maven 3.9+**（`start.sh` 可自动下载 Maven）
- **[Ollama](https://ollama.com)** 已安装，服务运行在 `localhost:11434`
- 至少一个视觉模型已拉取

```bash
# 安装 Ollama
curl -fsSL https://ollama.com/install.sh | sh

# 拉取视觉模型（任选一个）
ollama pull llava:13b
ollama pull minicpm-v:latest
ollama pull llama3.2-vision:11b
```

### 启动

```bash
cd image-filter-java
./start.sh
```

打开浏览器访问：**`http://localhost:8080`**

### 自定义参数

```bash
# 指定图片目录和端口
IMG_DIR=/path/to/photos PORT=9090 ./start.sh

# 跳过编译直接启动
./start.sh --skip-build
```

## 使用流程

```
测试连接 → 扫描图片 → 选择提示词 → 分析 → 筛选导出
```

1. **连接测试** — 点击"测试 Ollama 连接"确认服务正常
2. **扫描图片** — 点击"扫描图片目录"载入 `./img` 下的所有图片
3. **选择提示词** — 从预设中选择，或选「自定义」输入任意内容
4. **分析** — 单张即时分析，或批量后台处理
5. **结果浏览** — 按类别筛选，查看统计，导出 CSV

## 项目结构

```
image-filter-java/
├── start.sh                  # 一键启动（自动检测环境 + 编译 + 运行）
├── setup.sh                  # 环境检测
├── pom.xml
├── img/                      # 默认图片目录
├── filter_results/           # CSV 导出目录
└── src/main/
    ├── java/com/example/imagefilter/
    │   ├── ImageFilterApplication.java   # 主入口
    │   ├── config/
    │   │   ├── AppConfig.java            # 应用配置
    │   │   └── AsyncConfig.java          # 异步线程池
    │   ├── controller/
    │   │   └── FilterController.java     # 全部 API 端点
    │   ├── model/
    │   │   ├── AnalysisResult.java       # 分析结果实体
    │   │   └── PredefinedPrompt.java     # 预设提示词
    │   └── service/
    │       ├── OllamaService.java        # Ollama API 通信
    │       ├── BatchTaskService.java     # 后台批量处理
    │       ├── ImageService.java         # 图片扫描
    │       ├── StateService.java         # 内存状态管理
    │       └── CsvExportService.java     # CSV 导出
    └── resources/
        ├── application.properties
        ├── templates/index.html          # 主页面
        └── static/
            ├── css/style.css
            └── js/app.js                 # 前端交互
```

## API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/` | 主页面 |
| POST | `/api/connect-test` | 测试 Ollama 连接 |
| POST | `/api/refresh-models` | 刷新模型列表 |
| POST | `/api/scan` | 扫描图片目录 |
| POST | `/api/preview` | 预览图片（含缓存结果） |
| POST | `/api/analyze` | 单张异步分析 |
| POST | `/api/batch` | 启动后台批量任务 |
| POST | `/api/batch/status` | 查询批量任务进度 |
| POST | `/api/categories` | 获取分类列表 |
| POST | `/api/filter` | 按类别筛选结果 |
| POST | `/api/export` | 导出筛选结果为 CSV |
| GET | `/api/download/{filename}` | 下载导出的 CSV |
| POST | `/api/clear` | 清除所有结果 |

## 提示词预设

| 预设 | 输出 |
|------|------|
| 通用描述 | 中文场景描述 |
| 质量筛选 | GOOD / OK / BAD |
| 文档判断 | DOCUMENT / NOT_DOCUMENT |
| 照片判断 | PHOTO / NOT_PHOTO |
| 人物检测 | HAS_PERSON / NO_PERSON |
| 亮度分类 | BRIGHT / NORMAL / DARK |
| 色彩判断 | COLORFUL / MONOCHROME / SEPIA |
| 截图检测 | SCREENSHOT / NOT_SCREENSHOT |
| 自然场景 | NATURE / NOT_NATURE |
| ✏️ 自定义 | 自由输入 |

在 [PredefinedPrompt.java](src/main/java/com/example/imagefilter/model/PredefinedPrompt.java) 中添加更多预设。

## 线程架构

```
浏览器 ──HTTP──→ Tomcat线程 ──→ 提交到 ollamaExecutor ──→ 立即释放
                  （释放）          ↓
                              Ollama 推理 (10~60s)
                                    ↓
                              CompletableFuture 完成
                                    ↓
                              另一个 Tomcat 线程返回结果
```

批量处理额外运行在 `batchExecutor`，进度通过 `/api/batch/status` 轮询。

## 配置

[application.properties](src/main/resources/application.properties)：

```properties
server.port=8080                  # 服务端口
app.image-dir=./img               # 图片目录
app.default-model=llava           # 默认模型
app.default-temperature=0.1       # 模型温度
server.tomcat.threads.max=200     # Tomcat 最大线程数
```
