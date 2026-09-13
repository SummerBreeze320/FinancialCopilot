import os, sys
WIND_X64 = r"D:\Wind\Wind.NET.Client\WindNET\x64"
WIND_BIN = r"D:\Wind\Wind.NET.Client\WindNET\bin"
for p in [WIND_X64, WIND_BIN]:
    if p not in sys.path:
        sys.path.insert(0, p)
os.environ["PATH"] = WIND_X64 + ";" + WIND_BIN + ";" + os.environ.get("PATH", "")

from WindPy import w
w.start()

# Test various Wind commands to get all public fund list
print("=== Test: wset fundfind with correct params ===")

# Try different parameter formats
tests = [
    # fundfind with different param combos
    ("fundfind", "wind_code=;field=wind_code,sec_name"),
    ("fundfind", "fund_type=all;field=wind_code,sec_name"),
    ("fundfind", "field=wind_code,sec_name,fund_type"),
    # sectorconter
    ("sectorconter", "sectorid=1000000040000000;field=wind_code,sec_name"),
    # sectorconstituent with fund-specific sectors
    ("sectorconstituent", "sectorid=2008140000000000;field=wind_code,sec_name"),
    ("sectorconstituent", "sectorid=1000005150000000;field=wind_code,sec_name"),
    # Try wset with SecuritiesReligionFundInfo
    ("SecuritiesReligionFundInfo", "field=wind_code,sec_name"),
    # Try fundbasic
    ("fundbasic", "field=wind_code,sec_name"),
    # Try fundmanagerlist
    ("fundmanagerlist", "field=wind_code,sec_name"),
    # Try listed_republic_fund
    ("listed_republic_fund", "field=wind_code,sec_name"),
    # Try sectorconstituent with all public funds sector
    ("sectorconstituent", "sectorid=1000005151000000;field=wind_code,sec_name"),
    ("sectorconstituent", "sectorid=1000005152000000;field=wind_code,sec_name"),
    ("sectorconstituent", "sectorid=1000005153000000;field=wind_code,sec_name"),
    ("sectorconstituent", "sectorid=1000005154000000;field=wind_code,sec_name"),
    ("sectorconstituent", "sectorid=1000005155000000;field=wind_code,sec_name"),
]

for cmd, params in tests:
    try:
        res = w.wset(cmd, params)
        cnt = 0
        if res.ErrorCode == 0 and res.Data:
            if isinstance(res.Data[0], list):
                cnt = len(res.Data[0])
        print(f"  {cmd}({params}): ErrorCode={res.ErrorCode} count={cnt}")
        if cnt > 0:
            print(f"    sample: {res.Data[0][:3]}")
            if cnt > 10:
                break
    except Exception as e:
        print(f"  {cmd}({params}): Error={e}")

# Test: Try wsd to get fund list via index
print("\n=== Test: wss with fund codes ===")
# Use wss with a range of fund codes to test
res = w.wss("000001.OF,000002.OF,000003.OF,000004.OF,000005.OF", "sec_name,fund_fundstatus")
if res.ErrorCode == 0:
    print(f"  wss test: ErrorCode={res.ErrorCode}")
    for i, code in enumerate(res.Codes):
        name = res.Data[0][i] if res.Data else 'N/A'
        status = res.Data[1][i] if len(res.Data) > 1 else 'N/A'
        print(f"    {code}: {name} status={status}")

w.stop()
