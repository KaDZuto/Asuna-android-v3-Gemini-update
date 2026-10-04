#!/usr/bin/env python3
"""
Antigravity CLI to OpenAI-Compatible API Bridge.
------------------------------------------------
Позволяет подключить подписку Gemini AI Pro (через официальный Antigravity CLI)
к приложению Asuna на Android (или любому другому OpenAI-совместимому клиенту)
абсолютно легально и БЕЗ нарушения пользовательского соглашения Google (ToS).

Как это работает:
1. Запускается локальный HTTP сервер (по умолчанию порт 8080).
2. Принимает стандартные запросы POST /v1/chat/completions.
3. Пробрасывает запрос в официально авторизованный CLI `agy -p`.
4. Возвращает ответ в стандартном JSON-формате OpenAI.

Запуск на ПК:
    python3 antigravity_bridge.py --port 8080 --host 0.0.0.0

В приложении Asuna на телефоне (в одной Wi-Fi сети):
    Провайдер: Antigravity Bridge (или OpenAI-совместимый)
    Base URL: http://<IP_ВАШЕГО_ПК>:8080/v1
    Model: gemini-3.8-flash-high (или gemini-3.1-pro-high, claude-sonnet-5-5-high)
"""

import sys
import os
import json
import time
import argparse
import subprocess
from http.server import HTTPServer, BaseHTTPRequestHandler

DEFAULT_MODEL = "gemini-3.8-flash-high"

class AntigravityBridgeHandler(BaseHTTPRequestHandler):
    def _send_cors_headers(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Title, HTTP-Referer")

    def do_OPTIONS(self):
        self.send_response(200)
        self._send_cors_headers()
        self.end_headers()

    def do_GET(self):
        if self.path in ("/v1/models", "/models"):
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self._send_cors_headers()
            self.end_headers()
            models_data = {
                "object": "list",
                "data": [
                    {"id": "gemini-3.8-flash-high", "object": "model", "owned_by": "google"},
                    {"id": "gemini-3.1-pro-high", "object": "model", "owned_by": "google"},
                    {"id": "claude-sonnet-5-5-high", "object": "model", "owned_by": "anthropic"}
                ]
            }
            self.wfile.write(json.dumps(models_data).encode("utf-8"))
            return

        # Healthcheck
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self._send_cors_headers()
        self.end_headers()
        self.wfile.write(json.dumps({"status": "ok", "service": "antigravity-bridge"}).encode("utf-8"))

    def do_POST(self):
        if not (self.path.startswith("/v1/chat/completions") or self.path.startswith("/chat/completions")):
            self.send_response(404)
            self.end_headers()
            return

        content_length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(content_length)

        try:
            req_json = json.loads(body.decode("utf-8"))
        except Exception as e:
            self.send_error(400, f"Invalid JSON: {e}")
            return

        messages = req_json.get("messages", [])
        requested_model = req_json.get("model", DEFAULT_MODEL)

        # Собираем контекст сообщений в единый промпт для CLI
        prompt_parts = []
        for msg in messages:
            role = msg.get("role", "user")
            content = msg.get("content", "")
            if isinstance(content, list):
                # Multimodal or parts
                content = "".join([part.get("text", "") for part in content if isinstance(part, dict)])
            if role == "system":
                prompt_parts.append(f"[SYSTEM INSTRUCTION]\n{content}\n")
            elif role == "user":
                prompt_parts.append(f"[USER]\n{content}")
            elif role == "assistant":
                prompt_parts.append(f"[ASUNA]\n{content}")

        full_prompt = "\n".join(prompt_parts)

        # Вызываем agy CLI
        try:
            cmd = ["agy", "-p"]
            if requested_model:
                cmd.extend(["--model", requested_model])
            cmd.append(full_prompt)

            print(f"[Bridge] Запрос к Antigravity CLI (модель: {requested_model})...")
            start_t = time.time()
            res = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=120
            )
            elapsed = time.time() - start_t

            if res.returncode != 0:
                err_text = res.stderr.strip() or f"Code {res.returncode}"
                print(f"[Bridge] Ошибка agy: {err_text}")
                self.send_response(500)
                self.send_header("Content-Type", "application/json")
                self._send_cors_headers()
                self.end_headers()
                err_resp = {"error": {"message": f"Antigravity CLI error: {err_text}"}}
                self.wfile.write(json.dumps(err_resp).encode("utf-8"))
                return

            reply_text = res.stdout.strip()
            print(f"[Bridge] Ответ получен за {elapsed:.2f}с ({len(reply_text)} символов)")

            # Формируем стандартный OpenAI JSON
            response_json = {
                "id": f"chatcmpl-agy-{int(time.time()*1000)}",
                "object": "chat.completion",
                "created": int(time.time()),
                "model": requested_model,
                "choices": [
                    {
                        "index": 0,
                        "message": {
                            "role": "assistant",
                            "content": reply_text
                        },
                        "finish_reason": "stop"
                    }
                ],
                "usage": {
                    "prompt_tokens": len(full_prompt) // 4,
                    "completion_tokens": len(reply_text) // 4,
                    "total_tokens": (len(full_prompt) + len(reply_text)) // 4
                }
            }

            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self._send_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps(response_json, ensure_ascii=False).encode("utf-8"))

        except subprocess.TimeoutExpired:
            self.send_response(504)
            self.send_header("Content-Type", "application/json")
            self._send_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps({"error": {"message": "Antigravity CLI timed out"}}).encode("utf-8"))
        except Exception as e:
            self.send_response(500)
            self.send_header("Content-Type", "application/json")
            self._send_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps({"error": {"message": str(e)}}).encode("utf-8"))

def run_server(host="0.0.0.0", port=8080):
    server_address = (host, port)
    httpd = HTTPServer(server_address, AntigravityBridgeHandler)
    print("=" * 60)
    print("✨ Antigravity CLI -> OpenAI Bridge запущен!")
    print(f"Адрес для Asuna: http://{host if host != '0.0.0.0' else '<IP-твоего-ПК>'}:{port}/v1")
    print(f"Проверка статуса: curl http://127.0.0.1:{port}/")
    print("Использует твою авторизацию Google One AI Pro из agy CLI.")
    print("=" * 60)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nОстановка сервера...")
        httpd.server_close()

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Antigravity CLI to OpenAI HTTP Bridge")
    parser.add_argument("--host", default="0.0.0.0", help="Host to bind (default: 0.0.0.0)")
    parser.add_argument("--port", type=int, default=8080, help="Port to bind (default: 8080)")
    args = parser.parse_args()
    run_server(args.host, args.port)
