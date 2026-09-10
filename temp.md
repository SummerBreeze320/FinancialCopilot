1. 既然钱包什么的功能都实现了  帮我设计一个标准的 用户注册、登录、实名认证、个人资料、用户肖像（偏好）  以及rbac（可以用spring security）
2. 是不是没有做过 prompt Engineering（把背景、限制和示例全部堆进一条 Prompt，模型不一定更稳定。重复信息会增加输入成本，互相冲突的要求还会让输出偏离任务。Prompt 应该写清任务、必要背景、约束和输出格式，其余资料按需进入上下文。一个合格的 Prompt，通常要交代四件事：Role、Task、Context、Format。）
3. context Engineering（Context Engineering 至少管这么几块。

System Prompt 是 API 消息里的高优先级指令。.cursor/rules、.claude/rules、AGENTS.md 等文件是宿主程序读取的规则来源，宿主会按自己的加载规则把其中一部分转换成模型上下文；它们和 API 角色意义上的 System Prompt 不是同一个概念。Cursor 早期使用的 .cursorrules 已属于旧版形式，新项目应使用 .cursor/rules。

User Prompt 是用户输入的业务数据和指令。看起来简单，但真实项目里经常会混着自然语言、业务字段、历史状态、附件内容，处理不好就会把上下文搞脏。

Memory 这块分短期和长期。短期记忆一般是 Session 内的滑动窗口，长期记忆不一定就是向量库——文件、KV、关系库、图数据库、向量检索层都可以。关键问题是：记录什么、什么时候写入、怎么更新、怎么遗忘、召回之后怎么进入当前上下文。

RAG & Tools 也算。RAG 负责检索外部文档把相关内容塞进上下文，Tools 负责把工具描述、参数格式、调用结果挂载进去。RAG 其实可以看成 Context Engineering 的一种具体实现——它回答的是“检索什么、怎么检索、结果怎么放进上下文”这几个问题。

JSON Schema、Function Calling 的参数结构和返回约束会限制当前调用，因此也属于上下文的一部分。工具调用后的 Observation 则要区分：保留原文、写入摘要，还是在后续轮次清理；若不提前设计，解析和回放阶段会留下大量难以处理的结果。

摘要压缩、历史剔除和 Context Caching 都属于 Token 管理手段。它们需要在信息保留与调用成本之间取舍。）
4. Agent skills（Skill 是可被 Agent 发现、按需读取的任务说明。接口返回格式、日志字段、慢 SQL 的排查路径、Review 的关注顺序，都可以写进 SKILL.md。

Skill 本身不提供工具能力。它解决的是“这类任务该按什么规则做”，由宿主在任务命中时把对应说明交给 Agent。）