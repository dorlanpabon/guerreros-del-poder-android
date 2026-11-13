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
