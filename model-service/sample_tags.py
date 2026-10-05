import argparse
import base64
import io
import json
import urllib.request
from pathlib import Path

from PIL import Image

DEFAULT_BASE = "http://127.0.0.1:8000"


def post(base: str, path: str, payload: dict) -> dict:
    request = urllib.request.Request(
        base + path,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=180) as response:
        return json.loads(response.read().decode("utf-8"))


def main() -> None:
    parser = argparse.ArgumentParser(description="Sample WD14 tags for local images")
    parser.add_argument("paths", nargs="+")
    parser.add_argument("--base", default=DEFAULT_BASE)
    args = parser.parse_args()

    for raw in args.paths:
        path = Path(raw)
        try:
            image = Image.open(path)
        except Exception as exc:
            print(f"== {path.name}: open failed: {exc}")
            continue
        buffer = io.BytesIO()
        image.convert("RGB").save(buffer, format="PNG")
        payload = {
            "image": base64.b64encode(buffer.getvalue()).decode("ascii"),
            "content_type": "image/png",
        }
        try:
            tags = post(args.base, "/v1/tag", payload).get("tags", [])
        except Exception as exc:
            print(f"== {path.name}: tag failed: {exc}")
            continue
        characters = [tag for tag in tags if tag.get("category") == 4]
        general = [tag for tag in tags if tag.get("category") != 4]
        print(f"== {path.name}")
        joined_characters = ", ".join(f"{tag['tag']}({tag['confidence']:.2f})" for tag in characters)
        print(f"   character: {joined_characters or '-'}")
        joined_general = ", ".join(f"{tag['tag']}({tag['confidence']:.2f})" for tag in general[:10])
        print(f"   general:   {joined_general}")


if __name__ == "__main__":
    main()
