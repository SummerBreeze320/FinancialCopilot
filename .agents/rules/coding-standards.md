# 项目开发与编码规范约束 (Coding Standards)

## 1. 核心库与注解偏好
- **Lombok**：优先使用 Lombok 消除样板代码（如 `@Data`, `@Getter`, `@Setter`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor`, `@Slf4j` 等）。
- **MyBatis-Plus**：数据访问与持久化层统一使用 MyBatis-Plus 规范（BaseMapper、IService、LambdaQueryWrapper 等）。

## 2. 注释规范
- **Javadoc 中文注释**：所有类、接口、公共方法、核心算法、关键字段均必须提供详尽的中文 Javadoc 注释（包含业务意图、参数说明 `@param`、返回值 `@return` 及异常说明 `@throws`）。

## 3. 领域划分与多资产扩展架构
- **聚焦 Fund 领域，兼顾多资产扩展**：
  - 当前投研核心聚焦在 **Fund（公募基金）** 领域。
  - 必须具备清晰的领域特征，针对基金的特定算法、指标计算、专员 Agent、专属模型或产物，必须归置在对应的 `fund` 包下（如 `com.financial.copilot.agent.core.agents.fund.*`、`com.financial.copilot.domain.fund.*` 等）。
  - 同时顶层必须保持良好的抽象架构与门面扩展能力，以便未来无缝兼容 **Stock（个股）**、**Futures（期货）**、**Wealth（银行理财）** 等资产。

## 4. 导包与代码干净度纪律
- **严格在文件顶部完成完整 import**：
  - 严禁在类方法签名、参数列表、返回值或方法体内部使用带包名的完全限定名（如 `java.util.List`、`com.financial...SomeClass`）。
  - 所有使用的类必须在文件顶部通过 `import` 语句统一导入，保持业务代码干净、优雅。
