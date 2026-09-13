#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Neo4j 批量导入执行器
====================
通过 cypher-shell 分批执行 Cypher 语句，避免内存溢出。
"""

import subprocess
import sys
import os
import tempfile
from pathlib import Path

CYPHER_FILE = Path(__file__).parent / "neo4j_import.cypher"
NEO4J_USER = "neo4j"
NEO4J_PASS = "123456"
NEO4J_CONTAINER = "docker_neo4j"

def run_cypher_batch(statements, batch_num):
    """执行一批 Cypher 语句"""
    # 写入临时文件
    with tempfile.NamedTemporaryFile(mode='w', suffix='.cypher', delete=False, encoding='utf-8') as f:
        f.write("\n".join(statements))
        tmp_path = f.name

    try:
        # 复制到容器
        container_path = f"/tmp/batch_{batch_num}.cypher"
        subprocess.run(
            ["docker", "cp", tmp_path, f"{NEO4J_CONTAINER}:{container_path}"],
            check=True, capture_output=True
        )

        # 执行
        result = subprocess.run(
            ["docker", "exec", NEO4J_CONTAINER, "cypher-shell",
             "-u", NEO4J_USER, "-p", NEO4J_PASS,
             "--format", "plain", "-f", container_path],
            capture_output=True, text=True, timeout=300
        )

        if result.returncode != 0:
            # 检查是否只是警告
            if "Already exists" in result.stdout or "created" in result.stdout.lower():
                return True, result.stdout[:200]
            return False, f"{result.stderr[:300]}\n{result.stdout[:200]}"
        return True, result.stdout[:200] if result.stdout else "OK"

    except subprocess.TimeoutExpired:
        return False, "Timeout (300s)"
    except Exception as e:
        return False, str(e)
    finally:
        os.unlink(tmp_path)

def main():
    print("=" * 60)
    print("Neo4j 批量导入执行器")
    print("=" * 60)

    if not CYPHER_FILE.exists():
        print(f"错误: {CYPHER_FILE} 不存在")
        return

    # 读取所有 Cypher 语句
    with open(CYPHER_FILE, encoding='utf-8') as f:
        content = f.read()

    # 按分号分割语句（保留注释行）
    raw_lines = content.split('\n')
    statements = []
    current_stmt = []

    for line in raw_lines:
        stripped = line.strip()
        if stripped.startswith('//') or stripped == '':
            if current_stmt:
                current_stmt.append(line)
            continue
        current_stmt.append(line)
        if stripped.endswith(';'):
            statements.append('\n'.join(current_stmt))
            current_stmt = []

    if current_stmt:
        statements.append('\n'.join(current_stmt))

    print(f"总语句数: {len(statements)}")

    # 分批执行 (每批 500 条)
    BATCH_SIZE = 500
    total_batches = (len(statements) + BATCH_SIZE - 1) // BATCH_SIZE
    success_count = 0
    fail_count = 0

    for i in range(0, len(statements), BATCH_SIZE):
        batch_num = i // BATCH_SIZE + 1
        batch = statements[i:i + BATCH_SIZE]

        print(f"\n执行批次 {batch_num}/{total_batches} ({len(batch)} 条)...", flush=True)
        ok, msg = run_cypher_batch(batch, batch_num)

        if ok:
            success_count += len(batch)
            print(f"  成功 (累计 {success_count})", flush=True)
        else:
            fail_count += len(batch)
            print(f"  失败: {msg[:150]}", flush=True)

    print(f"\n{'=' * 60}")
    print(f"导入完成: 成功 {success_count} | 失败 {fail_count}")
    print(f"{'=' * 60}")

    # 验证节点和关系统计
    print("\n验证数据:")
    verify_cypher = """
    MATCH (n) RETURN labels(n)[0] AS Label, count(*) AS Count ORDER BY Count DESC;
    """
    with tempfile.NamedTemporaryFile(mode='w', suffix='.cypher', delete=False, encoding='utf-8') as f:
        f.write(verify_cypher)
        verify_path = f.name

    try:
        subprocess.run(["docker", "cp", verify_path, f"{NEO4J_CONTAINER}:/tmp/verify.cypher"],
                       check=True, capture_output=True)
        result = subprocess.run(
            ["docker", "exec", NEO4J_CONTAINER, "cypher-shell",
             "-u", NEO4J_USER, "-p", NEO4J_PASS,
             "--format", "plain", "-f", "/tmp/verify.cypher"],
            capture_output=True, text=True, timeout=60
        )
        print(result.stdout)
    finally:
        os.unlink(verify_path)

    # 关系统计
    verify_rels = """
    MATCH ()-[r]->() RETURN type(r) AS Type, count(*) AS Count ORDER BY Count DESC;
    """
    with tempfile.NamedTemporaryFile(mode='w', suffix='.cypher', delete=False, encoding='utf-8') as f:
        f.write(verify_rels)
        verify_path = f.name

    try:
        subprocess.run(["docker", "cp", verify_path, f"{NEO4J_CONTAINER}:/tmp/verify2.cypher"],
                       check=True, capture_output=True)
        result = subprocess.run(
            ["docker", "exec", NEO4J_CONTAINER, "cypher-shell",
             "-u", NEO4J_USER, "-p", NEO4J_PASS,
             "--format", "plain", "-f", "/tmp/verify2.cypher"],
            capture_output=True, text=True, timeout=60
        )
        print(result.stdout)
    finally:
        os.unlink(verify_path)

if __name__ == "__main__":
    main()
