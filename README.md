# CareLoop 术后智能随访系统

面向骨科术后出院患者的院外连续照护：**医生在飞书决策，患者打开 App 填报，服务端负责计划调度、可解释分诊与诊前简报**，形成「建档 → 计划 → 采集 → 告警 → 复诊简报」闭环。

> 本系统用于辅助院外随访信息采集与风险提示，**不替代**面诊、影像检查或急诊处置。

**在线演示**

| 入口 | 说明 |
|------|------|
| 患者端 | 打开 CareLoop 患者 App，输入 8 位病患码登录 |
| 服务健康检查 | https://fromfreedom.top/api/health |
| 护士演示台 | https://fromfreedom.top/nurse |

---

## 三端架构

```text
患者端（App）                服务端（Spring Boot）           医护端（飞书）
登录 · 今日 · 随访          计划调度 · 统一随访表            病历建档
设备 · 我的          ◄────► 红黄绿分诊 · 告警推送    ◄────► 离院计划确认
                            诊前 30 秒简报 · 知识库          告警处置 · 简报
```

| 端 | 说明 | 目录 |
|----|------|------|
| 患者端 | UniApp：登录、今日、随访、设备、我的 | `care-patient-app/` |
| 服务端 | REST API、定时任务、飞书回调、知识库 | `care-copilot/` |
| 医护端 | 群机器人 + 交互卡片（建档、计划、告警、简报） | 飞书自建应用 |

---

## 核心能力

- **离院计划生成**：解析病历 / 指令建档，匹配病种包，医生在飞书确认随访天数与表单后生效
- **统一术后随访**：骨科通用项 + 病种专属 + 医生加项；支持分支题、简表、今日跳过
- **红黄绿可解释分诊**：规则与选项信号（`signalMap`）综合判定；黄/红推送飞书卡片供医护处置
- **诊前 30 秒简报**：结论优先、未关闭风险、近况趋势、建议三问
- **病患码登录**：出院确认后发放 8 位专属码，打开 App 凭码绑定，无需单独注册
- **手环数据（演示）**：模拟外设体征写入随访证据，辅助分诊（非医疗器械替代）

---

## 仓库结构

```text
.
├── README.md
├── .gitignore
├── care-copilot/                 # 服务端
│   ├── pom.xml
│   ├── sql/                      # 建库与补丁
│   ├── scripts/                  # 可选工具（读环境变量）
│   └── src/main/
│       ├── java/com/careloop/    # 业务代码
│       └── resources/
│           ├── application.yml   # 仅占位，密钥走环境变量
│           ├── kb/               # 骨科通用 + 病种包 JSON
│           └── static/           # patient / nurse 静态页
└── care-patient-app/             # 患者端 UniApp
    ├── pages/                    # login / today / chat / device / mine
    └── utils/                    # API 与会话
```

---

## 技术栈

| 层级 | 技术 |
|------|------|
| 服务端 | Java 21 · Spring Boot 3 · MySQL 8 · 飞书开放平台 |
| 患者端 | UniApp（Vue） |
| 知识库 | 本地 JSON（`ortho-common` + `diseases/*`）；可选飞书多维表格同步 |

---

## 快速开始

### 1. 环境要求

- JDK 21+
- Maven 3.9+
- MySQL 8（库名建议 `care_copilot`）
- 飞书企业自建应用（机器人、卡片回调等权限已开通）

### 2. 配置环境变量（必填）

**不要**把 App Secret、数据库密码写入仓库。变量名见 [`care-copilot/.env.example`](care-copilot/.env.example)。

至少配置：

```text
FEISHU_APP_ID
FEISHU_APP_SECRET
MYSQL_PASSWORD
FEISHU_DEMO_RECEIVE_ID          # 测试群 chat_id（演示推送用）
```

Windows 可在「系统属性 → 环境变量 → 用户变量」中设置；设置后请**新开终端**再启动服务。

### 3. 初始化数据库

```bash
mysql -uroot -p < care-copilot/sql/schema.sql
# 如有后续补丁
mysql -uroot -p care_copilot < care-copilot/sql/patch_v2.sql
```

### 4. 启动服务端

```bash
cd care-copilot
mvn -DskipTests package
java -jar target/care-copilot-0.0.1-SNAPSHOT.jar
```

默认端口 `8080`。飞书事件回调需公网 HTTPS，可指向已部署域名或本地隧道。

### 5. 患者端

用 HBuilderX 打开 `care-patient-app`，确认 `utils/config.js` 中的 API 根地址后运行到手机 / 模拟器。患者拿到 8 位病患码后，**直接打开 App 登录**即可。

---

## 典型演示链路

1. 飞书测试群：建档 → 确认离院计划 → 获得 8 位病患码  
2. 打开患者 App：登录 → 填写统一随访表并提交  
3. 触发黄/红信号时：飞书出现告警卡片，医护可处置  
4. 生成诊前 30 秒简报，医生在飞书阅读与确认  

---

## 安全说明

- `application.yml` 中的密钥均为 `${ENV:}` 占位，真实值只存在于运行环境
- `.gitignore` 已忽略 `.env`、`application-local.yml`、`application-prod.yml`、`target/` 等
- 切勿将飞书 App Secret、数据库密码提交到 Git；若曾泄露请在飞书后台轮换 Secret

---

## License

本仓库用于学习与竞赛演示。商业或临床正式部署前，请自行完成合规评估与安全加固。
