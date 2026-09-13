import json
from collections import Counter

with open(r'd:\BaiduSyncdisk\IdeaProjects\FinancialCopilot\scripts\data_sync\market_basics_data.json', encoding='utf-8') as f:
    d = json.load(f)

print('Meta:', d['meta'])
print()

# 股票统计
print('--- 股票概览 ---')
print(f"  总数: {len(d['stocks'])}")
exchanges = Counter(s['exchange'] for s in d['stocks'])
for ex, cnt in exchanges.most_common():
    print(f"  {ex:6s}: {cnt}")
print(f"  有行业: {sum(1 for s in d['stocks'] if s.get('industry') and s['industry'] != '未分类')}")
print(f"  有市值: {sum(1 for s in d['stocks'] if s.get('market_cap_billion', 0) > 0)}")
print()

print('--- 股票 Top 10 (按市值) ---')
for s in sorted(d['stocks'], key=lambda x: -x.get('market_cap_billion', 0))[:10]:
    print(f"  {s['code']:10s} {s['name']:15s} {s['exchange']:5s} 市值:{s['market_cap_billion']:.0f}亿 PE:{s.get('pe_ttm', 0):.1f} PB:{s.get('pb', 0):.1f}")
print()

print('--- 行业分布 Top 15 ---')
ind_counts = Counter(s.get('industry', '未分类') for s in d['stocks'])
for ind, cnt in ind_counts.most_common(15):
    print(f"  {ind:20s} {cnt:4d}")
print()

# 期货统计
print('--- 期货概览 ---')
print(f"  总数: {len(d['futures'])}")
cats = Counter(f['category'] for f in d['futures'])
for cat, cnt in cats.most_common():
    print(f"  {cat:10s}: {cnt}")
exs = Counter(f['exchange'] for f in d['futures'])
for ex, cnt in exs.most_common():
    print(f"  {ex:6s}: {cnt}")
print()

# 理财统计
print('--- 理财产品 ---')
for wp in d['wealth_products'][:5]:
    print(f"  {wp['product_code']} {wp['product_name']:20s} {wp['bank_name']:10s} {wp['risk_level']} {wp['expected_annual_return']}%")
print(f"  ... 共 {len(d['wealth_products'])} 个")
print()

# 企业统计
print('--- 上市企业 ---')
print(f"  总数: {len(d['enterprises'])}")
print(f"  有市值: {sum(1 for e in d['enterprises'] if e.get('market_cap_billion', 0) > 0)}")
