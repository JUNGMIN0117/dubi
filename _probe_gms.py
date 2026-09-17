import json
import urllib.error
import urllib.request
from pathlib import Path

values = {}
for raw in Path("infra/.env").read_text(encoding="utf-8-sig").splitlines():
    line = raw.strip()
    if line and not line.startswith("#") and "=" in line:
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip("'\"")

base = "https://gms.ssafy.io/gmsapi/"
for suffix in ("", "models", "v1/models", "api.openai.com/v1/models"):
    request = urllib.request.Request(base + suffix, headers={"Authorization": "Bearer " + values["GMS_API_KEY"], "Accept": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            body = response.read(2000).decode("utf-8", "replace")
            print(json.dumps({"path": suffix, "status": response.status, "contentType": response.headers.get("Content-Type"), "bodyPrefix": body}, ensure_ascii=False))
    except urllib.error.HTTPError as error:
        body = error.read(1000).decode("utf-8", "replace")
        print(json.dumps({"path": suffix, "status": error.code, "contentType": error.headers.get("Content-Type"), "bodyPrefix": body}, ensure_ascii=False))
    except Exception as error:
        print(json.dumps({"path": suffix, "errorType": type(error).__name__}, ensure_ascii=False))

for model in ("gpt-4o-mini", "gpt-4.1-mini"):
    payload = json.dumps({"model": model, "messages": [{"role": "user", "content": "Reply only OK"}], "max_tokens": 4, "temperature": 0}).encode()
    request = urllib.request.Request(base + "api.openai.com/v1/chat/completions", data=payload,
        headers={"Authorization": "Bearer " + values["GMS_API_KEY"], "Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            result = json.loads(response.read().decode())
            print(json.dumps({"model": model, "status": response.status, "responseModel": result.get("model"), "hasChoices": bool(result.get("choices"))}))
            break
    except urllib.error.HTTPError as error:
        print(json.dumps({"model": model, "status": error.code, "bodyPrefix": error.read(500).decode("utf-8", "replace")}, ensure_ascii=False))
