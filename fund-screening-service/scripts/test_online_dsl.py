import json
import urllib.request
import time

def test_dsl_online(query: str, previous_dsl: dict = None):
    url = "http://127.0.0.1:8810/v1/fund-screening/commands"
    payload = {"query": query}
    if previous_dsl:
        payload["previousDsl"] = previous_dsl
    
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
    
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=180) as resp:
            elapsed = time.time() - t0
            result = json.loads(resp.read().decode("utf-8"))
            print(f"=== Query: {query} ===", flush=True)
            print(f"Status: {resp.status} | Latency: {elapsed:.2f}s", flush=True)
            print(f"Route: {result.get('route')}", flush=True)
            print(f"Repairs: {result.get('repairs')}", flush=True)
            print(f"Candidate Count: {result.get('candidateCount')}", flush=True)
            print(f"DSL:\n{json.dumps(result.get('dsl'), indent=2, ensure_ascii=False)}", flush=True)
            return result.get('dsl')
    except Exception as e:
        print(f"Error testing {query}: {e}", flush=True)
        return None

if __name__ == "__main__":
    # Test 1: Direct query
    print("\n--- Test 1: Direct Query ---", flush=True)
    test_dsl_online("001938")
    
    # Test 2: Simple screening
    print("\n--- Test 2: Screening Query ---", flush=True)
    dsl1 = test_dsl_online("规模大于10亿且成立超过5年")
    
    # Test 3: Multiturn edit (Take top 50 based on DSL1)
    if dsl1:
        print("\n--- Test 3: Multiturn Incremental Edit ---", flush=True)
        test_dsl_online("改取前50只", previous_dsl=dsl1)
