#!/usr/bin/env python3
"""Device-level checks on a fresh CI emulator; never sends a shared file."""
import hashlib
import io
import json
import math
import os
import re
import struct
import subprocess
import sys
import time
import traceback
import wave
import xml.etree.ElementTree as ET
from pathlib import Path

APP = "com.phocorin.easyaudio"
APK = "app/build/outputs/apk/debug/app-debug.apk"
OUT = Path("app/build/reports/device/additional")
OUT.mkdir(parents=True, exist_ok=True)
results = {}


def adb(*args, data=None, check=True):
    p = subprocess.run(["adb", *args], input=data, stdout=subprocess.PIPE,
                       stderr=subprocess.PIPE, timeout=90)
    if check and p.returncode:
        raise RuntimeError(f"adb {args}: {p.stderr.decode(errors='replace')} {p.stdout.decode(errors='replace')}")
    return p.stdout


def wait_for(predicate, seconds=30):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            value = predicate()
            if isinstance(value, ET.Element) or value:
                return value
        except (RuntimeError, ValueError, ET.ParseError):
            pass
        time.sleep(.3)
    raise AssertionError("Device condition timed out")


def ui():
    adb("shell", "uiautomator", "dump", "--compressed", "/sdcard/easy-audio-ui.xml")
    raw = adb("exec-out", "cat", "/sdcard/easy-audio-ui.xml")
    (OUT / "last-ui.xml").write_bytes(raw)
    return list(ET.fromstring(raw).iter("node"))


def nodes_with_text(text, nodes):
    return [n for n in nodes if text in n.get("text", "")]


def center(node):
    coords = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
    assert len(coords) == 4 and coords[2] > coords[0] and coords[3] > coords[1]
    return ((coords[0] + coords[2]) // 2, (coords[1] + coords[3]) // 2)


def tap(node):
    x, y = center(node)
    adb("shell", "input", "tap", str(x), str(y))


def click_text(text):
    def find():
        matches = nodes_with_text(text, ui())
        visible = [n for n in matches if len(re.findall(r"\d+", n.get("bounds", ""))) == 4]
        visible.sort(key=lambda n: (n.get("clickable") != "true", (center(n)[1], center(n)[0])))
        return visible[0] if visible else None
    tap(wait_for(find))


def click_desc(description):
    tap(wait_for(lambda: next((n for n in ui() if n.get("content-desc") == description), None)))


def has_text(text):
    return bool(nodes_with_text(text, ui()))


def snapshot(name):
    (OUT / f"{name}.png").write_bytes(adb("exec-out", "screencap", "-p"))


def record_result(name):
    results[name] = True
    (OUT / "results.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
    print("DEVICE_CHECK_PASSED=" + name, flush=True)


def library():
    return json.loads(adb("exec-out", "run-as", APP, "cat", "files/library-v1.json"))


def launch(record=False):
    args = ["shell", "am", "start", "-n", APP + "/.MainActivity"]
    if record:
        args += ["-a", APP + ".RECORD"]
    adb(*args)


def write_private(path, content):
    # All paths are constants controlled by this fixture, not filenames from UI.
    adb("exec-in", f'run-as {APP} sh -c "cat > {path}"', data=content)


def pcm(milliseconds):
    output = io.BytesIO()
    with wave.open(output, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(44100)
        wav.writeframes(b"".join(struct.pack("<h", int(9000 * math.sin(i * math.tau * 440 / 44100)))
                                 for i in range(milliseconds * 44100 // 1000)))
    return output.getvalue()


def clear_search():
    for _ in range(3):
        if not any(n.get("class", "").endswith("EditText") for n in ui()):
            return
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
    raise AssertionError("Search did not close")


def search(term, present, absent):
    click_desc("Search names and notes")
    tap(wait_for(lambda: next((n for n in ui() if n.get("class", "").endswith("EditText")), None)))
    adb("shell", "input", "text", term)
    wait_for(lambda: any(n.get("class", "").endswith("EditText") and term in n.get("text", "") for n in ui()))
    wait_for(lambda: has_text(present))
    assert not has_text(absent)
    clear_search()


def sharesheet():
    wait_for(lambda: b"ChooserActivity" in adb("shell", "dumpsys", "activity", "activities"))
    snapshot("share-menu")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")


try:
    assert os.environ.get("GITHUB_ACTIONS") == "true", "This fixture script is restricted to isolated GitHub Actions emulators"
    assert adb("get-serialno").decode().strip().startswith("emulator-"), "Refusing to modify a physical device"
    adb("install", "-r", "-t", APK)
    adb("shell", "pm", "clear", APP)
    adb("shell", "svc", "wifi", "disable", check=False)
    adb("shell", "svc", "data", "disable", check=False)
    adb("shell", "pm", "revoke", APP, "android.permission.RECORD_AUDIO", check=False)
    launch(record=True)
    deny = wait_for(lambda: next((n for n in ui() if n.get("resource-id", "").endswith("permission_deny_button")), None))
    tap(deny)
    wait_for(lambda: has_text("Allow microphone access to record"))
    assert not library()["recordings"]
    assert adb("shell", "pidof", APP).strip()
    snapshot("microphone-denied")
    record_result("microphone_permission_denied_without_crash_or_audio")

    adb("shell", "am", "force-stop", APP)
    adb("shell", "pm", "grant", APP, "android.permission.RECORD_AUDIO")
    adb("shell", "pm", "grant", APP, "android.permission.POST_NOTIFICATIONS")
    adb("shell", "run-as", APP, "mkdir", "-p", "files/recordings")
    data_a, data_b = pcm(2000), pcm(1000)
    write_private("files/recordings/persistence.wav", data_a)
    write_private("files/recordings/alpha.wav", data_b)
    now = int(time.time() * 1000)
    entries = [dict(id="persistence", name="Persistence fixture", file="persistence.wav", created=now,
                    duration=2000, favorite=True, folder="Persisted folder", note="PersistentNoteXYZ",
                    marks=[500, 1500], peaks=[.2747] * 20),
               dict(id="alpha", name="Alpha fixture", file="alpha.wav", created=now+1000,
                    duration=1000, favorite=False, folder="", note="Other note", marks=[], peaks=[.2747] * 10)]
    write_private("files/library-v1.json", json.dumps(dict(version=1, folders=["Persisted folder"], recordings=entries)).encode())
    launch()
    wait_for(lambda: has_text("Persistence fixture"))
    assert len(library()["recordings"]) == 2
    record_result("offline_library_and_metadata_loaded")

    def ordered(first, second):
        nodes = ui()
        a, b = nodes_with_text(first, nodes), nodes_with_text(second, nodes)
        return a and b and center(a[0])[1] < center(b[0])[1]
    assert ordered("Alpha fixture", "Persistence fixture")
    click_desc("Sort"); click_text("Longest first")
    wait_for(lambda: ordered("Persistence fixture", "Alpha fixture"))
    click_desc("Sort"); click_text("Name A–Z")
    wait_for(lambda: ordered("Alpha fixture", "Persistence fixture"))
    record_result("date_name_duration_sorting")
    search("Alpha", "Alpha fixture", "Persistence fixture")
    search("PersistentNoteXYZ", "Persistence fixture", "Alpha fixture")
    record_result("filename_and_note_search")
    click_text("Favorites")
    wait_for(lambda: has_text("Persistence fixture"))
    assert not has_text("Alpha fixture")
    click_text("Folders"); click_text("Persisted folder")
    wait_for(lambda: has_text("Persistence fixture"))
    assert not has_text("Alpha fixture")
    click_text("Recordings")
    wait_for(lambda: has_text("Alpha fixture"))
    record_result("favorites_and_folder_filtering")

    item = wait_for(lambda: next(iter(nodes_with_text("Persistence fixture", ui())), None))
    x, y = center(item)
    adb("shell", "input", "swipe", str(x), str(y), str(x), str(y), "900")
    wait_for(lambda: has_text("Selected: 1"))
    click_text("Alpha fixture")
    wait_for(lambda: has_text("Selected: 2"))
    click_desc("Share"); sharesheet()
    click_desc("Delete")
    wait_for(lambda: has_text("Delete selected recordings?"))
    click_text("Cancel")
    assert len(library()["recordings"]) == 2
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    record_result("multi_select_share_menu_and_delete_cancellation")

    # Delete only the expendable Alpha fixture through the confirmation dialog.
    menu = wait_for(lambda: next((n for n in ui() if n.get("content-desc") == "More options"), None))
    tap(menu); click_text("Delete")
    wait_for(lambda: has_text("Delete selected recordings?"))
    click_text("Delete")
    wait_for(lambda: len(library()["recordings"]) == 1)
    assert library()["recordings"][0]["id"] == "persistence"
    record_result("confirmed_delete_preserves_other_recordings")

    saved = library()
    digest = hashlib.sha256(data_a).hexdigest()
    for mode in ("process_restart", "apk_update"):
        adb("shell", "am", "force-stop", APP)
        if mode == "apk_update":
            adb("install", "-r", "-t", APK)
        launch()
        wait_for(lambda: has_text("Persistence fixture"))
        assert library() == saved
        actual = adb("exec-out", "run-as", APP, "cat", "files/recordings/persistence.wav")
        assert hashlib.sha256(actual).hexdigest() == digest
        record_result(mode + "_preserves_audio_notes_folders_favorites_bookmarks")
    snapshot("retained-recording")
    click_text("Persistence fixture")
    wait_for(lambda: any(re.search(r"00:00:0[12] / 00:00:02", n.get("text", "")) for n in ui()))
    click_desc("Share"); sharesheet()
    record_result("offline_playback_and_single_file_share_menu")

    # Kill capture without a Stop action, then verify journal recovery and no restart.
    launch(record=True)
    def pending_capture():
        filenames = adb("shell", "run-as", APP, "ls", "files/recordings").decode().split()
        for filename in filenames:
            if filename.endswith(".wav.part"):
                data = adb("exec-out", "run-as", APP, "cat", "files/recordings/" + filename)
                if len(data) >= 44 + 44100 * 2 * 2:
                    return filename
        return None
    pending = wait_for(pending_capture)
    recovered_file = pending.removesuffix(".part")
    adb("shell", "am", "force-stop", APP)
    launch()
    wait_for(lambda: any(r["file"] == recovered_file for r in library()["recordings"]))
    recovered = adb("exec-out", "run-as", APP, "cat", "files/recordings/" + recovered_file)
    with wave.open(io.BytesIO(recovered), "rb") as recovered_audio:
        assert recovered_audio.getnframes() >= 44100
        assert recovered_audio.getframerate() == 44100
    assert struct.unpack_from("<I", recovered, 40)[0] == len(recovered) - 44
    assert b".RecordingService" not in adb("shell", "dumpsys", "activity", "services", APP)
    snapshot("recovered-recording")
    record_result("process_kill_recovers_audio_without_hidden_recording_restart")
except BaseException as failure:
    results["failure"] = str(failure)
    (OUT / "results.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
    (OUT / "failure.txt").write_text(traceback.format_exc(), encoding="utf-8")
    try:
        snapshot("failure")
    except BaseException:
        pass
    raise