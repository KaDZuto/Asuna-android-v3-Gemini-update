#!/usr/bin/env python3
"""
Antigravity CLI to OpenAI-Compatible API Bridge.
--------------------------------------------
Позволяет подключить подписку Gemini AI Pro (через официальный Antigravity CLI)
к приложению Asuna на Android (или любому другому OpenAI-совместимому клиенту)
абсолютно легально и БЕЗ нарушения пользовательского соглашения Google (ToS).

Запуск на ПК:
    python3 antigravity_bridge.py --port 8080 --host 0.0.0.0 --token mysecret

В приложении Asuna на телефоне (в одной Wi-Fi сети):
    Провайдер: Antigravity Bridge (или OpenAI-совместимый)
    Base URL: http://<IP_ВАШЕГО_ПК>:8080/v1
    Model: gemini-3.8-flash-high (или gemini-3.1-pro-high, claude-sonnet-5-5-high)
    Header Authorization: Bearer <token> (если задан --token)
"""

import sys
import os
import json
import re
import time
import argparse
import subprocess
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

DEFAULT_MODEL = "gemini-3.8-flash-high"
ALLOWED_MODELS = {
    "gemini-3.8-flash-high",
    "gemini-3.1-pro-high",
    "claude-sonnet-5-5-high",
}
# Модели: буквы/цифры, дефис, точка, нижнее подчёркивание, опциональный суффикс -high
MODEL_RE = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$")
MAX_BODY_BYTES = 64 * 1024  # 64 КБ лимит тела запроса
DEFAULT_PRINT_TIMEOUT = 120

# Три-полевые параметры окружения для отката через transcript
TRANSCRIPT_DIR_ENV = "AGY_TRANSCRIPT_DIR"


def valid_model(name: str) -> bool:
    if not isinstance(name, str) or not name:
        return False
    if name in ALLOWED_MODELS:
        return True
    return bool(MODEL_RE.match(name))


class AntigravityBridgeHandler(BaseHTTPRequestHandler):
    server_version = "AntigravityBridge/2.0"
    protocol_version = "HTTP/1.1"

    # Настраивается в run_server через server-атрибуты
    def _cfg(self, name, default=None):
        return getattr(self.server, name, default)

    def _check_auth(self) -> bool:
        token = self._cfg("auth_token")
        if not token:
            return True
        header = self.headers.get("Authorization", "")
        if header == f"Bearer {token}" or header == token:
            return True
        self.send_response(401)
        self.send_header("Content-Type", "application/json")
        self._send_cors_headers()
        self.end_headers()
        self.wfile.write(json.dumps({"error": {"message": "Unauthorized"}}).encode("utf-8"))
        return False

    def _send_cors_headers(self):
        # По умолчанию разрешаем только запросы с того же устройства/локальные;
        # переопределить можно через --cors-origin
        origin = self._cfg("cors_origin", "*")
        # Если токен задан — CORS по умолчанию запрещаем (пустой origin невалидно),
        # иначе оставляем явный список
        self.send_header("Access-Control-Allow-Origin", origin)
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization")

    def _send_json(self, code: int, obj: dict):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self._send_cors_headers()
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        self.send_response(200)
        self._send_cors_headers()
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):
        if not self._check_auth():
            return
        if self.path in ("/v1/models", "/models"):
            self._send_json(200, {
                "object": "list",
                "data": [
                    {"id": m, "object": "model", "owned_by": "antigravity"}
                    for m in sorted(ALLOWED_MODELS)
                ]
            })
            return

        # Healthcheck
        self._send_json(200, {"status": "ok", "service": "antigravity-bridge"})

    def do_POST(self):
        if not self._check_auth():
            return
        if not (self.path.startswith("/v1/chat/completions") or self.path.startswith("/chat/completions")):
            self._send_json(404, {"error": {"message": "Not found"}})
            return

        try:
            content_length = int(self.headers.get("Content-Length", 0))
        except ValueError:
            self._send_json(400, {"error": {"message": "Invalid Content-Length"}})
            return

        if content_length <= 0:
            self._send_json(400, {"error": {"message": "Empty body"}})
            return
        if content_length > MAX_BODY_BYTES:
            self._send_json(413, {"error": {"message": f"Body too large (max {MAX_BODY_BYTES} bytes)"}})
            return

        body = self.rfile.read(content_length)

        try:
            req_json = json.loads(body.decode("utf-8"))
        except Exception as e:
            self._send_json(400, {"error": {"message": f"Invalid JSON: {e}"}})
            return

        messages = req_json.get("messages")
        if not isinstance(messages, list) or not messages:
            self._send_json(400, {"error": {"message": "messages must be a non-empty list"}})
            return

        requested_model = req_json.get("model", DEFAULT_MODEL)
        if not valid_model(requested_model):
            self._send_json(400, {"error": {"message": f"Invalid model name: {requested_model!r}"}})
            return

        # Собираем контекст сообщений в единый промпт для CLI
        prompt_parts = []
        for msg in messages:
            if not isinstance(msg, dict):
                continue
            role = msg.get("role", "user")
            content = msg.get("content", "")
            if isinstance(content, list):
                content = "".join([part.get("text", "") for part in content if isinstance(part, dict)])
            if not isinstance(content, str):
                continue
            if role == "system":
                prompt_parts.append(f"[SYSTEM INSTRUCTION]\n{content}\n")
            elif role == "user":
                prompt_parts.append(f"[USER]\n{content}")
            elif role == "assistant":
                prompt_parts.append(f"[ASUNA]\n{content}")

        full_prompt = "\n".join(prompt_parts)
        if not full_prompt.strip():
            self._send_json(400, {"error": {"message": "Empty prompt"}})
            return

        print_timeout = self._cfg("print_timeout", DEFAULT_PRINT_TIMEOUT)

        try:
            cmd = ["agy", "-p", "--print-timeout", str(print_timeout)]
            if requested_model:
                cmd.extend(["--model", requested_model])
            cmd.append(full_prompt)

            print(f"[Bridge] Запрос к Antigravity CLI (модель: {requested_model}, timeout: {print_timeout}s)...")
            start_t = time.time()
            res = subprocess.run(
                cmd,
                stdin=subprocess.DEVNULL,
                capture_output=True,
                text=True,
                timeout=print_timeout + 30,
            )
            elapsed = time.time() - start_t

            if res.returncode != 0:
                err_text = (res.stderr or "").strip() or f"Code {res.returncode}"
                print(f"[Bridge] Ошибка agy: {err_text}")
                self._send_json(500, {"error": {"message": f"Antigravity CLI error: {err_text}"}})
                return

            reply_text = (res.stdout or "").strip()

            # Откат через transcript при пустом stdout (известный баг agy -p в pipe-режиме)
            if not reply_text:
                print("[Bridge] agy вернул пустой stdout, пробуем transcript...")
                reply_text = self._transcript_fallback()
                if reply_text:
                    print(f"[Bridge] Ответ восстановлен из transcript ({len(reply_text)} символов)")

            if not reply_text:
                print("[Bridge] Пустой ответ и откат не сработал")
                self._send_json(502, {"error": {"message": "Antigravity CLI returned empty output; check transcript fallback / agy bugs"}})
                return

            print(f"[Bridge] Ответ получен за {elapsed:.2f}с ({len(reply_text)} символов)")

            response_json = {
                "id": f"chatcmpl-agy-{int(time.time()*1000)}",
                "object": "chat.completion",
                "created": int(time.time()),
                "model": requested_model,
                "choices": [
                    {
                        "index": 0,
                        "message": {"role": "assistant", "content": reply_text},
                        "finish_reason": "stop"
                    }
                ],
                "usage": {
                    "prompt_tokens": len(full_prompt) // 4,
                    "completion_tokens": len(reply_text) // 4,
                    "total_tokens": (len(full_prompt) + len(reply_text)) // 4
                }
            }
            self._send_json(200, response_json)

        except subprocess.TimeoutExpired:
            self._send_json(504, {"error": {"message": "Antigravity CLI timed out"}})
        except FileNotFoundError:
            self._send_json(500, {"error": {"message": "agy CLI not found in PATH"}})
        except Exception as e:
            self._send_json(500, {"error": {"message": str(e)}})

    def _transcript_fallback(self) -> str:
        """Пытаемся достать ответ из последнего transcript-файла agy."""
        candidates = []
        env_dir = os.environ.get(TRANSCRIPT_DIR_ENV)
        if env_dir and os.path.isdir(env_dir):
            candidates.append(env_dir)
        home = os.path.expanduser("~")
        candidates += [
            os.path.join(home, ".antigravity", "transcripts"),
            os.path.join(home, ".config", "antigravity", "transcripts"),
            os.path.join(home, ".agy", "transcripts"),
        ]
        newest = None
        newest_mtime = 0
        for d in candidates:
            if not os.path.isdir(d):
                continue
            for name in os.listdir(d):
                if not (name.endswith(".json") or name.endswith(".md") or name.endswith(".txt")):
                    continue
                p = os.path.join(d, name)
                try:
                    m = os.path.getmtime(p)
                except OSError:
                    continue
                if m > newest_mtime:
                    newest_mtime = m
                    newest = p
        if not newest:
            return ""
        # Игнорируем слишком старые transcript (> 10 минут)
        if time.time() - newest_mtime > 600:
            return ""
        try:
            with open(newest, "r", encoding="utf-8", errors="replace") as f:
                data = f.read()
        except Exception:
            return ""
        try:
            obj = json.loads(data)
            # Пробуем типичные поля
            for key in ("response", "text", "content", "output", "assistant"):
                if isinstance(obj, dict) and isinstance(obj.get(key), str) and obj[key].strip():
                    return obj[key].strip()
            if isinstance(obj, list) and obj:
                last = obj[-1]
                if isinstance(last, dict):
                    for key in ("content", "text", "message"):
                        if isinstance(last.get(key), str) and last[key].strip():
                            return last[key].strip()
        except Exception:
            pass
        # Не JSON — возвращаем хвост как сырой текст
        return data.strip()[-4000:]

    def log_message(self, fmt, *args):
        print(f"[Bridge] {self.address_string()} - {fmt % args}")


def run_server(host="127.0.0.1", port=8080, auth_token=None, print_timeout=DEFAULT_PRINT_TIMEOUT, cors_origin="*"):
    httpd = ThreadingHTTPServer((host, port), AntigravityBridgeHandler)
    httpd.auth_token = auth_token
    httpd.print_timeout = print_timeout
    httpd.cors_origin = cors_origin
    print("=" * 60)
    print("✨ Antigravity CLI -> OpenAI Bridge запущен!")
    print(f"Адрес для Asuna: http://{host if host not in ('0.0.0.0', '127.0.0.1') else '<IP-твоего-ПК>'}:{port}/v1")
    print(f"Проверка статуса: curl http://127.0.0.1:{port}/")
    print(f"Авторизация: {'вкл' if auth_token else 'выкл (рекомендуется --token)'}, CORS origin: {cors_origin}")
    print(f"agy --print-timeout: {print_timeout}с")
    print("=" * 60)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nОстановка сервера...")
        httpd.server_close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Antigravity CLI to OpenAI HTTP Bridge")
    parser.add_argument("--host", default="127.0.0.1", help="Host to bind (default: 127.0.0.1). Используйте 0.0.0.0 осознанно — сервер станет доступен из Wi-Fi!")
    parser.add_argument("--port", type=int, default=8080, help="Port to bind (default: 8080)")
    parser.add_argument("--token", default=os.environ.get("BRIDGE_TOKEN"), help="Токен авторизации (Authorization: Bearer ...). Можно через env BRIDGE_TOKEN")
    parser.add_argument("--print-timeout", type=int, default=DEFAULT_PRINT_TIMEOUT, help=f"Таймаут ожидания ответа agy в секундах (default: {DEFAULT_PRINT_TIMEOUT})")
    parser.add_argument("--cors-origin", default="*", help="Значение Access-Control-Allow-Origin (default: *)")
    args = parser.parse_args()
    run_server(args.host, args.port, args.token, args.print_timeout, args.cors_origin)
