import json
import os
import re
import shutil
import zipfile
from pathlib import Path

UPSTREAM = Path(os.environ["TEMP"]) / "uw_upstream"
PROJECT_ROOT = Path(__file__).resolve().parent.parent
ASSETS = PROJECT_ROOT / "app" / "src" / "main" / "assets"
RES = PROJECT_ROOT / "app" / "src" / "main" / "res"

ANIM_PREFIXES = [
    ("fly_left", "fly_left"),
    ("fly_right", "fly_right"),
    ("coverup", "CoverUp"),
    ("damaged", "damaged"),
    ("taunt", "taunt"),
    ("punch", "punch"),
    ("kick", "kick"),
    ("power", "power"),
    ("aura", "aura"),
    ("dodge", "dodge"),
    ("roll", "roll"),
    ("tackle", "tackle"),
    ("stand", "stand"),
    ("up", "up"),
    ("down", "down"),
    ("win", "win"),
    ("lose", "lose"),
]


def copy_tree_clean(src: Path, dst: Path):
    if dst.exists():
        shutil.rmtree(dst)
    shutil.copytree(src, dst)


def parse_number(val, default=0.0):
    if isinstance(val, (int, float)):
        return float(val)
    if isinstance(val, str):
        val = val.strip().replace(",", ".")
        try:
            return float(val)
        except ValueError:
            return default
    return default


def parse_bool(val, default=False):
    if isinstance(val, bool):
        return val
    if isinstance(val, str):
        return val.strip().lower() in ("true", "1", "yes", "si")
    if isinstance(val, (int, float)):
        return val != 0
    return default


def extract_ppsm_media_and_layout(ppsm_path: Path):
    media_out = ASSETS / "data" / "resources" / "ppsm_media"
    media_out.mkdir(parents=True, exist_ok=True)
    layout = {}
    with zipfile.ZipFile(ppsm_path, "r") as z:
        for name in z.namelist():
            if name.startswith("ppt/media/"):
                out_file = media_out / Path(name).name
                out_file.write_bytes(z.read(name))

        for slide_num in [2, 3, 6, 7, 11, 12, 18]:
            slide_xml = z.read(f"ppt/slides/slide{slide_num}.xml").decode("utf-8", errors="ignore")
            rels_xml = z.read(f"ppt/slides/_rels/slide{slide_num}.xml.rels").decode("utf-8", errors="ignore")
            rmap = dict(re.findall(r'Id="(rId\d+)"[^>]*Target="([^"]+)"', rels_xml))
            pics = {}
            for pic_name, rid in re.findall(
                r'<p:pic>.*?cNvPr[^>]*name="([^"]+)".*?blip[^>]*r:embed="(rId\d+)".*?</p:pic>',
                slide_xml,
                re.S,
            ):
                target = rmap.get(rid, "")
                if target:
                    pics[pic_name] = Path(target).name
            layout[f"slide{slide_num}"] = pics

    (ASSETS / "data" / "ppsm_layout.json").write_text(
        json.dumps(layout, indent=2, ensure_ascii=False), encoding="utf-8"
    )


def scan_phase_animations(phase_dir: Path, rel_prefix: str):
    anims = {}
    if not phase_dir.exists():
        return anims
    files = sorted(
        [f.name for f in phase_dir.iterdir() if f.is_file() and f.suffix.lower() in (".png", ".jpg", ".jpeg", ".gif")],
        key=lambda x: [int(t) if t.isdigit() else t.lower() for t in re.split(r"(\d+)", x)],
    )
    for anim_key, prefix in ANIM_PREFIXES:
        matched = []
        p_low = prefix.lower()
        for fname in files:
            stem = Path(fname).stem.lower()
            if stem == p_low or stem.startswith(p_low + "_"):
                matched.append(f"{rel_prefix}/{fname}")
        if matched:
            anims[anim_key] = matched
    return anims


def build_manifest():
    chars_dir = ASSETS / "chars"
    char_list = []
    for folder_path in sorted(chars_dir.iterdir(), key=lambda p: p.name.lower()):
        if not folder_path.is_dir():
            continue
        char_json_path = folder_path / "char.json"
        if not char_json_path.exists():
            continue
        raw = json.loads(char_json_path.read_text(encoding="utf-8-sig"))

        stats = raw.get("statistics", {})
        tx_raw = raw.get("transformation", {})
        tx_enabled = parse_bool(tx_raw.get("eneable", tx_raw.get("enable", False)))
        transformations = {}
        if tx_enabled:
            for k, v in tx_raw.items():
                if k.lower().startswith("phase"):
                    num_str = k.lower().replace("phase", "").strip().replace(",", ".")
                    try:
                        transformations[str(float(num_str))] = str(v)
                    except ValueError:
                        pass

        colors_raw = raw.get("transformation color", {})
        colors_enabled = parse_bool(colors_raw.get("eneable", colors_raw.get("enable", False)))
        tx_colors = {}
        for k, v in colors_raw.items():
            if isinstance(v, dict):
                norm_key = k.strip().lower().replace(",", ".")
                tx_colors[norm_key] = {
                    "color": str(v.get("color", "#FFFFFF")),
                    "radio": parse_number(v.get("radio", 3)),
                    "transparency": parse_number(v.get("transparecy", v.get("transparency", 50))),
                }

        base_color_raw = raw.get("base color", {})
        base_color = None
        if isinstance(base_color_raw, dict) and parse_bool(
            base_color_raw.get("eneable", base_color_raw.get("enable", False))
        ):
            base_color = {
                "color": str(base_color_raw.get("color", "#FFFFFF")),
                "radio": parse_number(base_color_raw.get("radio", 3)),
                "transparency": parse_number(base_color_raw.get("transparecy", base_color_raw.get("transparency", 50))),
            }

        effects_raw = raw.get("Char Effects", {})
        char_effects = {}
        if isinstance(effects_raw, dict):
            for k, v in effects_raw.items():
                if isinstance(v, dict):
                    norm_key = k.strip().lower().replace(",", ".")
                    char_effects[norm_key] = {
                        "enabled": parse_bool(v.get("eneable", v.get("enable", False))),
                        "effect": str(v.get("effect", "")),
                        "speed": parse_number(v.get("speed", 0.12), 0.12),
                        "transparency": parse_number(v.get("transparecy", v.get("transparency", 70)), 70.0),
                    }

        cheats_raw = raw.get("cheats", {})
        cheats = []
        if isinstance(cheats_raw, dict):
            for _, v in sorted(cheats_raw.items()):
                if v:
                    cheats.append(str(v).strip())

        # Scan all sub-folders inside char folder (case-insensitive mapping)
        phases_frames = {}
        subfolders = {d.name.lower(): d.name for d in folder_path.iterdir() if d.is_dir()}
        for sub_low, sub_real in subfolders.items():
            phases_frames[sub_low] = scan_phase_animations(
                folder_path / sub_real, f"chars/{folder_path.name}/{sub_real}"
            )

        ico_file = None
        for cand in ["ico.png", "ico.jpg", "icon.png"]:
            if (folder_path / cand).exists():
                ico_file = f"chars/{folder_path.name}/{cand}"
                break

        char_list.append(
            {
                "folder": folder_path.name,
                "base": str(raw.get("base", "base")),
                "name": str(raw.get("name", folder_path.name)),
                "ico": ico_file,
                "statistics": {
                    "speed": parse_number(stats.get("speed", 420), 420.0),
                    "life": parse_number(stats.get("life", 100), 100.0),
                    "damaged": parse_number(stats.get("damaged", 95), 95.0),
                    "defence": parse_number(stats.get("defence", 17), 17.0),
                    "attackSpeed": parse_number(stats.get("Attack Speed", 7.5), 7.5),
                    "standSpeed": parse_number(stats.get("Stand Speed", 12), 12.0),
                    "tauntSpeed": parse_number(stats.get("Taunt Speed", 12), 12.0),
                    "auraSpeed": parse_number(stats.get("Aura Speed", 1), 1.0),
                },
                "transformationEnabled": tx_enabled,
                "transformations": transformations,
                "transformationColorsEnabled": colors_enabled,
                "transformationColors": tx_colors,
                "baseColor": base_color,
                "charEffects": char_effects,
                "cheats": cheats,
                "phases": phases_frames,
            }
        )

    stages_dir = ASSETS / "stages"
    stage_list = []
    for folder_path in sorted(stages_dir.iterdir(), key=lambda p: p.name.lower()):
        if not folder_path.is_dir():
            continue
        sj = folder_path / "stage.json"
        if not sj.exists():
            continue
        raw = json.loads(sj.read_text(encoding="utf-8-sig"))
        st_settings = raw.get("settings", {})
        stage_img = None
        for cand in ["Stage.png", "stage.png", "Stage.jpg"]:
            if (folder_path / cand).exists():
                stage_img = f"stages/{folder_path.name}/{cand}"
                break
        ico_img = None
        for cand in ["ico.png", "ico.jpg"]:
            if (folder_path / cand).exists():
                ico_img = f"stages/{folder_path.name}/{cand}"
                break
        stage_list.append(
            {
                "folder": folder_path.name,
                "name": str(raw.get("name", folder_path.name)),
                "stageImage": stage_img,
                "icoImage": ico_img,
                "filter": parse_bool(st_settings.get("filter", False)),
                "color": str(st_settings.get("color", "")),
                "shadow": parse_bool(st_settings.get("shadow", True), True),
                "effects": parse_bool(st_settings.get("effects", True), True),
            }
        )

    char_effects_dir = ASSETS / "data" / "resources" / "CharEffects"
    char_effects_map = {}
    for f in sorted(
        char_effects_dir.iterdir(),
        key=lambda x: [int(t) if t.isdigit() else t.lower() for t in re.split(r"(\d+)", x.name)],
    ):
        if f.is_file() and "_" in f.stem:
            prefix = f.stem.rsplit("_", 1)[0].lower()
            char_effects_map.setdefault(prefix, []).append(f"data/resources/CharEffects/{f.name}")

    game_effects_dir = ASSETS / "data" / "resources" / "GameEffects"
    game_effects_map = {}
    for f in sorted(
        game_effects_dir.iterdir(),
        key=lambda x: [int(t) if t.isdigit() else t.lower() for t in re.split(r"(\d+)", x.name)],
    ):
        if f.is_file():
            stem = f.stem.lower()
            prefix = stem.rsplit("_", 1)[0] if "_" in stem else stem
            game_effects_map.setdefault(prefix, []).append(f"data/resources/GameEffects/{f.name}")

    music_dir = ASSETS / "sound"
    music_tracks = [
        {"name": f.stem, "path": f"sound/{f.name}"}
        for f in sorted(music_dir.iterdir(), key=lambda x: x.name.lower())
        if f.is_file() and f.suffix.lower() in (".mp3", ".wav", ".ogg")
    ]

    sfx_dir = ASSETS / "data" / "sound_effects"
    sfx_files = [
        {"key": f.stem, "path": f"data/sound_effects/{f.name}"}
        for f in sorted(sfx_dir.iterdir(), key=lambda x: x.name.lower())
        if f.is_file()
    ]

    manifest = {
        "characters": char_list,
        "stages": stage_list,
        "charEffects": char_effects_map,
        "gameEffects": game_effects_map,
        "musicTracks": music_tracks,
        "soundEffects": sfx_files,
    }
    (ASSETS / "asset_manifest.json").write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    print(
        f"Manifest generated: {len(char_list)} chars, {len(stage_list)} stages, "
        f"{len(char_effects_map)} charEffect groups, {len(game_effects_map)} gameEffect groups, "
        f"{len(music_tracks)} music tracks, {len(sfx_files)} sfx files."
    )


def main():
    if not UPSTREAM.exists():
        raise RuntimeError(f"Upstream clone not found at {UPSTREAM}")

    ASSETS.mkdir(parents=True, exist_ok=True)
    copy_tree_clean(UPSTREAM / "chars", ASSETS / "chars")
    copy_tree_clean(UPSTREAM / "stages", ASSETS / "stages")
    copy_tree_clean(UPSTREAM / "font", ASSETS / "font")
    copy_tree_clean(UPSTREAM / "sound", ASSETS / "sound")

    data_dst = ASSETS / "data"
    if data_dst.exists():
        shutil.rmtree(data_dst)
    data_dst.mkdir(parents=True, exist_ok=True)

    for fname in ["Language.json", "Settings.json", "Controls.json", "Changelog.txt", "version.data", "icon.png"]:
        src_file = UPSTREAM / "data" / fname
        if src_file.exists():
            shutil.copy2(src_file, data_dst / fname)

    copy_tree_clean(UPSTREAM / "data" / "sound_effects", data_dst / "sound_effects")
    copy_tree_clean(UPSTREAM / "data" / "resources", data_dst / "resources")

    extract_ppsm_media_and_layout(UPSTREAM / "Game.ppsm")
    build_manifest()

    # Copy original icon.png as launcher icon
    mipmap = RES / "mipmap-xxxhdpi"
    mipmap.mkdir(parents=True, exist_ok=True)
    shutil.copy2(UPSTREAM / "data" / "icon.png", mipmap / "ic_launcher.png")
    shutil.copy2(UPSTREAM / "data" / "icon.png", mipmap / "ic_launcher_round.png")
    print("Assets and launcher icon prepared successfully.")


if __name__ == "__main__":
    main()
