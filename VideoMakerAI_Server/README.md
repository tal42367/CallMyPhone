# VideoMaker AI GPU Server

FastAPI server for the Android VideoMaker AI app.

## API
- POST /jobs
- GET /jobs/{id}
- GET /jobs/{id}/video
- GET /health

## Engine
Lightricks LTX-Video 2B 0.9.8 Distilled, image-to-video.

The model weights are **not baked into the Docker image**. LTX downloads the required Hugging Face model files into its cache on first generation. Mount a persistent volume for the Hugging Face cache in production.

## Recommended environment variables
- API_KEY=choose-a-secret
- HF_HOME=/models/huggingface
- VIDEO_WIDTH=704
- VIDEO_HEIGHT=480
- VIDEO_SEED=42

## GPU
A CUDA-capable NVIDIA GPU is required for useful generation speed. For lower-memory GPUs, LTX also supports CPU offloading; this server currently uses the standard 2B distilled config.

## Android app
Enter the public HTTPS URL of this server in the app's "כתובת שרת AI" field.
