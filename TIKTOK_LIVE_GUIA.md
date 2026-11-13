# 🎮 Cómo Configurar TikTok Live Interactivo (`v1.0.6+`)

Ultimate Warriors Android incluye **3 formas** de conectar tu transmisión en vivo de TikTok para que tu audiencia controle a los peleadores en tiempo real mediante **comentarios**, **regalos (Gifts)** y **likes**.

> Además, siempre puedes probar o entretener la sala usando el **Simulador de Audiencia integrado** en el Hub de TikTok Live sin necesidad de cuenta externa. Al llegar el primer comentario o regalo real, el simulador se apaga automáticamente.

---

## Opción 1: Directo en el mismo teléfono (SIN PC) — 100% Local Sin API y/o Euler Stream 📱

Es la forma más rápida para transmitir directamente desde tu Android sin usar computador ni servidores externos:

1. Abre **TikTok** en tu teléfono (o en otro dispositivo usando tu cuenta) e inicia un **LIVE**.
2. En **Ultimate Warriors Android**, entra a **🔴 TIKTOK LIVE ARENA**.
3. En la tarjeta **2. CONEXIÓN EN TELÉFONO, PC Y SIMULADOR**, toca el botón:
   **`📱 CONECTAR @USUARIO EN TELÉFONO`**
4. Escribe tu nombre de usuario de TikTok (por ejemplo `@dorlanpabon`) y elige el modo que prefieras:
   - **Modo 100% Local Sin API (WebView Desktop React Fiber):** Abre `https://www.tiktok.com/@usuario/live` en un lienzo Desktop real (`1280x900`) fuera de pantalla (con audio silenciado sin pausar el stream), inyecta un observador React Fiber + DOM para capturar chat, regalos y espectadores directamente en tu teléfono sin API ni límites externos.
   - **Euler Stream WebSocket (`wss://ws.eulerstream.com`, Opcional):** Conexión WebSocket nativa de alta fiabilidad usando tu API Key de Euler Stream (configurable en el mismo diálogo). Si activas ambos (**Modo Híbrido Sin PC**), la app deduplica automáticamente mensajes y regalos repetidos.
5. Presiona **Guardar y Conectar** y luego **`🔴 ¡INICIAR DIRECTO TIKTOK!`**.

### ¿TikTok muestra captcha o verificación en tu región?
- Dentro del diálogo **Conectar TikTok en Teléfono**, presiona **`👁️ Ver / Diagnosticar Página LIVE`** para traer el navegador integrado al frente de forma interactiva, resolver cualquier captcha o tocar *Watch LIVE*, y luego cerrarlo para que siga escuchando en segundo plano.
- Si prefieres no abrir el navegador integrado, puedes usar solo **Euler Stream WS** o recurrir a la **Opción 2 (Termux en el mismo teléfono)** o **Opción 3 (TikFinity en PC)**.

---

## Opción 2: Directo en el teléfono con Termux (o desde un PC con Python) 🐍

Este repositorio incluye el puente oficial de código abierto [`scripts/tiktok_live_bridge.py`](scripts/tiktok_live_bridge.py) basado en `TikTokLive`, el cual se conecta por WebSocket al chat oficial sin usar interfaz web.

### A. Todo en el mismo teléfono (Android + Termux, sin PC)
1. Instala **Termux** en tu Android (preferiblemente desde F-Droid / GitHub).
2. Ejecuta dentro de Termux:
   ```bash
   pkg update && pkg install python -y
   pip install TikTokLive requests
   ```
3. Ejecuta el puente apuntando al servidor local del juego (`127.0.0.1:8765`):
   ```bash
   python scripts/tiktok_live_bridge.py --user @tu_usuario --target http://127.0.0.1:8765/event
   ```
4. Deja Termux corriendo en segundo plano y abre el modo **TikTok Live Arena** en Ultimate Warriors Android.

### B. Desde un PC en la misma red Wi-Fi
1. En la pantalla **TikTok Live Arena** de la app verás tu URL local en verde, por ejemplo:
   `Webhook [ONLINE]: http://192.168.1.45:8765/event`
2. En tu PC ejecuta:
   ```bash
   pip install TikTokLive requests
   python scripts/tiktok_live_bridge.py --user @tu_usuario --target http://192.168.1.45:8765/event
   ```

---

## Opción 3: Con TikFinity en PC (WebSocket / Webhook) ⚡

1. Abre **TikFinity** en tu PC conectado a la misma red Wi-Fi que el teléfono.
2. Puedes conectarlo de cualquiera de estas dos maneras:
   - **WebSocket automático (`:21213`)**: en el Hub de TikTok Live toca **`TikFinity :21213`** para activarlo. Si el PC y el celular usan proxy/túnel o red local compatibilizada, aparecerá `[WS OK]`.
   - **Webhook HTTP directo**: en **TikFinity → Actions & Events → Custom Webhook / Execute Request**, envía un `POST JSON` hacia la IP que te muestra el juego en pantalla:
     `http://<IP_DEL_TELEFONO>:8765/event`

### Formato JSON del Webhook `:8765/event`
Cualquier herramienta externa (TikFinity, Streamer.bot, Touch Portal, Termux, OBS scripts) puede enviar eventos JSON al teléfono:

```json
{"type":"chat","user":"@fan","text":"!ssj","team":2}
```
```json
{"type":"gift","user":"@fan","gift":"rose","count":1,"coins":1,"team":2}
```
```json
{"type":"like","user":"@fan","count":15,"team":1}
```

---

## 🎮 Comandos del Chat y Regalos en el Directo

### Comandos que escriben los espectadores en el chat
| Comando | Efecto en la Pelea |
| :--- | :--- |
| `!j1` / `!p1` | Apoya al Equipo 1 (Azul) y sube su barra de Ki |
| `!j2` / `!p2` | Apoya al Equipo 2 (Rojo) y sube su barra de Ki |
| `!j1 goku` / `!j2 broly` | Te anota en la cola de retadores con ese personaje |
| `!ki` | Recarga barra de energía Ki del equipo apoyado |
| `!ssj` / `!transform` | Activa la siguiente transformación si hay Ki |
| `!kaioken` | Activa el aura roja **Kaioken** |
| `!curar` / `!heal` | Recupera +25% de vida (HP) |
| `!kame` / `!blast` | Lanza una ráfaga o súper ataque Ki |
| `!choque` / `!tackle` | Embiste con choque de poderes |
| `!tiempo` / `!timejump` | Congela brevemente el tiempo (Salto Temporal) |

### Poderes por Regalos (Gifts)
| Regalo | Efecto |
| :--- | :--- |
| 🌹 **Rosa** (`1🪙`) | Recarga **+Ki y ráfaga** inmediata |
| 🔥 **GG** (`1🪙`) | Activa **Kaioken / Burst** |
| 🍦 **Helado** (`1🪙`) | **Curación +25% HP** al equipo |
| 👑 **Corona / Perfumes** (`20🪙`) | **Transformación SSJ / Blue / Ultra** |
| 🌌 **Galaxia** (`1000🪙`) | **Súper Kame / Ultra + Salto Temporal** |
| 🦁 **León** (`29999🪙`) | **Modo Dios + Cambio de Arena** |
