#!/usr/bin/env python3
"""
„Új verzió érhető el” levél kiküldése a Brevo listának (vagy tesztként egy címre).

Környezeti változók:
  BREVO_API_KEY, BREVO_LIST_ID
  BREVO_SENDER_EMAIL   a Brevóban MEGERŐSÍTETT feladó cím
  BREVO_SENDER_NAME    pl. "BlueTune"
  VERSION              pl. 1.2.0
  CHANGES              újdonságok, soronként egy (a "- " előtag opcionális)
  DOWNLOAD_URL         hova mutasson a gomb (alap: a GitHub Release oldal)
  TEST_EMAIL           ha meg van adva, CSAK ide megy egy tesztlevél
  MODE                 "test" vagy "send"
"""
import html
import os
import sys
from datetime import datetime, timezone

import requests

API = "https://api.brevo.com/v3"


def need(name: str) -> str:
    v = os.environ.get(name, "").strip()
    if not v:
        sys.exit(f"Hiányzó beállítás: {name}")
    return v


def build_html(version: str, changes: list[str], url: str) -> str:
    items = "\n".join(
        f'<li style="margin:0 0 8px">{html.escape(c)}</li>' for c in changes
    ) or "<li>Hibajavítások és fejlesztések</li>"
    v = html.escape(version)
    u = html.escape(url, quote=True)
    return f"""<!doctype html>
<html lang="hu"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>BlueTune {v}</title></head>
<body style="margin:0;padding:0;background:#0b1220;font-family:Arial,Helvetica,sans-serif;color:#e6edf5">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#0b1220">
<tr><td align="center" style="padding:32px 16px">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:560px;background:#121922;border-radius:16px">
<tr><td style="padding:32px 28px 8px">
  <div style="font-size:13px;letter-spacing:2px;color:#38d9ff;font-weight:bold">BLUETUNE</div>
  <h1 style="margin:8px 0 4px;font-size:24px;color:#ffffff">Megérkezett a {v} verzió</h1>
  <p style="margin:0;color:#9aa8b8;font-size:15px">Frissítsd az appot, hogy elérd az újdonságokat.</p>
</td></tr>
<tr><td style="padding:16px 28px 8px">
  <h2 style="font-size:16px;color:#ffffff;margin:0 0 12px">Újdonságok</h2>
  <ul style="padding-left:20px;margin:0;font-size:15px;line-height:1.5;color:#e6edf5">
{items}
  </ul>
</td></tr>
<tr><td style="padding:20px 28px 32px">
  <a href="{u}" style="display:inline-block;background:#38d9ff;color:#00222c;text-decoration:none;font-weight:bold;padding:12px 22px;border-radius:10px">Frissítés letöltése</a>
</td></tr>
<tr><td style="padding:16px 28px 28px;border-top:1px solid #222c38;font-size:12px;color:#7d8a99;line-height:1.5">
  Ezt a levelet azért kaptad, mert a BlueTune appban feliratkoztál a frissítés-értesítőkre.<br>
  <a href="{{{{ unsubscribe }}}}" style="color:#9aa8b8">Leiratkozás</a> · az appban is kikapcsolhatod: Fiók → Frissítés-értesítők.
</td></tr>
</table></td></tr></table>
</body></html>"""


def main() -> None:
    api_key = need("BREVO_API_KEY")
    list_id = int(need("BREVO_LIST_ID"))
    sender_email = need("BREVO_SENDER_EMAIL")
    sender_name = os.environ.get("BREVO_SENDER_NAME", "").strip() or "BlueTune"
    version = need("VERSION").lstrip("vV")
    mode = os.environ.get("MODE", "test").strip()
    test_email = os.environ.get("TEST_EMAIL", "").strip()
    repo = os.environ.get("GITHUB_REPOSITORY", "")
    url = os.environ.get("DOWNLOAD_URL", "").strip() or (
        f"https://github.com/{repo}/releases/latest" if repo else "https://example.com"
    )
    changes = [
        line.strip().lstrip("-•* ").strip()
        for line in os.environ.get("CHANGES", "").replace("\\n", "\n").splitlines()
        if line.strip()
    ]

    if mode == "test" and not test_email:
        sys.exit("Teszt módban add meg a TEST_EMAIL címet.")

    s = requests.Session()
    s.headers.update({"api-key": api_key, "accept": "application/json", "content-type": "application/json"})

    stamp = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M")
    body = {
        "name": f"BlueTune {version} – {'TESZT ' if mode == 'test' else ''}{stamp}",
        "subject": f"Megérkezett a BlueTune {version} 🎧",
        "previewText": changes[0] if changes else "Új verzió érhető el",
        "sender": {"name": sender_name, "email": sender_email},
        "htmlContent": build_html(version, changes, url),
        "recipients": {"listIds": [list_id]},
    }
    r = s.post(f"{API}/emailCampaigns", json=body, timeout=30)
    if r.status_code >= 400:
        sys.exit(f"Kampány létrehozása sikertelen ({r.status_code}): {r.text[:400]}")
    cid = r.json()["id"]
    print(f"Kampány létrehozva: #{cid}")

    if mode == "test":
        r = s.post(f"{API}/emailCampaigns/{cid}/sendTest", json={"emailTo": [test_email]}, timeout=30)
        if r.status_code >= 400:
            sys.exit(f"Tesztküldés sikertelen ({r.status_code}): {r.text[:400]}")
        print(f"Tesztlevél elküldve ide: {test_email}. A kampány piszkozatként a Brevóban maradt.")
    else:
        r = s.post(f"{API}/emailCampaigns/{cid}/sendNow", timeout=30)
        if r.status_code >= 400:
            sys.exit(f"Küldés sikertelen ({r.status_code}): {r.text[:400]}")
        print("Kiküldve a teljes listának ✓")


if __name__ == "__main__":
    main()
