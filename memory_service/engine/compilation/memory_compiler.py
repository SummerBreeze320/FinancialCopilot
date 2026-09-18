import json
from typing import Any
import tiktoken
from memory_service.domain.enums import MemoryType
from memory_service.domain.memory_pack import MemoryPack


class MemoryCompiler:
    """
    预编译离线物化器：
    将结构化记忆（事实或过程性经验）在入库前编译为：
    1. compact_text: 高密度的精简文本（注：对于 PROCEDURE 绝不裁剪核心安全与适用前提）
    2. standard_text: 结构完整的标准文本
    3. retrieval_text: 优化向量化与检索的关键词/上下文文本
    4. token_count: 预先计算好的精确 token 数（使在线 Reader 拼装时零开销完成预算控制）
    """

    def __init__(self, encoding_name: str = "cl100k_base"):
        try:
            self._encoding = tiktoken.get_encoding(encoding_name)
        except Exception:
            self._encoding = None

    def count_tokens(self, text: str) -> int:
        """精确计算文本 Token 数，降级时采用字符估算"""
        if not text:
            return 0
        if self._encoding:
            return len(self._encoding.encode(text))
        return max(1, len(text) // 3)

    def compile(
        self,
        memory_id: str,
        version: int,
        memory_type: MemoryType,
        content: str,
        data_json: dict[str, Any],
    ) -> MemoryPack:
        """根据记忆类型与结构化载荷，编译出物化包"""
        if memory_type in (MemoryType.FACT, MemoryType.PREFERENCE):
            return self._compile_fact_or_preference(
                memory_id=memory_id,
                version=version,
                memory_type=memory_type,
                content=content,
                data_json=data_json,
            )
        elif memory_type == MemoryType.PROCEDURE:
            return self._compile_procedure(
                memory_id=memory_id,
                version=version,
                content=content,
                data_json=data_json,
            )
        else:
            # 通用回退编译
            compact_text = f"[{memory_type.value}] {content}"
            standard_text = f"Type: {memory_type.value}\nContent: {content}"
            retrieval_text = f"{memory_type.value} {content}"
            tokens = self.count_tokens(compact_text)
            return MemoryPack(
                memory_id=memory_id,
                version=version,
                compact_text=compact_text,
                standard_text=standard_text,
                retrieval_text=retrieval_text,
                token_count=tokens,
            )

    def _compile_fact_or_preference(
        self,
        memory_id: str,
        version: int,
        memory_type: MemoryType,
        content: str,
        data_json: dict[str, Any],
    ) -> MemoryPack:
        subject = data_json.get("subject", "user")
        predicate = data_json.get("predicate", "attribute")
        object_val = data_json.get("value", data_json.get("object_value", ""))
        if isinstance(object_val, (dict, list)):
            val_str = json.dumps(object_val, ensure_ascii=False)
        else:
            val_str = str(object_val)

        prefix = "FACT" if memory_type == MemoryType.FACT else "PREF"
        compact_text = f"[{prefix}] {subject}.{predicate}={val_str}"

        standard_lines = [
            f"Type: {memory_type.value}",
            f"Subject: {subject}",
            f"Predicate: {predicate}",
            f"Value: {val_str}",
            f"Summary: {content}",
        ]
        context_keys = data_json.get("context_keys")
        if context_keys:
            standard_lines.append(f"Context: {context_keys}")

        standard_text = "\n".join(standard_lines)
        retrieval_text = f"{subject} {predicate} {val_str} {content}"

        tokens = self.count_tokens(compact_text)
        return MemoryPack(
            memory_id=memory_id,
            version=version,
            compact_text=compact_text,
            standard_text=standard_text,
            retrieval_text=retrieval_text,
            token_count=tokens,
        )

    def _compile_procedure(
        self,
        memory_id: str,
        version: int,
        content: str,
        data_json: dict[str, Any],
    ) -> MemoryPack:
        task_type = data_json.get("task_type", "general")
        trigger = data_json.get("trigger_conditions", {})
        preconditions = data_json.get("preconditions", [])
        steps = data_json.get("steps", [])
        applicability = data_json.get("applicability", {})
        failure_sigs = data_json.get("failure_signatures", [])
        recovery = data_json.get("recovery_actions", [])

        # 编译 compact_text: 严格保留安全/前置条件与恢复动作
        pre_str = "; ".join(preconditions) if preconditions else "None"
        app_str = json.dumps(applicability, ensure_ascii=False) if applicability else "None"
        steps_summary = " -> ".join(
            [s.get("description", s.get("action", f"Step{i+1}")) if isinstance(s, dict) else str(s)
             for i, s in enumerate(steps)]
        )
        rec_summary = " | ".join(
            [f"If {r.get('on_error')}: {r.get('action')}" if isinstance(r, dict) else str(r)
             for r in recovery]
        ) if recovery else "None"

        compact_text = (
            f"[PROC] task={task_type} | pre=[{pre_str}] | app=[{app_str}] | "
            f"flow=[{steps_summary}] | recovery=[{rec_summary}]"
        )

        # 编译 standard_text: 全格式结构化
        standard_lines = [
            f"Procedure: {task_type}",
            f"Description: {content}",
            f"Preconditions: {json.dumps(preconditions, ensure_ascii=False)}",
            f"Applicability: {json.dumps(applicability, ensure_ascii=False)}",
            f"Trigger Conditions: {json.dumps(trigger, ensure_ascii=False)}",
            "Execution Steps:",
        ]
        for i, step in enumerate(steps, 1):
            if isinstance(step, dict):
                act = step.get("action", "")
                desc = step.get("description", "")
                standard_lines.append(f"  {i}. {act}: {desc}")
            else:
                standard_lines.append(f"  {i}. {step}")

        if failure_sigs:
            standard_lines.append(f"Known Failure Signatures: {json.dumps(failure_sigs, ensure_ascii=False)}")
        if recovery:
            standard_lines.append(f"Recovery Actions: {json.dumps(recovery, ensure_ascii=False)}")

        standard_text = "\n".join(standard_lines)

        # 编译 retrieval_text: 向量检索友好的描述，整合意图、触发和失败特征
        retrieval_text = (
            f"Task: {task_type}. {content}. "
            f"Triggers: {json.dumps(trigger, ensure_ascii=False)}. "
            f"Preconditions: {pre_str}. "
            f"Failures: {json.dumps(failure_sigs, ensure_ascii=False)}"
        )

        tokens = self.count_tokens(compact_text)
        return MemoryPack(
            memory_id=memory_id,
            version=version,
            compact_text=compact_text,
            standard_text=standard_text,
            retrieval_text=retrieval_text,
            token_count=tokens,
        )
