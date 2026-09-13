import json
from collections import Counter

with open(r'd:\BaiduSyncdisk\IdeaProjects\FinancialCopilot\scripts\data_sync\wind_demo_data.json', encoding='utf-8') as f:
    d = json.load(f)

print('Meta:', d['meta'])
print()
print('--- 公司 Top 10 (按规模) ---')
for c in sorted(d['companies'], key=lambda x: -x['total_scale_billion'])[:10]:
    print(f"  {c['short_name']:10s}  规模:{c['total_scale_billion']:.1f}亿  基金:{c['fund_count']}  经理:{c['manager_count']}")
print()
print('--- 经理 Top 10 (按在管规模) ---')
for m in sorted(d['managers'], key=lambda x: -x['current_total_scale_billion'])[:10]:
    print(f"  {m['manager_name']:6s}  {m['education']:4s}  在管:{m['current_total_scale_billion']:.1f}亿  代表作:{m['best_fund_code']}")
print()
print('--- 基金类型分布 ---')
type_counts = Counter(f['fund_type'] for f in d['funds'])
for ftype, count in type_counts.most_common():
    print(f"  {ftype:30s}  {count:4d}")
print()
print('--- 基金 Top 10 (按规模) ---')
for f in sorted(d['funds'], key=lambda x: -x['current_scale_billion'])[:10]:
    print(f"  {f['fund_code']} {f['fund_name']:25s}  {f['fund_type']:20s}  规模:{f['current_scale_billion']:.1f}亿")
print()
print('--- 行业分布 Top 10 ---')
sector_counts = Counter(h['holding_sector'] for h in d['holdings'])
for sector, count in sector_counts.most_common(10):
    print(f"  {sector:20s}  {count} 条")
