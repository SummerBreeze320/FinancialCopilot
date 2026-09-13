import os, sys, json, time
WIND_X64 = r"D:\Wind\Wind.NET.Client\WindNET\x64"
WIND_BIN = r"D:\Wind\Wind.NET.Client\WindNET\bin"
for p in [WIND_X64, WIND_BIN]:
    if p not in sys.path:
        sys.path.insert(0, p)
os.environ["PATH"] = WIND_X64 + ";" + WIND_BIN + ";" + os.environ.get("PATH", "")

from WindPy import w
w.start()

# Scan fund codes 000001-020000.OF, classify by fund_investtype
# Each batch: 50 codes via wss
BATCH = 50
MAX_CODE = 12000  # enough to cover most funds

print(f"Scanning fund codes 000001-{MAX_CODE:06d}.OF, batch={BATCH}")
start_time = time.time()
all_funds = []

for start in range(1, MAX_CODE + 1, BATCH):
    end = min(start + BATCH - 1, MAX_CODE)
    codes = [f"{i:06d}.OF" for i in range(start, end + 1)]
    codes_str = ",".join(codes)
    
    res = w.wss(codes_str, "sec_name,fund_investtype,prt_netasset")
    if res.ErrorCode != 0:
        continue
    
    for i, wcode in enumerate(res.Codes):
        name = res.Data[0][i] if res.Data and i < len(res.Data[0]) else None
        if not name or str(name).strip() in ('', 'None', 'nan'):
            continue
        ftype = str(res.Data[1][i]).strip() if res.Data and i < len(res.Data[1]) else '未知'
        if ftype in ('', 'None', 'nan'):
            ftype = '未知'
        raw_net = res.Data[2][i] if res.Data and i < len(res.Data[2]) else None
        scale = float(raw_net) / 1e8 if raw_net else 0.0
        all_funds.append({
            "wind_code": wcode,
            "fund_code": wcode.split('.')[0],
            "fund_name": str(name).strip(),
            "fund_type": ftype,
            "scale_billion": round(scale, 2),
        })
    
    if start % 2000 == 1:
        elapsed = time.time() - start_time
        print(f"  Progress: {start}/{MAX_CODE} | Found: {len(all_funds)} | {elapsed:.0f}s")

elapsed = time.time() - start_time
print(f"\nScan done in {elapsed:.0f}s. Total valid funds: {len(all_funds)}")

# Stats by type
from collections import Counter
type_counts = Counter(f["fund_type"] for f in all_funds)
print(f"\nFund type distribution:")
for ftype, count in type_counts.most_common():
    print(f"  {ftype:30s}  {count:5d}")

# Save
out = os.path.join(os.path.dirname(__file__), "all_fund_scan.json")
with open(out, "w", encoding="utf-8") as f:
    json.dump(all_funds, f, ensure_ascii=False, indent=2)
print(f"\nSaved to: {out}")

w.stop()
