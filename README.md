# CareLoop 术后智能随访系统APP

骨科术后院外连续随访：医生在飞书建档与处置，患者用 App / H5 完成统一随访表，服务端负责计划、分诊、告警与诊前简报。

生产地址：`https://fromfreedom.top`  
患者端 H5：https://fromfreedom.top/patient

## 仓库结构

```text
feishu/
├── care-copilot/       # Spring Boot 服务端 + 患者 H5 + 护士演示页
│   ├── src/            # 业务代码
│   ├── src/main/resources/kb/  # 骨科通用 + 病种包知识库
│   └── deploy/         # 部署相关脚本/配置
└── care-patient-app/   # 患者端 UniApp（HBuilderX）
```

## 核心能力

- 统一术后随访（骨科通用 + 病种专属 + 医生加项）
- 红 / 黄 / 绿可解释分诊，飞书告警处置
- 诊前 30 秒简报
- 知识库驱动表单与规则
- 8 位病患码登录（App / H5）

## 本地开发

```bash
# JDK 21+
cd care-copilot
mvn -DskipTests package
# 本机需先配置用户环境变量（见 care-copilot/.env.example 变量名）
java -jar target/care-copilot-0.0.1-SNAPSHOT.jar
```

敏感配置（飞书 App Secret、MySQL 密码等）请写在 **Windows 用户环境变量**，不要提交到仓库。变量名清单见 `care-copilot/.env.example`。

患者 App：用 HBuilderX 打开 `care-patient-app`，API 指向 `https://fromfreedom.top`。

## 技术栈

- 服务端：Java 21 · Spring Boot 3 · MySQL · 飞书开放平台
- 患者端：UniApp（Vue）· 静态 H5
- 知识库：本地 JSON
