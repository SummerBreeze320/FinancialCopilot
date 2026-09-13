import os, sys
WIND_X64 = r"D:\Wind\Wind.NET.Client\WindNET\x64"
WIND_BIN = r"D:\Wind\Wind.NET.Client\WindNET\bin"
for p in [WIND_X64, WIND_BIN]:
    if p not in sys.path:
        sys.path.insert(0, p)
os.environ["PATH"] = WIND_X64 + ";" + WIND_BIN + ";" + os.environ.get("PATH", "")

from WindPy import w
w.start()

# Method 1: Try wset with "SecuritiesReligionFundInfo" or similar
print("=== Method 1: Try various wset commands ===")
cmds = [
    "SecuritiesReligionFundInfo",
    "FundENFShareChg",
    "FundNavInfo",
    "fundnavinfo",
    "FundFinanceInfo",
    "FundShareChg",
    "FundManager",
    "FundManagerChg",
    "FundNewInfo",
]
for cmd in cmds:
    try:
        res = w.wset(cmd, "field=wind_code,sec_name")
        cnt = 0
        if res.ErrorCode == 0 and res.Data:
            if isinstance(res.Data[0], list):
                cnt = len(res.Data[0])
        print(f"  {cmd}: ErrorCode={res.ErrorCode} count={cnt}")
    except:
        pass

# Method 2: Generate fund codes programmatically (000001-999999 .OF) and test
print("\n=== Method 2: Generate fund codes and test with wss ===")
# Generate codes 000001.OF through 020000.OF (20k codes)
test_codes = [f"{i:06d}.OF" for i in range(1, 50)]
codes_str = ",".join(test_codes[:20])
res = w.wss(codes_str, "sec_name,fund_setupdate")
if res.ErrorCode == 0:
    found = sum(1 for x in res.Data[0] if x and str(x).strip() not in ('', 'None'))
    print(f"  wss {len(test_codes[:20])} codes: {found} valid funds found")
    for i, code in enumerate(res.Codes):
        name = res.Data[0][i] if res.Data else 'N/A'
        if name and str(name).strip() not in ('', 'None'):
            print(f"    {code}: {name}")

# Method 3: wset sectorconstituent with correct fund sector IDs
print("\n=== Method 3: sectorconstituent with fund sector IDs ===")
# Try different sector ID patterns
sector_ids = [
    "1000005200000000",
    "1000005201000000",
    "1000005202000000",
    "1000005203000000",
    "1000005204000000",
    "1000005205000000",
    "1000005206000000",
    "1000005207000000",
    "1000005208000000",
    "1000005209000000",
    "1000005210000000",
    "1000005211000000",
    "1000005212000000",
    "1000005213000000",
    "1000005214000000",
    "1000005215000000",
    "1000005216000000",
    "1000005217000000",
    "1000005218000000",
    "1000005219000000",
    "1000005220000000",
    "2008140000000000",
    "2008140100000000",
    "2008140200000000",
    "2008140300000000",
    "2008140400000000",
    "2008140500000000",
]
for sid in sector_ids:
    res = w.wset("sectorconstituent", f"sectorid={sid};field=wind_code,sec_name")
    cnt = 0
    if res.ErrorCode == 0 and res.Data:
        if isinstance(res.Data[0], list):
            cnt = len(res.Data[0])
    if cnt > 0:
        print(f"  sector {sid}: count={cnt} sample={res.Data[0][:3]}")

# Method 4: Try wset with "FundInfo" or "OpenFundInfo"
print("\n=== Method 4: Try OpenFundInfo / FundInfo ===")
for cmd in ["OpenFundInfo", "FundInfo", "FundList", "fundlist", "OpenEndFund", "ClosedEndFund"]:
    try:
        res = w.wset(cmd, "field=wind_code,sec_name")
        cnt = 0
        if res.ErrorCode == 0 and res.Data:
            if isinstance(res.Data[0], list):
                cnt = len(res.Data[0])
        print(f"  {cmd}: ErrorCode={res.ErrorCode} count={cnt}")
    except:
        pass

w.stop()
