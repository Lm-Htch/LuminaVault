import base64
import io
import json
import sys
import urllib.request

from PIL import Image

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8000"


def post(path: str, payload: dict) -> dict:
    request = urllib.request.Request(
        BASE + path,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read().decode("utf-8"))


def main() -> None:
    image = Image.new("RGB", (64, 64), (30, 120, 200))
    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    payload = {
        "image": base64.b64encode(buffer.getvalue()).decode("ascii"),
        "content_type": "image/png",
    }

    with urllib.request.urlopen(BASE + "/health", timeout=10) as response:
        print("health:", response.read().decode("utf-8"))

    tags = post("/v1/tag", payload)
    print("tag:", json.dumps(tags, ensure_ascii=False)[:200])

    embedding = post("/v1/embed", payload)
    values = embedding.get("embedding", [])
    norm = sum(value * value for value in values) ** 0.5
    print(f"embed: model={embedding.get('model')} dims={len(values)} norm~{norm:.4f}")


if __name__ == "__main__":
    main()
