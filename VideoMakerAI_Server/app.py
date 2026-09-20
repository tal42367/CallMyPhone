import json
import os
import shutil
import subprocess
import sys
import threading
import time
import uuid
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor

from fastapi import FastAPI, UploadFile, File, Form, Header, HTTPException
from fastapi.responses import FileResponse

BASE = Path(os.getenv("APP_HOME", "/app"))
DATA = BASE / "data"
UPLOADS = DATA / "uploads"
OUTPUTS = DATA / "outputs"
JOBS = DATA / "jobs"
LTX_REPO = Path(os.getenv("LTX_REPO", "/opt/LTX-Video"))
API_KEY = os.getenv("API_KEY", "").strip()

for p in (UPLOADS, OUTPUTS, JOBS):
    p.mkdir(parents=True, exist_ok=True)

app = FastAPI(title="VideoMaker AI GPU Server", version="1.0.0")
executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="gpu-worker")
lock = threading.Lock()

FRAMES = {5: 121, 10: 241}
FPS = 24
WIDTH = int(os.getenv("VIDEO_WIDTH", "704"))
HEIGHT = int(os.getenv("VIDEO_HEIGHT", "480"))
CONFIG = os.getenv("LTX_CONFIG", "configs/ltxv-2b-0.9.8-distilled.yaml")


def auth(x_api_key: str | None):
    if API_KEY and x_api_key != API_KEY:
        raise HTTPException(status_code=401, detail="Invalid API key")


def job_path(job_id: str) -> Path:
    return JOBS / f"{job_id}.json"


def load_job(job_id: str):
    p = job_path(job_id)
    if not p.exists():
        return None
    return json.loads(p.read_text(encoding="utf-8"))


def save_job(job: dict):
    with lock:
        job["updated_at"] = time.time()
        job_path(job["id"]).write_text(
            json.dumps(job, ensure_ascii=False, indent=2), encoding="utf-8"
        )


def update_job(job_id: str, **changes):
    with lock:
        p = job_path(job_id)
        job = json.loads(p.read_text(encoding="utf-8"))
        job.update(changes)
        job["updated_at"] = time.time()
        p.write_text(json.dumps(job, ensure_ascii=False, indent=2), encoding="utf-8")


def run_generation(job_id: str, image_path: str, prompt: str, duration: int):
    work_dir = OUTPUTS / f"{job_id}_work"
    final_mp4 = OUTPUTS / f"{job_id}.mp4"
    work_dir.mkdir(parents=True, exist_ok=True)

    try:
        update_job(job_id, status="running", progress=3)

        cmd = [
            sys.executable,
            str(LTX_REPO / "inference.py"),
            "--prompt", prompt,
            "--conditioning_media_paths", image_path,
            "--conditioning_strengths", "1.0",
            "--conditioning_start_frames", "0",
            "--height", str(HEIGHT),
            "--width", str(WIDTH),
            "--num_frames", str(FRAMES[duration]),
            "--frame_rate", str(FPS),
            "--seed", os.getenv("VIDEO_SEED", "42"),
            "--pipeline_config", CONFIG,
            "--output_path", str(work_dir),
        ]

        proc = subprocess.Popen(
            cmd,
            cwd=str(LTX_REPO),
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
        )

        progress = 8
        if proc.stdout:
            for line in proc.stdout:
                print(line, end="", flush=True)
                if progress < 92:
                    progress += 1
                    update_job(job_id, progress=progress)

        rc = proc.wait()
        if rc != 0:
            raise RuntimeError(f"LTX inference exited with code {rc}")

        videos = sorted(work_dir.glob("*.mp4"), key=lambda p: p.stat().st_mtime)
        if not videos:
            videos = sorted(work_dir.rglob("*.mp4"), key=lambda p: p.stat().st_mtime)
        if not videos:
            raise RuntimeError("LTX finished but no MP4 was produced")

        shutil.move(str(videos[-1]), str(final_mp4))
        shutil.rmtree(work_dir, ignore_errors=True)

        update_job(job_id, status="completed", progress=100, error=None)
    except Exception as exc:
        update_job(job_id, status="failed", error=str(exc))


@app.get("/")
def root():
    return {
        "name": "VideoMaker AI GPU Server",
        "ok": True,
        "engine": "LTX-Video 2B distilled",
    }


@app.get("/health")
def health():
    return {
        "ok": True,
        "cuda_visible_devices": os.getenv("CUDA_VISIBLE_DEVICES", "default"),
        "ltx_repo_exists": LTX_REPO.exists(),
        "durations": [5, 10],
        "fps": FPS,
        "resolution": f"{WIDTH}x{HEIGHT}",
    }


@app.post("/jobs")
async def create_job(
    image: UploadFile = File(...),
    prompt: str = Form(...),
    consent: bool = Form(False),
    duration: int = Form(10),
    x_api_key: str | None = Header(default=None),
):
    auth(x_api_key)

    if not consent:
        raise HTTPException(status_code=400, detail="Consent confirmation required")
    if duration not in FRAMES:
        raise HTTPException(status_code=400, detail="Duration must be 5 or 10")
    if not image.content_type or not image.content_type.startswith("image/"):
        raise HTTPException(status_code=400, detail="Upload an image")

    raw = await image.read()
    if len(raw) > 20 * 1024 * 1024:
        raise HTTPException(status_code=413, detail="Image too large")

    job_id = uuid.uuid4().hex[:16]
    suffix = Path(image.filename or "input.jpg").suffix.lower()
    if suffix not in {".jpg", ".jpeg", ".png", ".webp"}:
        suffix = ".jpg"

    image_path = UPLOADS / f"{job_id}{suffix}"
    image_path.write_bytes(raw)

    job = {
        "id": job_id,
        "status": "queued",
        "progress": 0,
        "duration": duration,
        "created_at": time.time(),
        "updated_at": time.time(),
        "error": None,
        "video_url": f"/jobs/{job_id}/video",
    }
    save_job(job)

    executor.submit(run_generation, job_id, str(image_path), prompt, duration)
    return job


@app.get("/jobs/{job_id}")
def get_job(job_id: str, x_api_key: str | None = Header(default=None)):
    auth(x_api_key)
    job = load_job(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="Job not found")
    return job


@app.get("/jobs/{job_id}/video")
def get_video(job_id: str, x_api_key: str | None = Header(default=None)):
    auth(x_api_key)
    job = load_job(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="Job not found")
    if job.get("status") != "completed":
        raise HTTPException(status_code=409, detail="Video not ready")

    p = OUTPUTS / f"{job_id}.mp4"
    if not p.exists():
        raise HTTPException(status_code=404, detail="MP4 missing")

    return FileResponse(p, media_type="video/mp4", filename=f"VideoMakerAI-{job_id}.mp4")
