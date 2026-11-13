#!/usr/bin/env python3
"""
Bridge de TikTok Live -> Ultimate Warriors Mobile (Android).

Reenvía comentarios, likes y regalos en tiempo real al servidor HTTP local
del juego en Android (puerto 8765 por defecto).

Requisitos para un directo real de TikTok:
    pip install TikTokLive

Uso con directo real:
    python scripts/tiktok_live_bridge.py --tiktok @tu_usuario --host 192.168.1.50

Uso por USB con ADB (sin depender de Wi-Fi):
    adb forward tcp:8765 tcp:8765
    python scripts/tiktok_live_bridge.py --tiktok @tu_usuario --host 127.0.0.1

Modo prueba sin directo activo:
    python scripts/tiktok_live_bridge.py --demo --host 127.0.0.1
"""

import argparse
import json
import random
import time
import urllib.error
import urllib.request


def post_event(url: str, payload: dict) -> None:
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=2.0) as resp:
            resp.read()
    except urllib.error.URLError as exc:
        print(f"[!] Error enviando evento a {url}: {exc}")


def run_demo(url: str) -> None:
    print(f"[*] Modo DEMO activo -> enviando eventos de prueba a {url} (Ctrl+C para salir)")
    users = ["@goku_ui", "@vegeta_ego", "@broly_ssj", "@namek_fan", "@dorlan_live"]
    chars = ["goku", "vegeta", "broly", "jiren", "gogeta", "vegetto", "freezer", "hit"]
    gifts = [
        ("rose", 1),
        ("heart", 5),
        ("doughnut", 30),
        ("cap", 99),
        ("galaxy", 500),
        ("lion", 1000),
    ]
    while True:
        u = random.choice(users)
        roll = random.random()
        if roll < 0.45:
            post_event(url, {"type": "like", "user": u, "count": random.randint(5, 25)})
            print(f"[LIKE] {u}")
        elif roll < 0.80:
            cmd = random.choice(["!ki", "!ssj", "!kaioken", "!choque", f"!j2 {random.choice(chars)}"])
            post_event(url, {"type": "chat", "user": u, "text": cmd})
            print(f"[CHAT] {u}: {cmd}")
        else:
            gift, coins = random.choice(gifts)
            post_event(url, {"type": "gift", "user": u, "gift": gift, "count": 1, "coins": coins})
            print(f"[GIFT] {u} -> {gift} ({coins})")
        time.sleep(1.2)


def run_live(tiktok_user: str, url: str) -> None:
    try:
        from TikTokLive import TikTokLiveClient
        from TikTokLive.events import CommentEvent, ConnectEvent, GiftEvent, LikeEvent, RoomUserSeqEvent
    except ImportError:
        print("[!] Falta la librería TikTokLive. Instálala con:")
        print("    pip install TikTokLive")
        raise SystemExit(1)

    handle = tiktok_user.lstrip("@")
    client = TikTokLiveClient(unique_id=f"@{handle}")

    @client.on(ConnectEvent)
    async def on_connect(event: ConnectEvent) -> None:
        print(f"[+] Conectado al directo @{handle} -> reenviando a {url}")

    @client.on(CommentEvent)
    async def on_comment(event: CommentEvent) -> None:
        user = getattr(event.user, "unique_id", None) or getattr(event.user, "nickname", "viewer")
        post_event(url, {"type": "chat", "user": user, "text": event.comment})

    @client.on(GiftEvent)
    async def on_gift(event: GiftEvent) -> None:
        if getattr(event, "streaking", False):
            return
        user = getattr(event.user, "unique_id", None) or getattr(event.user, "nickname", "viewer")
        gift_name = getattr(event.gift, "name", "rose")
        coins = int(getattr(event.gift, "diamond_count", 1) or 1)
        count = int(getattr(event, "repeat_count", 1) or 1)
        post_event(url, {"type": "gift", "user": user, "gift": gift_name, "count": count, "coins": coins})

    @client.on(LikeEvent)
    async def on_like(event: LikeEvent) -> None:
        user = getattr(event.user, "unique_id", None) or getattr(event.user, "nickname", "viewer")
        count = int(getattr(event, "count", 1) or 1)
        post_event(url, {"type": "like", "user": user, "count": count})

    @client.on(RoomUserSeqEvent)
    async def on_viewers(event: RoomUserSeqEvent) -> None:
        total = int(getattr(event, "m_total", 0) or 0)
        if total > 0:
            post_event(url, {"type": "viewers", "viewerCount": total})

    client.run()


def main() -> None:
    parser = argparse.ArgumentParser(description="TikTok Live Bridge para Ultimate Warriors Mobile")
    parser.add_argument("--tiktok", help="Usuario de TikTok Live (ej: @usuario)")
    parser.add_argument("--host", default="127.0.0.1", help="IP del celular Android (default: 127.0.0.1)")
    parser.add_argument("--port", type=int, default=8765, help="Puerto HTTP Webhook del juego (default: 8765)")
    parser.add_argument("--demo", action="store_true", help="Enviar eventos de prueba sin directo real")
    args = parser.parse_args()

    url = f"http://{args.host}:{args.port}/event"
    if args.demo:
        run_demo(url)
    elif args.tiktok:
        run_live(args.tiktok, url)
    else:
        parser.error("Debes especificar --tiktok @usuario o usar --demo")


if __name__ == "__main__":
    main()
