#!/usr/bin/env python3
"""
Firebase -> Brevo feliratkozó-szinkron.

Forrás:  Firestore `subscribers/{uid}` (az app írja, csak kifejezett hozzájárulással)
Cél:     egy Brevo lista (BREVO_LIST_ID)

Szabályok:
  * csak megerősített (email_verified) fiókok kerülnek a listára
  * ha valaki leiratkozott az appban vagy törölte a fiókját -> a Brevo-kontakt törlődik
  * ha a Firebase-fiók már nem létezik, az árva subscribers-dokumentum is törlődik
  * a Brevo saját leiratkozó linkjét a Brevo kezeli (az ilyen kontaktnak nem megy több levél)

Környezeti változók (GitHub Secrets):
  FIREBASE_SERVICE_ACCOUNT  a Firebase szolgáltatásfiók JSON-ja (teljes tartalom)
  BREVO_API_KEY             Brevo API kulcs
  BREVO_LIST_ID             a Brevo lista azonosítója (szám)
  DRY_RUN=1                 csak kiírja, mit csinálna
  FORCE=1                   engedi a tömeges törlést (biztonsági zár feloldása)
"""
import json
import os
import sys
import urllib.parse

import firebase_admin
import requests
from firebase_admin import auth, credentials, firestore

API = "https://api.brevo.com/v3"
PAGE = 500


def env(name: str, required: bool = True) -> str:
    v = os.environ.get(name, "").strip()
    if required and not v:
        print(f"Hiányzó beállítás: {name} – a szinkron kimarad.")
        sys.exit(0)
    return v


def brevo(session: requests.Session, method: str, path: str, **kw) -> requests.Response:
    r = session.request(method, API + path, timeout=30, **kw)
    if r.status_code >= 400 and r.status_code != 404:
        print(f"Brevo hiba {r.status_code} {method} {path}: {r.text[:300]}")
    return r


def main() -> None:
    sa = env("FIREBASE_SERVICE_ACCOUNT")
    api_key = env("BREVO_API_KEY")
    list_id = int(env("BREVO_LIST_ID"))
    dry = os.environ.get("DRY_RUN") == "1"
    force = os.environ.get("FORCE") == "1"

    firebase_admin.initialize_app(credentials.Certificate(json.loads(sa)))
    db = firestore.client()

    # ---- 1. kik szeretnének levelet?
    desired: dict[str, dict] = {}
    orphans = 0
    unverified = 0
    for doc in db.collection("subscribers").stream():
        data = doc.to_dict() or {}
        if data.get("consent") is not True:
            continue
        try:
            user = auth.get_user(doc.id)
        except auth.UserNotFoundError:
            orphans += 1
            if not dry:
                doc.reference.delete()
            continue
        if not user.email or not user.email_verified or user.disabled:
            unverified += 1
            continue
        consent_at = data.get("consentAt")
        desired[user.email.lower()] = {
            "SOURCE": str(data.get("source", "app"))[:60],
            "CONSENT_AT": consent_at.isoformat() if hasattr(consent_at, "isoformat") else "",
        }

    # ---- 2. kik vannak most a Brevo listán?
    s = requests.Session()
    s.headers.update({"api-key": api_key, "accept": "application/json", "content-type": "application/json"})
    current: set[str] = set()
    offset = 0
    while True:
        r = brevo(s, "GET", f"/contacts/lists/{list_id}/contacts", params={"limit": PAGE, "offset": offset})
        if r.status_code >= 400:
            sys.exit("A Brevo lista nem olvasható – ellenőrizd a BREVO_LIST_ID-t és az API kulcsot.")
        batch = r.json().get("contacts", [])
        current.update(c["email"].lower() for c in batch if c.get("email"))
        if len(batch) < PAGE:
            break
        offset += PAGE

    to_add = sorted(set(desired) - current)
    to_remove = sorted(current - set(desired))

    print(f"Firebase feliratkozók: {len(desired)} (megerősítetlen: {unverified}, árva: {orphans})")
    print(f"Brevo listán: {len(current)}  ->  hozzáadás: {len(to_add)}, törlés: {len(to_remove)}")

    # Biztonsági zár: rossz projekt / hibás beállítás esetén ne törölje az egész listát
    if to_remove and not force and len(to_remove) > max(10, len(current) // 2):
        sys.exit("Túl sok törlés lenne (a lista több mint fele). Ellenőrizd a beállítást, vagy futtasd FORCE=1-gyel.")

    if dry:
        for e in to_add:
            print("  + " + e)
        for e in to_remove:
            print("  - " + e)
        print("DRY_RUN – nem változott semmi.")
        return

    # ---- 3. hozzáadás / frissítés
    for email in to_add:
        brevo(s, "POST", "/contacts", json={
            "email": email,
            "listIds": [list_id],
            "updateEnabled": True,
            "attributes": desired[email],
        })

    # ---- 4. törlés (leiratkozott vagy törölte a fiókját) – adattakarékosság, GDPR
    for email in to_remove:
        brevo(s, "DELETE", "/contacts/" + urllib.parse.quote(email, safe=""))

    print("Szinkron kész.")


if __name__ == "__main__":
    main()
