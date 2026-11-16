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
import struct
import zlib

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


def png_left_edge(path):
    """Decode x=0 pixel using only the Python standard library (PNG filters 0-4)."""
    data=path.read_bytes()
    assert data[:8]==b"\x89PNG\r\n\x1a\n"
    p=8; packed=[]
    while p < len(data):
        size=struct.unpack(">I",data[p:p+4])[0]
        kind=data[p+4:p+8]
        chunk=data[p+8:p+8+size]
        p+=size+12
        if kind==b"IHDR":
            width,height,depth,color,_,_,interlace=struct.unpack(">IIBBBBB",chunk)
        elif kind==b"IDAT":
            packed.append(chunk)
        elif kind==b"IEND":
            break
    if depth!=8 or color not in (2,6) or interlace:
        raise ValueError(f"Unexpected screenshot PNG format {depth}/{color}/{interlace}")
    bpp=3 if color==2 else 4
    raw=zlib.decompress(b"".join(packed))
    stride=1+width*bpp
    prev=[0]*bpp; result=[]
    for y in range(height):
        idx=y*stride
        ftype=raw[idx]
        pix=list(raw[idx+1:idx+1+bpp])
        for channel in range(bpp):
            if ftype in (0,1):
                val=pix[channel]
            elif ftype in (2,4):
                val=(pix[channel]+prev[channel])&255
            elif ftype==3:
                val=(pix[channel]+prev[channel]//2)&255
            else:
                raise ValueError(f"Unsupported PNG filter {ftype}")
            pix[channel]=val
        prev=pix
        result.append(tuple(pix[:3]))
    return result

def find_keyboard_top(png_path,composer_bottom):
    edge=png_left_edge(png_path)
    height=len(edge)
    samples=edge[max(0,composer_bottom-130):max(1,composer_bottom-40)]
    background=tuple(sorted(px[c] for px in samples)[len(samples)//2] for c in range(3))
    threshold=23
    def differs(y):
        return sum(abs(edge[y][c]-background[c]) for c in range(3)) >= threshold
    for y in range(max(composer_bottom-20,height//3),min(height-180,composer_bottom+700)):
        if all(differs(k) for k in range(y,y+8)):
            return y,background
    raise AssertionError("Keyboard edge not found in Android screenshot")

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

    # UIAutomator omits third-party IME nodes on this emulator, even while
    # the keyboard is visibly open. Use its actual screenshot edge instead.
    time.sleep(2.5)
    nodes=ui_nodes()
    screenshot("03-coach-ime-open")
    focused=[n for n in nodes if n.get("class")=="android.widget.EditText" and
             n.get("focused")=="true"]
    if len(focused)!=1:
        raise AssertionError("Coach input was not focused")
    x1,y1,x2,y2=bounds(focused[0])
    ktop,bg=find_keyboard_top(OUT/"03-coach-ime-open.png",y2)
    density_text=adb("shell","wm","density")
    dpi=int(re.search(r"\d+",density_text).group(0))
    gap_dp=(ktop-y2)/(dpi/160.0)
    header_nodes=[n for n in nodes if "Ask about your recovery" in n.get("text","")]
    if not header_nodes:
        raise AssertionError("Coach header not visible when keyboard opens")
    header_top_dp=min(bounds(n)[1] for n in header_nodes)/(dpi/160.0)
    evidence={"density_dpi":dpi,"keyboard_top_px":ktop,"composer_text_bottom_px":y2,
              "gap_dp":round(gap_dp,1),"header_top_dp":round(header_top_dp,1),
              "keyboard_edge_background_rgb":bg}
    (OUT/"measurements.json").write_text(json.dumps(evidence,indent=2),encoding="utf-8")
    print("COACH IME GEOMETRY: "+json.dumps(evidence),flush=True)
    assert -20 < gap_dp < 40, f"Chat box is not next to keyboard (gap={gap_dp:.1f}dp)"
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
