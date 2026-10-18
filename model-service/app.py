import base64
import csv
import hashlib
import io
import os
import re

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
RERANK_MODEL_PATH = os.getenv("RERANK_MODEL_PATH", "/models/reranker/model.onnx")
RERANK_TOKENIZER_PATH = os.getenv("RERANK_TOKENIZER_PATH", "/models/reranker/tokenizer.json")
RERANK_MAX_LENGTH = int(os.getenv("RERANK_MAX_LENGTH", "512"))
CLIP_MODEL_PATH = os.getenv("CLIP_MODEL_PATH", "/models/clip/model.onnx")
CLIP_TOKENIZER_PATH = os.getenv("CLIP_TOKENIZER_PATH", "/models/clip/tokenizer.json")
CLIP_INPUT_SIZE = int(os.getenv("CLIP_INPUT_SIZE", "224"))
CLIP_TEXT_MAX_LENGTH = int(os.getenv("CLIP_TEXT_MAX_LENGTH", "77"))
CLIP_MEAN = np.array([0.48145466, 0.4578275, 0.40821073], dtype=np.float32)
CLIP_STD = np.array([0.26862954, 0.26130258, 0.27577711], dtype=np.float32)
OCR_ENABLED = os.getenv("OCR_ENABLED", "auto").lower()

app = FastAPI(title="LuminaVault Model Service")

_wd14_session = None
_wd14_labels = None
_wd14_categories = None
_rerank_session = None
_rerank_tokenizer = None
_clip_session = None
_clip_tokenizer = None
_ocr_engine = None
_ocr_loaded = False

try:
    from rapidocr_onnxruntime import RapidOCR  # type: ignore

    OCR_AVAILABLE = True
except Exception:
    OCR_AVAILABLE = False


class ImageRequest(BaseModel):
    image: str
    content_type: str | None = None


class RerankRequest(BaseModel):
    query: str
    documents: list[str]


class TextRequest(BaseModel):
    text: str


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


def clip_available() -> bool:
    return os.path.exists(CLIP_MODEL_PATH)


def clip_text_available() -> bool:
    return clip_available() and os.path.exists(CLIP_TOKENIZER_PATH)


def load_clip():
    global _clip_session
    if _clip_session is None:
        if not clip_available():
            raise HTTPException(status_code=503, detail="clip model not available")
        import onnxruntime as ort

        _clip_session = ort.InferenceSession(CLIP_MODEL_PATH, providers=["CPUExecutionProvider"])
    return _clip_session


def preprocess_clip(image: Image.Image) -> np.ndarray:
    image = image.convert("RGB")
    width, height = image.size
    scale = CLIP_INPUT_SIZE / min(width, height)
    resized = image.resize((max(1, round(width * scale)), max(1, round(height * scale))), Image.BICUBIC)
    left = (resized.size[0] - CLIP_INPUT_SIZE) // 2
    top = (resized.size[1] - CLIP_INPUT_SIZE) // 2
    cropped = resized.crop((left, top, left + CLIP_INPUT_SIZE, top + CLIP_INPUT_SIZE))
    array = np.asarray(cropped, dtype=np.float32) / 255.0
    array = (array - CLIP_MEAN) / CLIP_STD
    array = np.transpose(array, (2, 0, 1))
    return np.expand_dims(array, 0)


def run_clip(image: Image.Image) -> list[float]:
    session = load_clip()
    input_names = {item.name for item in session.get_inputs()}
    inputs = {}
    if "pixel_values" in input_names:
        inputs["pixel_values"] = preprocess_clip(image)
    if "input_ids" in input_names:
        # 全量 CLIP 需要文本输入；给一组最小合法 token（只取图像塔输出）
        ids = np.zeros((1, 77), dtype=np.int64)
        ids[0, 0] = 49406
        ids[0, 1] = 49407
        mask = np.zeros((1, 77), dtype=np.int64)
        mask[0, 0] = 1
        mask[0, 1] = 1
        inputs["input_ids"] = ids
        inputs["attention_mask"] = mask
    outputs = session.run(None, inputs)
    output_names = [item.name for item in session.get_outputs()]
    candidate = None
    for name, value in zip(output_names, outputs):
        if "image_embeds" in name:
            candidate = value
            break
    if candidate is None:
        for name, value in zip(output_names, outputs):
            if "logits" in name or "text" in name:
                continue
            array = np.asarray(value)
            if array.size >= 128:
                candidate = array
                break
    if candidate is None:
        raise HTTPException(status_code=503, detail="clip model has no image embedding output")
    embedding = np.asarray(candidate).reshape(-1).astype(np.float32)
    norm = float(np.linalg.norm(embedding)) or 1.0
    return (embedding / norm).tolist()


def load_clip_tokenizer():
    global _clip_tokenizer
    if _clip_tokenizer is None:
        if not clip_text_available():
            raise HTTPException(status_code=503, detail="clip tokenizer not available")
        from tokenizers import Tokenizer

        _clip_tokenizer = Tokenizer.from_file(CLIP_TOKENIZER_PATH)
    return _clip_tokenizer


def run_clip_text(text: str) -> list[float]:
    """CLIP 文本塔：tokenize 后取 text_embeds（全量模型需一并给图像输入，给零图像即可）。"""
    session = load_clip()
    tokenizer = load_clip_tokenizer()
    encoding = tokenizer.encode(text)
    ids = list(encoding.ids[:CLIP_TEXT_MAX_LENGTH])
    mask = [1] * len(ids)
    if len(ids) < CLIP_TEXT_MAX_LENGTH:
        pad = CLIP_TEXT_MAX_LENGTH - len(ids)
        ids += [0] * pad
        mask += [0] * pad
    input_names = {item.name for item in session.get_inputs()}
    inputs = {}
    if "input_ids" in input_names:
        inputs["input_ids"] = np.array([ids], dtype=np.int64)
    if "attention_mask" in input_names:
        inputs["attention_mask"] = np.array([mask], dtype=np.int64)
    if "pixel_values" in input_names:
        inputs["pixel_values"] = np.zeros((1, 3, CLIP_INPUT_SIZE, CLIP_INPUT_SIZE), dtype=np.float32)
    outputs = session.run(None, inputs)
    output_names = [item.name for item in session.get_outputs()]
    candidate = None
    for name, value in zip(output_names, outputs):
        if "text_embeds" in name:
            candidate = value
            break
    if candidate is None:
        raise HTTPException(status_code=503, detail="clip model has no text embedding output")
    embedding = np.asarray(candidate).reshape(-1).astype(np.float32)
    norm = float(np.linalg.norm(embedding)) or 1.0
    return (embedding / norm).tolist()


def fake_text_embedding(text: str, dims: int = 512) -> list[float]:
    digest = hashlib.sha256(text.encode("utf-8")).digest()
    seed = np.frombuffer(digest, dtype=np.uint8).astype(np.float32)
    raw = np.resize(seed, dims * 2).reshape(dims, 2)
    vector = raw[:, 0] + raw[:, 1] * 256
    norm = float(np.linalg.norm(vector)) or 1.0
    return (vector / norm).tolist()


def reranker_available() -> bool:
    return os.path.exists(RERANK_MODEL_PATH) and os.path.exists(RERANK_TOKENIZER_PATH)


def load_reranker():
    global _rerank_session, _rerank_tokenizer
    if _rerank_session is None:
        if not reranker_available():
            raise HTTPException(status_code=503, detail="reranker model not available")
        import onnxruntime as ort
        from tokenizers import Tokenizer

        _rerank_session = ort.InferenceSession(RERANK_MODEL_PATH, providers=["CPUExecutionProvider"])
        _rerank_tokenizer = Tokenizer.from_file(RERANK_TOKENIZER_PATH)
        _rerank_tokenizer.enable_truncation(max_length=RERANK_MAX_LENGTH)
    return _rerank_session, _rerank_tokenizer


def run_rerank(query: str, documents: list[str]) -> list[float]:
    session, tokenizer = load_reranker()
    input_names = {item.name for item in session.get_inputs()}
    scores: list[float] = []
    for document in documents:
        encoding = tokenizer.encode(query, document)
        arrays = {
            "input_ids": np.array([encoding.ids], dtype=np.int64),
            "attention_mask": np.array([encoding.attention_mask], dtype=np.int64),
            "token_type_ids": np.array([encoding.type_ids], dtype=np.int64),
        }
        inputs = {name: arrays[name] for name in arrays if name in input_names}
        output = np.asarray(session.run(None, inputs)[0]).reshape(-1)
        logit = float(output[0]) if output.size else 0.0
        scores.append(1.0 / (1.0 + float(np.exp(-logit))))
    return scores


def fake_rerank_score(query: str, document: str) -> float:
    normalized_query = query.lower().strip()
    normalized_document = document.lower()
    if not normalized_query:
        return 0.0
    if normalized_query in normalized_document:
        return 0.95
    pattern = r"[a-z0-9]+|[\u4e00-\u9fff]"
    query_tokens = set(re.findall(pattern, normalized_query))
    if not query_tokens:
        return 0.0
    document_tokens = set(re.findall(pattern, normalized_document))
    return round(0.8 * len(query_tokens & document_tokens) / len(query_tokens), 4)


def run_ocr(image: Image.Image) -> str | None:
    global _ocr_engine, _ocr_loaded
    if OCR_ENABLED == "false" or not OCR_AVAILABLE:
        return None
    try:
        if not _ocr_loaded:
            _ocr_engine = RapidOCR()
            _ocr_loaded = True
        result, _ = _ocr_engine(np.asarray(image))
        if not result:
            return None
        lines = [item[1].strip() for item in result if len(item) > 1 and str(item[1]).strip()]
        text = " ".join(lines).strip()
        return text or None
    except Exception:
        return None


@app.get("/health")
def health():
    return {
        "status": "UP",
        "mode": MODEL_MODE,
        "tagger": "wd14" if MODEL_MODE == "real" else "fake",
        "embedder": "wd14-image" if MODEL_MODE == "real" else "fake",
        "reranker": "cross-encoder" if reranker_available() else "unavailable",
        "clip": "clip-vit-b32" if clip_available() else "unavailable",
        "clip_text": "clip-vit-b32" if clip_text_available() else "unavailable",
        "ocr": "rapidocr" if OCR_AVAILABLE else "unavailable",
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


@app.post("/v1/clip-embed")
def clip_embed(request: ImageRequest):
    image = decode_image(request.image)
    if MODEL_MODE != "real":
        return {"embedding": fake_embedding(image, 512), "model": "fake-clip-512"}
    return {"embedding": run_clip(image), "model": "clip-vit-b32"}


@app.post("/v1/clip-text-embed")
def clip_text_embed(request: TextRequest):
    text = request.text.strip() if request.text else ""
    if not text:
        raise HTTPException(status_code=400, detail="text is empty")
    if MODEL_MODE != "real":
        return {"embedding": fake_text_embedding(text), "model": "fake-clip-text-512"}
    return {"embedding": run_clip_text(text), "model": "clip-vit-b32-text"}


@app.post("/v1/analyze")
def analyze(request: ImageRequest):
    image = decode_image(request.image)
    if MODEL_MODE != "real":
        return {"tags": [], "characters": [], "embedding": fake_embedding(image),
                "ocr_text": None, "model": "fake-image-16"}
    tags, characters, embedding = run_wd14(image)
    return {"tags": tags, "characters": characters, "embedding": embedding,
            "ocr_text": run_ocr(image), "model": "wd14-image"}


@app.post("/v1/rerank")
def rerank(request: RerankRequest):
    if not request.documents:
        return {"scores": [], "model": "none"}
    if MODEL_MODE != "real":
        return {"scores": [fake_rerank_score(request.query, doc) for doc in request.documents],
                "model": "fake-rerank"}
    return {"scores": run_rerank(request.query, request.documents), "model": "cross-encoder"}
