#!/usr/bin/env python3
"""End-to-end Coach keyboard visual regression on a real Android emulator.

Runs the exact production APK from the successful verification build,
without modifying, rebuilding, or signing the app under test.
"""
import json
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

APP = "com.otidotid.thoop.debug"
OUT = Path("coach-blackbox-evidence")
OUT.mkdir(parents=True, exist_ok=True)

def run(*argv, check=True, timeout=60, capture=True):
    result = subprocess.run(argv, stdout=subprocess.PIPE if capture else None,
                            stderr=subprocess.STDOUT if capture else None,
                            text=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(f"{argv} returned {result.returncode}: {result.stdout[-1500:]}")
    return (result.stdout or "").strip()

def adb(*argv, **kwargs):
    return run("adb", *argv, **kwargs)

def screenshot(label):
    with (OUT / (label + ".png")).open("wb") as f:
        subprocess.run(["adb", "exec-out", "screencap", "-p"], stdout=f, timeout=45, check=False)

def ui_nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/coach-layout.xml", timeout=35)
    xml = adb("shell", "cat", "/sdcard/coach-layout.xml", timeout=20)
    (OUT / "ui-hierarchy.xml").write_text(xml, encoding="utf-8")
    tree = ET.fromstring(xml[xml.index("<?xml"):])
    return [node.attrib for node in tree.iter("node")]

def bounds(node):
    nums = [int(x) for x in re.findall(r"\d+", node["bounds"])]
    return nums  # x1,y1,x2,y2

def pick(nodes, text, attrs=("content-desc", "text"), timeout=30):
    deadline = time.time()+timeout
    while time.time() < deadline:
        for node in nodes:
            if any(node.get(attr, "") == text for attr in attrs) and node.get("bounds"):
                x1,y1,x2,y2 = bounds(node)
                if x2>x1 and y2>y1:
                    return node
        time.sleep(1)
        nodes=ui_nodes()
    raise RuntimeError(f"Can't find accessible node {text!r}. Closest nodes: "+str(
        [(n.get("text"),n.get("content-desc")) for n in nodes if text.lower() in (n.get("text","")+n.get("content-desc","")).lower()][:12]))

def tap(node):
    x1,y1,x2,y2 = bounds(node)
    adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
    time.sleep(1.3)

def main():
    apk_files=list(Path("test-apk").glob("*.apk"))
    if len(apk_files)!=1:
        raise RuntimeError("Expected exactly one debug APK in test-apk: "+str(apk_files))
    adb("install", "-r", str(apk_files[0]), timeout=150)
    adb("shell", "settings", "put", "secure", "show_ime_with_hard_keyboard", "1")
    for permission in ["android.permission.BLUETOOTH_SCAN",
                       "android.permission.BLUETOOTH_CONNECT",
                       "android.permission.POST_NOTIFICATIONS"]:
        adb("shell", "pm", "grant", APP, permission, check=False)

    prefs='''<?xml version="1.0" encoding="utf-8" standalone="yes" ?>
<map>
    <boolean name="noop.onboarded" value="true" />
    <string name="noop.acceptedTermsVersion">2.0</string>
    <string name="noop.lastSeenChangelogVersion">11.8.0</string>
</map>
'''
    (OUT/"noop_prefs.xml").write_text(prefs,encoding="utf-8")
    adb("push",str(OUT/"noop_prefs.xml"),"/data/local/tmp/coach-noop-prefs.xml")
    adb("shell","run-as",APP,"mkdir","-p","shared_prefs")
    adb("shell","run-as",APP,"cp","/data/local/tmp/coach-noop-prefs.xml","shared_prefs/noop_prefs.xml")
    adb("shell","am","start","-W","-n",APP+"/com.noop.ui.MainActivity",timeout=60)
    time.sleep(4)
    screenshot("01-boot")
    nodes=ui_nodes()
    tap(pick(nodes,"More",timeout=25))
    nodes=ui_nodes()
    tap(pick(nodes,"Nutrition & Coach",timeout=25))
    nodes=ui_nodes()
    tap(pick(nodes,"Coach",attrs=("text",),timeout=25))
    nodes=ui_nodes()
    screenshot("02-coach-closed")
    tap(pick(nodes,"Message THOOP Coach",attrs=("text","content-desc"),timeout=25))

    keyboard=[]
    for _ in range(25):
        time.sleep(1)
        nodes=ui_nodes()
        keyboard=[n for n in nodes if "inputmethod" in n.get("package","").lower() or
                  "latin" in n.get("package","").lower()]
        if keyboard:
            break
    screenshot("03-coach-ime-open")
    if not keyboard:
        raise AssertionError("Android keyboard did not become visible")

    msg=pick(nodes,"Message THOOP Coach",timeout=5)
    x1,y1,x2,y2=bounds(msg)
    ktop=min(bounds(n)[1] for n in keyboard if n.get("bounds") and
             bounds(n)[3]>bounds(n)[1])
    density_text=adb("shell","wm","density")
    dpi=int(re.search(r"\d+",density_text).group(0))
    gap_dp=(ktop-y2)/(dpi/160.0)
    header_nodes=[n for n in nodes if "Ask about your recovery" in n.get("text","")]
    if not header_nodes:
        raise AssertionError("Coach header not visible when keyboard opens")
    header_top_dp=min(bounds(n)[1] for n in header_nodes)/(dpi/160.0)
    evidence={"density_dpi":dpi,"keyboard_top_px":ktop,"composer_text_bottom_px":y2,
              "gap_dp":round(gap_dp,1),"header_top_dp":round(header_top_dp,1),
              "keyboard_package":keyboard[0].get("package","")}
    (OUT/"measurements.json").write_text(json.dumps(evidence,indent=2),encoding="utf-8")
    print("COACH IME GEOMETRY: "+json.dumps(evidence),flush=True)
    assert -20 < gap_dp < 90, f"Chat box is not next to keyboard (gap={gap_dp:.1f}dp)"
    assert header_top_dp>18, f"Coach header obscured by status bar ({header_top_dp:.1f}dp)"
    print("PASS: composer adjacent to keyboard; header below status bar",flush=True)

if __name__=="__main__":
    try:
        main()
    except Exception as exc:
        try:screenshot("error")
        except Exception:pass
        print("COACH IME TEST FAILED: "+str(exc),flush=True)
        raise
