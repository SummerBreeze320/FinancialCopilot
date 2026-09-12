# 架构改进事项与落地状态

- [x] 1. 用户注册、登录、实名认证、个人资料、用户肖像（偏好） 以及 RBAC（Spring Security WebFlux）已全部落地。
- [x] 2. Prompt Engineering：确立 RTCF 模型（Role 角色定位、Task 任务目标、Context 事实槽、Format 输出契约）。消灭混合堆砌，前缀静态化对齐 Context Caching，完成 TaskDecomposer、FundComparator、ReportSynthesizer、MemoryRefinement 标准化重构。
- [x] 3. Context Engineering：
  - System Prompt 与 User Prompt 边界解耦，静态前缀保证缓存命中；
  - 结构化构建器 `StructuredUserPromptBuilder` 实现分槽隔离（Goal / Profile / Skills / Memory / Observations / Constraints）；
  - `ObservationSanitizer` 工具观察值净化治理（清洗 raw JSON，Token 降低 50%~70%，消除幻觉与长括号干扰）；
  - `ContextReducer` 滚动摘要压缩（Rolling Summary）治理短期记忆，避免暴力丢弃；
  - `ContextBudgetManager` 全局 Token 配额预算与自适应截断。
- [x] 4. Agent Skills 体系：
  - 规范以 Markdown/YAML 格式定义 `SKILL.md`（存放于 `classpath:skills/{skill_name}/SKILL.md`）；
  - `SkillRegistry` 自动扫描发现并多维索引；
  - `SkillMatcher` 依据子任务类型与用户 Query 意图按需动态匹配注入，未命中零 Token 占用；
  - 内置首批三大行业技能：`fund-comparison`（两强横向对标四步审计法）、`asset-allocation`（C1-C5 适格投资者核心-卫星配置规范）、`quant-screening`（量化初筛硬性准入与极值风控剔除）。
