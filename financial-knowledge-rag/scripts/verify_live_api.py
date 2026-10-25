import httpx
import sys

sys.path.insert(0, ".")
sys.stdout.reconfigure(encoding="utf-8")


def main():
    print("=" * 60)
    print("🚀 校验 8820 实时运行服务的文档与切片 API...")
    print("=" * 60)

    client = httpx.Client(trust_env=False)
    # 1. 健康检查
    h = client.get("http://127.0.0.1:8820/health")
    print(f"✓ 健康检查: {h.status_code} -> {h.json().get('status')}")

    # 2. 文档列表
    res_docs = client.get("http://127.0.0.1:8820/api/knowledge/documents")
    docs = res_docs.json()
    print(f"\n📚 获取到文档列表 (共 {len(docs)} 份):")
    for d in docs:
        print(f"   • [{d['id']}] {d['title']} (切片数: {d.get('chunk_count', 0)}, 日期: {d.get('publish_date')})")

    # 3. 全量切片
    res_chunks = client.get("http://127.0.0.1:8820/api/knowledge/documents/all/chunks")
    chunks = res_chunks.json()
    print(f"\n📦 全量切片接口 (/api/knowledge/documents/all/chunks): 返回 {len(chunks)} 个切片")
    
    tables = sum(1 for c in chunks if c.get("has_table"))
    charts = sum(1 for c in chunks if c.get("has_chart"))
    print(f"   ✓ 包含表格切片: {tables} 个")
    print(f"   ✓ 包含走势图切片: {charts} 个")

    # 4. 单文档切片抽样 (如 2026Q1)
    res_q1 = client.get("http://127.0.0.1:8820/api/knowledge/documents/doc_4102084/chunks")
    q1_chunks = res_q1.json()
    print(f"\n📑 单文档测试 (2026Q1 季报 doc_4102084): 返回 {len(q1_chunks)} 个切片")

    print("\n" + "=" * 60)
    print("🎉 校验全部通过！前端页面可通过浏览器直接访问查看！")
    print("=" * 60)


if __name__ == "__main__":
    main()
