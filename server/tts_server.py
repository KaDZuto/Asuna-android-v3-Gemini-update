#!/usr/bin/env python3
"""
Silero TTS HTTP Server для Asuna.
---------------------------------
GET /?text=...&speaker=baya  -> audio/wav (16 kHz)
GET /health                  -> {"status": "ok"} или 503 если silero недоступен

Зависимости: pip install torch omegaconf
Запуск:
    python3 server/tts_server.py --host 0.0.0.0 --port 8000

В приложении Asuna: TTS -> Сетевой Silero, URL http://<IP_ПК>:8000/
"""

import io
import argparse
import wave
import time
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs

SAMPLE_RATE = 48000  # Silero выдаёт 48000 по умолчанию для ru_v3
SPEAKERS = {"baya", "aidar", "kseniya", "xenia", "eugene"}
MAX_TEXT_LEN = 3000

_model = None
_model_error = None


def _load_model():
    """Ленивая загрузка silero-tts; один раз на процесс."""
    global _model, _model_error
    if _model is not None or _model_error is not None:
        return _model
    try:
        import torch  # noqa: F401
        model, _ = __import__("torch").hub.load(
            repo_or_dir="snakers4/silero-models",
            model="silero_tts",
            language="ru",
            speaker="v3_1_ru",
            source="github",
            trust_repo=True,
        )
        _model = model
        return _model
    except Exception as e:
        _model_error = e
        return None


def _synthesize(text: str, speaker: str) -> bytes:
    model = _load_model()
    if model is None:
        raise RuntimeError(
            f"Silero TTS недоступен: {_model_error}. "
            "Установите: pip install torch && python3 -c \"import torch; torch.hub.list('snakers4/silero-models')\""
        )
    audio = model.apply_tts(
        text=text,
        speaker=speaker,
        sample_rate=SAMPLE_RATE,
        put_accent=True,
        put_yo=True,
    )
    # audio — torch tensor float32 mono
    pcm = (audio * 32767).clamp(-32767, 32767).to("int16").cpu().numpy()
    buf = io.BytesIO()
    with wave.open(buf, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(SAMPLE_RATE)
        wf.writeframes(pcm.tobytes())
    return buf.getvalue()


class TtsHandler(BaseHTTPRequestHandler):
    server_version = "AsunaTTS/1.0"
    protocol_version = "HTTP/1.1"

    def _cors_origin(self) -> str:
        return getattr(self.server, "cors_origin", "*")

    def _send(self, code: int, body: bytes, content_type: str = "application/json"):
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", self._cors_origin())
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_header("Access-Control-Allow-Origin", self._cors_origin())
        self.send_header("Access-Control-Allow-Methods", "GET, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):
        parsed = urlparse(self.path)
        params = parse_qs(parsed.query)

        if parsed.path == "/health":
            ok = _model is not None or _model_error is None
            try:
                _load_model()
            except Exception:
                pass
            if _model is not None:
                self._send(200, b'{"status":"ok","service":"silero-tts"}')
            else:
                err = str(_model_error) if _model_error else "not loaded"
                self._send(503, f'{{"status":"error","detail":{err!r}}}'.encode())
            return

        if parsed.path not in ("/", "/tts"):
            self._send(404, b'{"error":"not found"}')
            return

        text = (params.get("text") or [""])[0].strip()
        speaker = (params.get("speaker") or ["baya"])[0].lower()
        if not text:
            self._send(400, b'{"error":"text is empty"}')
            return
        if len(text) > MAX_TEXT_LEN:
            self._send(400, f'{{"error":"text too long (max {MAX_TEXT_LEN})"}}'.encode())
            return
        if speaker not in SPEAKERS:
            self._send(400, f'{{"error":"unknown speaker, allowed: {sorted(SPEAKERS)}"}}'.encode())
            return

        try:
            t0 = time.time()
            wav = _synthesize(text, speaker)
            dt = time.time() - t0
            print(f"[TTS] {len(text)} симв., speaker={speaker}, {dt:.2f}с -> {len(wav)} байт")
            self._send(200, wav, "audio/wav")
        except RuntimeError as e:
            print(f"[TTS] Ошибка: {e}")
            self._send(503, f'{{"error":{str(e)!r}}}'.encode())
        except Exception as e:
            print(f"[TTS] Исключение: {e}")
            self._send(500, f'{{"error":{str(e)!r}}}'.encode())

    def log_message(self, fmt, *args):
        print(f"[TTS] {self.address_string()} - {fmt % args}")


def main():
    parser = argparse.ArgumentParser(description="Silero TTS HTTP server for Asuna")
    parser.add_argument("--host", default="127.0.0.1", help="default: 127.0.0.1")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--cors-origin", default="*")
    args = parser.parse_args()

    httpd = ThreadingHTTPServer((args.host, args.port), TtsHandler)
    httpd.cors_origin = args.cors_origin
    print(f"🔊 Silero TTS сервер: http://{args.host}:{args.port}/?text=...&speaker=baya")
    print(f"Health: http://{args.host}:{args.port}/health")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nОстановка...")
        httpd.server_close()


if __name__ == "__main__":
    main()
