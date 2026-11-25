#!/usr/bin/env python3
"""Ricevitore dei log della prova tecnica Proscaenia su Tizen.

Avvio sul PC nella stessa rete della TV:

    python3 tools/tizen_log_receiver.py [--port 8765] [--out tizen-log]

e sulla TV si inserisce http://<IP del PC>:<porta> nel campo "Ricevitore PC".

- POST /log     righe di testo, aggiunte a <out>/tizen-live-<data>.txt e stampate a terminale;
- POST /upload  archivio ZIP esportato dalla TV, salvato in <out>/ con il nome indicato.

Le righe arrivano già sanificate dall'app (niente token, password o query string firmate). Uso
solo la libreria standard: nessuna dipendenza da installare.
"""

import argparse
import datetime
import os
import re
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

MAX_BODY_BYTES = 20 * 1024 * 1024
SAFE_NAME = re.compile(r"^[A-Za-z0-9._-]{1,120}\.zip$")


def make_handler(out_dir: str):
    class Handler(BaseHTTPRequestHandler):
        # La TV invia da un'origine diversa: rispondo con CORS aperto, è un servizio solo per la rete locale.
        def _cors(self) -> None:
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Access-Control-Allow-Methods", "POST, OPTIONS")
            self.send_header("Access-Control-Allow-Headers", "Content-Type")

        def _reply(self, code: int, text: str = "") -> None:
            body = text.encode("utf-8")
            self.send_response(code)
            self._cors()
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_OPTIONS(self) -> None:  # noqa: N802 (nome imposto da BaseHTTPRequestHandler)
            self.send_response(204)
            self._cors()
            self.send_header("Content-Length", "0")
            self.end_headers()

        def _read_body(self) -> bytes | None:
            length = int(self.headers.get("Content-Length") or 0)
            if length <= 0 or length > MAX_BODY_BYTES:
                self._reply(413 if length > MAX_BODY_BYTES else 400, "corpo assente o troppo grande")
                return None
            return self.rfile.read(length)

        def do_POST(self) -> None:  # noqa: N802
            url = urlparse(self.path)
            body = self._read_body()
            if body is None:
                return
            if url.path == "/log":
                text = body.decode("utf-8", errors="replace")
                day = datetime.date.today().strftime("%Y%m%d")
                with open(os.path.join(out_dir, f"tizen-live-{day}.txt"), "a", encoding="utf-8") as fh:
                    fh.write(text)
                sys.stdout.write(text)
                sys.stdout.flush()
                self._reply(200, "ok")
            elif url.path == "/upload":
                name = (parse_qs(url.query).get("name") or [""])[0]
                # Il nome arriva dalla rete: accetto solo nomi semplici .zip, mai percorsi.
                if not SAFE_NAME.match(name):
                    name = datetime.datetime.now().strftime("proscaenia-tizen-log-%Y%m%d-%H%M%S.zip")
                path = os.path.join(out_dir, name)
                with open(path, "wb") as fh:
                    fh.write(body)
                print(f"\n[ricevitore] ZIP salvato: {path} ({len(body)} byte)", flush=True)
                self._reply(200, "ok")
            else:
                self._reply(404, "percorso sconosciuto")

        def log_message(self, fmt: str, *args) -> None:
            # Silenzio il log di accesso standard: a terminale voglio vedere solo le righe della TV.
            return

    return Handler


def main() -> None:
    parser = argparse.ArgumentParser(description="Ricevitore log della prova Proscaenia su Tizen")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--out", default="tizen-log", help="cartella di destinazione (default: tizen-log)")
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)
    server = ThreadingHTTPServer(("0.0.0.0", args.port), make_handler(os.path.abspath(args.out)))
    print(f"[ricevitore] in ascolto su 0.0.0.0:{args.port}, file in {os.path.abspath(args.out)} (Ctrl+C per uscire)", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[ricevitore] chiuso")


if __name__ == "__main__":
    main()
