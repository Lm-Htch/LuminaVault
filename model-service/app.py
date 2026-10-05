import base64
import csv
import hashlib
import io
import os

import numpy as np
from fastapi import FastAPI, HTTPException
from PIL import Image
from pydantic import BaseModel

MODEL_MODE = os.getenv("MODEL_MODE", "fake")
WD14_MODEL_PATH = os.getenv("WD14_MODEL_PATH", "/models/wd14/model.onnx")
WD14_TAGS_PATH = os.getenv("WD14_TAGS_PATH", "/models/wd14/tags.csv")
WD14_INPUT_SIZE = int(os.getenv("WD14_INPUT_SIZE", "448"))
TAG_CONFIDENCE = float(os.getenv("TAG_CONFIDENCE", "0.5"))
MAX_TAGS = int(os.getenv("MAX_TAGS", "64"))
CATEGORY_RATING = 9

app = FastAPI(title="LuminaVault Model Service")

_wd14_session = None
_wd14_labels = None
_wd14_categories = None


class ImageRequest(BaseModel):
    image: str
    content_type: str | None = None


def decode_image(data: str) -> Image.Image:
    try:
        raw = base64.b64decode(data)
        return Image.open(io.BytesIO(raw)).convert("RGB")
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"invalid image: {exc}") from exc


def load_wd14():
    global _wd14_session, _wd14_labels, _wd14_categories
    if _wd14_session is None:
        import onnxruntime as ort

        _wd14_session = ort.InferenceSession(WD14_MODEL_PATH, providers=["CPUExecutionProvider"])
        labels = []
        categories = []
        with open(WD14_TAGS_PATH, "r", encoding="utf-8", newline="") as handle:
            reader = csv.reader(handle)
            next(reader, None)
            for row in reader:
                if len(row) < 3:
                    continue
                labels.append(row[1])
                try:
                    categories.append(int(row[2]))
                except ValueError:
                    categories.append(0)
        _wd14_labels = labels
        _wd14_categories = categories
    return _wd14_session, _wd14_labels, _wd14_categories


def preprocess_wd14(image: Image.Image) -> np.ndarray:
    image = image.convert("RGBA")
    canvas = Image.new("RGBA", image.size, (255, 255, 255, 255))
    canvas.alpha_composite(image)
    image = canvas.convert("RGB")
    max_dim = max(image.size)
    padded = Image.new("RGB", (max_dim, max_dim), (255, 255, 255))
    padded.paste(image, ((max_dim - image.size[0]) // 2, (max_dim - image.size[1]) // 2))
    resized = padded.resize((WD14_INPUT_SIZE, WD14_INPUT_SIZE), Image.BICUBIC)
    array = np.asarray(resized, dtype=np.float32)
    array = array[:, :, ::-1]
    return np.expand_dims(array, 0)


def run_wd14(image: Image.Image):
    session, labels, categories = load_wd14()
    input_name = session.get_inputs()[0].name
    outputs = session.run(None, {input_name: preprocess_wd14(image)})
    logits = np.asarray(outputs[0]).reshape(-1)
    if logits.size > 0 and float(logits.min()) >= 0.0 and float(logits.max()) <= 1.0:
        probabilities = logits
    else:
        probabilities = 1.0 / (1.0 + np.exp(-logits))
    tags = [
        {
            "tag": labels[index],
            "confidence": round(float(probabilities[index]), 4),
            "category": categories[index],
        }
        for index in range(min(len(labels), len(probabilities)))
        if probabilities[index] >= TAG_CONFIDENCE and categories[index] != CATEGORY_RATING
    ]
    tags.sort(key=lambda item: item["confidence"], reverse=True)
    characters = [item["tag"] for item in tags if item["category"] == 4]
    embedding = None
    for output in outputs[1:]:
        array = np.asarray(output).reshape(-1)
        if array.size >= 64:
            norm = float(np.linalg.norm(array)) or 1.0
            embedding = (array / norm).tolist()
            break
    return tags[:MAX_TAGS], characters, embedding


def fake_embedding(image: Image.Image, dims: int = 16) -> list[float]:
    digest = hashlib.sha256(image.tobytes()).digest()
    raw = np.frombuffer(digest[: dims * 2], dtype=np.uint8).astype(np.float32).reshape(dims, 2)
    vector = raw[:, 0] + raw[:, 1] * 256
    norm = float(np.linalg.norm(vector)) or 1.0
    return (vector / norm).tolist()


@app.get("/health")
def health():
    return {
        "status": "UP",
        "mode": MODEL_MODE,
        "tagger": "wd14" if MODEL_MODE == "real" else "fake",
        "embedder": "wd14-image" if MODEL_MODE == "real" else "fake",
    }


@app.post("/v1/tag")
def tag(request: ImageRequest):
    image = decode_image(request.image)
    if MODEL_MODE != "real":
        return {"tags": [], "characters": [], "model": "fake-tagger"}
    tags, characters, _ = run_wd14(image)
    return {"tags": tags, "characters": characters, "model": "wd14"}


@app.post("/v1/embed")
def embed(request: ImageRequest):
    image = decode_image(request.image)
    if MODEL_MODE != "real":
        return {"embedding": fake_embedding(image), "model": "fake-image-16"}
    _, _, embedding = run_wd14(image)
    if embedding is None:
        raise HTTPException(status_code=503, detail="model has no embedding output")
    return {"embedding": embedding, "model": "wd14-image"}


@app.post("/v1/analyze")
def analyze(request: ImageRequest):
    image = decode_image(request.image)
    if MODEL_MODE != "real":
        return {"tags": [], "characters": [], "embedding": fake_embedding(image), "model": "fake-image-16"}
    tags, characters, embedding = run_wd14(image)
    return {"tags": tags, "characters": characters, "embedding": embedding, "model": "wd14-image"}
