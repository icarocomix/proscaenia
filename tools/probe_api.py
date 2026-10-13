#!/usr/bin/env python3
"""
Sonda delle API reali di Proscaenia, da eseguire a mano nel proprio terminale.

Scopo: ricavare la forma esatta delle risposte (nomi dei campi, annidamento, tipi) degli endpoint
usati dal client web, così da allineare l'app Android al contratto reale. Le credenziali vengono
chieste in modo interattivo (la password non viene mostrata), usate solo per le richieste verso
proscaenia.com e mai scritte da nessuna parte.

Il file prodotto contiene solo la struttura delle risposte: rimuovo token, email, nomi, dati di
pagamento e la query string di ogni URL (dove stanno firme e token), accorcio i testi lunghi e
tengo solo i primi elementi di ogni lista. È quindi pensato per essere condiviso.

Uso:  python3 tools/probe_api.py
"""

import datetime
import getpass
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

API = "https://proscaenia.com/backoffice/api"
USER_AGENT = ("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) "
              "Chrome/141.0.0.0 Mobile Safari/537.36")
SENSITIVE_KEY = re.compile(
    r"token|password|secret|email|^name$|lastname|surname|phone|address|fiscal|card|iban|stripe|"
    r"customer|payment|invoice|birth|ip$|session|cookie|license|signature|^key$",
    re.IGNORECASE,
)
URL = re.compile(r"^(https?://[^?#]+)[?#].*$")
MAX_LIST = 3
MAX_TEXT = 160


def redact(value, key=""):
    """Riduco la risposta alla sola struttura, eliminando ogni dato personale o segreto."""
    if key and SENSITIVE_KEY.search(key) and not isinstance(value, (dict, list)):
        return "<redatto>" if value not in (None, "") else value
    if isinstance(value, dict):
        return {k: redact(v, k) for k, v in value.items()}
    if isinstance(value, list):
        reduced = [redact(v, key) for v in value[:MAX_LIST]]
        if len(value) > MAX_LIST:
            reduced.append(f"<... altri {len(value) - MAX_LIST} elementi>")
        return reduced
    if isinstance(value, str):
        match = URL.match(value)
        if match:
            return match.group(1) + "?<query rimossa>"
        if len(value) > MAX_TEXT:
            return value[:MAX_TEXT] + f"<... {len(value)} caratteri>"
    return value


def stable_device_id():
    """DeviceId della sonda, creato al primo uso e conservato in probe-output/ (non è un segreto)."""
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "probe-output", ".probe-device-id")
    try:
        with open(path, encoding="utf-8") as fh:
            saved = fh.read().strip()
            if re.fullmatch(r"[0-9a-f]{32}", saved):
                return saved
    except FileNotFoundError:
        pass
    generated = uuid.uuid4().hex
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(generated + "\n")
    return generated


class Client:
    def __init__(self):
        # Imito il client web: DeviceId è un identificativo stabile del dispositivo e DeviceData un
        # JSON con la stessa struttura che il sito ricava da FingerprintJS. Il backend registra ogni
        # DeviceId nuovo tra i dispositivi dell'account e oltre un limite rifiuta il login ("MAX
        # Device"): per questo lo genero una sola volta e lo riuso a ogni esecuzione.
        self.device_id = stable_device_id()
        self.device_data = json.dumps({
            "deviceId": self.device_id,
            "deviceBrand": None,
            "deviceModel": "Android",
            "deviceSystemName": "Chrome",
            "deviceSystemVersion": "141.0.0.0",
        })
        self.token = None

    def request(self, method, path, body=None, params=None):
        url = API + path + ("?" + urllib.parse.urlencode(params) if params else "")
        headers = {
            "Accept": "application/json",
            "Content-Language": "it",
            # Il backend rifiuta il login con HTTP 411 "Current Build not found." se manca questo
            # header; il client web lo imposta globalmente con questo valore.
            "Current-Build": "ios_1",
            "User-Agent": USER_AGENT,
            "DeviceId": self.device_id,
            "DeviceData": self.device_data,
            "Origin": "https://proscaenia.com",
            "Referer": "https://proscaenia.com/login",
        }
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        req = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                return resp.status, resp.headers.get("Content-Type", ""), resp.read()
        except urllib.error.HTTPError as e:
            return e.code, e.headers.get("Content-Type", ""), e.read()


def as_json(raw):
    try:
        return json.loads(raw.decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        return None


def record(report, label, method, path, status, ctype, raw, params=None):
    parsed = as_json(raw)
    report["calls"].append({
        "label": label,
        "request": f"{method} {path}",
        "params": params,
        "status": status,
        "content_type": ctype,
        "body": redact(parsed) if parsed is not None else f"<non JSON, {len(raw)} byte>",
    })
    print(f"  {label:<28} HTTP {status}")
    return parsed


def first(seq):
    return seq[0] if isinstance(seq, list) and seq else None


def probe_manifest(report, url):
    """Del manifest DASH registro solo i sistemi DRM dichiarati, nessuna chiave o contenuto."""
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            text = resp.read().decode("utf-8", errors="replace")
            status = resp.status
    except urllib.error.HTTPError as e:
        report["manifest"] = {"status": e.code}
        return
    except urllib.error.URLError as e:
        report["manifest"] = {"error": str(e.reason)}
        return
    report["manifest"] = {
        "status": status,
        "content_protection_schemes": sorted(set(re.findall(r'schemeIdUri="([^"]+)"', text))),
        "has_pssh": "pssh" in text,
        "adaptation_sets": len(re.findall(r"<AdaptationSet", text)),
        "text_tracks": len(re.findall(r'contentType="text"|mimeType="text/vtt"', text)),
    }
    print(f"  {'manifest DASH':<28} HTTP {status}")


def main():
    print("Sonda API Proscaenia — le credenziali restano su questo PC.")
    email = input("Email: ").strip()
    password = getpass.getpass("Password (non visualizzata): ")
    client = Client()
    report = {"generated": datetime.datetime.now().isoformat(timespec="seconds"), "api": API, "calls": []}
    # Diagnostica dell'input che non rivela nulla del contenuto: serve a scoprire spazi o caratteri
    # finiti per errore incollando, la causa più comune di un login rifiutato con dati "giusti".
    report["input_check"] = {
        "email_contains_at": "@" in email,
        "email_has_uppercase": email != email.lower(),
        "password_length": len(password),
        "password_leading_or_trailing_space": password != password.strip(),
        "password_non_ascii": any(ord(ch) > 127 for ch in password),
    }

    print("Richieste in corso:")
    status, ctype, raw = client.request("POST", "/auth/login", {"email": email, "password": password, "remember": True})
    login = as_json(raw) or {}
    report["calls"].append({
        "label": "login", "request": "POST /auth/login", "status": status, "content_type": ctype,
        "body_keys": sorted(login.keys()) if isinstance(login, dict) else None,
        "body": redact(login) if isinstance(login, (dict, list)) else f"<non JSON, {len(raw)} byte>",
    })
    print(f"  {'login':<28} HTTP {status}")
    del password

    token = None
    if isinstance(login, dict):
        token = login.get("access_token") or (login.get("data") or {}).get("access_token")
    if not token:
        errors = login.get("errors") if isinstance(login, dict) else None
        if isinstance(errors, list) and "MAX Device" in errors:
            # Con il limite pieno il backend non rilascia token: nessuna chiamata autenticata è
            # possibile da qui, il posto va liberato da un dispositivo già collegato (sito web).
            print("Login rifiutato: numero massimo di dispositivi raggiunto. Libera un posto dal sito "
                  "(area personale, elenco dispositivi) e rilancia la sonda.")
        else:
            print("Login non riuscito o token non trovato: salvo comunque il report.")
    client.token = token

    if token:
        contents = record(report, "contenuti home", "GET", "/v2/show/contents", *client.request("GET", "/v2/show/contents"))
        record(report, "catalog snapshot", "GET", "/catalog/snapshot", *client.request("GET", "/catalog/snapshot"))
        record(report, "stato abbonamento", "GET", "/user/stripe/access-state", *client.request("GET", "/user/stripe/access-state"))
        # Solo lettura: elenco dei dispositivi registrati sull'account, come nella pagina del sito.
        # Il device_id della sonda lo riporto a parte, così si riconosce la propria voce nell'elenco.
        report["probe_device_id"] = client.device_id
        record(report, "dispositivi registrati", "GET", "/user/device", *client.request("GET", "/user/device"))

        row = first((contents or {}).get("data")) if isinstance(contents, dict) else None
        show_id = None
        if isinstance(row, dict) and row.get("id") is not None:
            params = {"contentId": row["id"]}
            shows = record(report, "spettacoli della riga", "GET", "/v2/show",
                           *client.request("GET", "/v2/show", params=params), params=params)
            candidate = shows.get("data") if isinstance(shows, dict) else shows
            if isinstance(candidate, dict):
                candidate = candidate.get("data") or candidate.get("shows")
            show = first(candidate)
            if isinstance(show, dict):
                show_id = show.get("id")

        if show_id is not None:
            record(report, "scheda (anteprima)", "GET", f"/show/{show_id}?preview=true",
                   *client.request("GET", f"/show/{show_id}", params={"preview": "true"}))
            detail = record(report, "scheda per la visione", "GET", f"/show/{show_id}?preview=false",
                            *client.request("GET", f"/show/{show_id}", params={"preview": "false"}))
            video = first(((detail or {}).get("data") or {}).get("video")) if isinstance(detail, dict) else None
            if isinstance(video, dict) and video.get("drm") and isinstance(video.get("urls"), dict) and video["urls"].get("dash"):
                probe_manifest(report, video["urls"]["dash"])

    out_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "probe-output")
    os.makedirs(out_dir, exist_ok=True)
    out = os.path.abspath(os.path.join(out_dir, f"proscaenia-api-{datetime.datetime.now():%Y%m%d-%H%M%S}.json"))
    with open(out, "w", encoding="utf-8") as fh:
        json.dump(report, fh, ensure_ascii=False, indent=2)
    print(f"\nReport (senza dati sensibili) salvato in:\n  {out}")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(130)
