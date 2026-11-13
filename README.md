# Ultimate Warriors — Android Native Mobile (Vertical)

Versión **100% nativa para Android (Kotlin + SurfaceView/Canvas + SoundPool/MediaPlayer)** en orientación **Vertical (9:16)** a pantalla completa, basada fielmente en [**Ultimate Warriors**](https://github.com/elJulioDev/Ultimate-Warriors) de **elJulioDev**.

## Características

- **Contenido Original Completo**:
  - **30 personajes** (`chars/`) con todas sus fases, transformaciones (incluyendo fases decimales como `6.5` de Vegeta) y sprites originales.
  - **22 escenarios** (`stages/`) con filtro de color, scroll horizontal (`MoveBackground`) y sombras dinámicas.
  - **25 pistas musicales MP3** (`sound/`) y **44 efectos de sonido** (`data/sound_effects/`).
  - **106 efectos de aura** (`CharEffects/`), **46 efectos de combate** (`GameEffects/`), **4 tipografías originales** (`font/`) e **interfaz bilingüe Español / Inglés** (`Language.json`).
- **Motor `dbxwCore` Nativo en Kotlin**:
  - Gravedad (`1000`), salto/doble salto (`-500`), barras de carga (`0..283`, niveles `0..3`), `AndroidSystem`, transformaciones y fases de truco (`MUI`, `UE`, `BLACK`, `SSFP`), `Kaioken xN`, `TimeJump`, `Teleport`, `UpDim`, `RecoverLife`, `KiShot` con choque de proyectiles, `Tackle` y `ClashTackle` (choque de embestidas), knockback crítico y cálculo de rango (`GOD`, `Z+`, `Z`, `S+`, `S`, `A`, `B`, `C`, `D`).
- **Modos de Juego**:
  - **1 Jugador vs Bot** (IA adaptativa portada de `data/bot/`).
  - **2 Jugadores Local** (controles táctiles duales en el mismo teléfono).
  - **Bot vs Bot** (espectador con velocidad `x1`, `x2`, `x4`).
  - **Modo Historia** (12 capítulos recorriendo las sagas).
  - **🔴 TikTok Live Interactivo (`v1.0.2+` / `v1.0.3`)**: Arena 24/7 (`Chat vs Chat`) y `Streamer vs Chat`, con conexión **directa en el teléfono (`@usuario` sin PC)**, soporte **Termux/Python** (`scripts/tiktok_live_bridge.py`), **TikFinity (`:21213`)** y **Webhook HTTP (`:8765/event`)**. Consulta la guía completa en [**TIKTOK_LIVE_GUIA.md**](TIKTOK_LIVE_GUIA.md).

## Novedades `v1.0.3`
- **Botones táctiles y menús responsivos**: disparo inmediato en menús/pausa sin perder toques por movimiento mínimo, cola segura entre hilos (`uiCommandQueue`), áreas táctiles ampliadas (`1.38x`) y retención de pulsos en combate (`TouchControls`).
- **Golpes, patadas y Ki fieles**: animaciones cuerpo a cuerpo sin encogimiento vertical y con alcance extendido hacia el rival (`fixAttackWidth`), *hit-pause*, chispas de impacto más visibles, `blast.png` propio por personaje/fase y contador de combos en arena.
- **Tipografía legible en Configuraciones y UI**: fuente limpia (`sans-serif Bold`) con autoajuste en filas tocables completas de Configuraciones y paneles de TikTok Live.
- **TikTok Live directo desde el teléfono**: botón **`📱 CONECTAR @USUARIO EN TELÉFONO`** + ayuda integrada **`❓ CÓMO CONFIGURAR`** y guía en [TIKTOK_LIVE_GUIA.md](TIKTOK_LIVE_GUIA.md).

## Compilación

```powershell
# Ejecutar pruebas unitarias
.\gradlew.bat testDebugUnitTest --console=plain

# Compilar APK Debug nativo
.\gradlew.bat assembleDebug --console=plain
```

El APK generado queda en `app/build/outputs/apk/debug/app-debug.apk` (y copiado a `dist-apk/`).

## Créditos

- Juego original, sprites, música y motor `dbxwCore`: [elJulioDev / Ultimate-Warriors](https://github.com/elJulioDev/Ultimate-Warriors).
- Port nativo Android Vertical: `dorlanpabon`.
