#!/usr/bin/env python3
"""Reads the CarPlay video reorder depth from `video codec config ... hex=` logcat lines or an .mp4.

    python3 scripts/video_reorder.py logcat.txt [sample.mp4 ...]

HEVC: sps_max_num_reorder_pics; H.264: VUI max_num_reorder_frames (null when the SPS does not signal it).
Depth 0 means output order equals decoding order, so the decoder may release each frame at once
(vendor.qti-ext-dec-picture-order.enable). Above 0 the stream has reordered (B) frames.
"""
import sys

class Bits:
    def __init__(self, data):
        self.d, self.p = data, 0
    def u(self, n):
        v = 0
        for _ in range(n):
            v = (v << 1) | ((self.d[self.p >> 3] >> (7 - (self.p & 7))) & 1)
            self.p += 1
        return v
    def ue(self):
        z = 0
        while self.u(1) == 0:
            z += 1
        return (1 << z) - 1 + self.u(z)
    def se(self):
        k = self.ue()
        return (k + 1) // 2 if k & 1 else -(k // 2)
    def more(self):
        return self.p < len(self.d) * 8

def rbsp(nal):
    out, zeros = bytearray(), 0
    for b in nal:
        if zeros >= 2 and b == 3:
            zeros = 0
            continue
        out.append(b)
        zeros = zeros + 1 if b == 0 else 0
    return bytes(out)

def hevc_sps(nal):
    b = Bits(rbsp(nal[2:]))
    b.u(4); max_sub = b.u(3); b.u(1)
    b.u(2); b.u(1); profile = b.u(5); b.u(32); b.u(48); level = b.u(8)
    present = [(b.u(1), b.u(1)) for _ in range(max_sub)]
    if max_sub > 0:
        for _ in range(max_sub, 8): b.u(2)
    for prof, lev in present:
        if prof: b.u(88)
        if lev: b.u(8)
    b.ue()
    chroma = b.ue()
    if chroma == 3: b.u(1)
    w, h = b.ue(), b.ue()
    if b.u(1):
        for _ in range(4): b.ue()
    b.ue(); b.ue(); b.ue()
    ordering_all = b.u(1)
    layers = []
    for _ in range(0 if ordering_all else max_sub, max_sub + 1):
        layers.append({"max_dec_pic_buffering": b.ue() + 1, "max_num_reorder_pics": b.ue(), "max_latency_increase_plus1": b.ue()})
    return {"codec": "H265", "profile_idc": profile, "level_idc": level, "width": w, "height": h,
            "sub_layers": max_sub + 1, "per_sub_layer": layers, "reorder": layers[-1]["max_num_reorder_pics"]}

def scaling_list(b, size):
    last = nxt = 8
    for _ in range(size):
        if nxt:
            nxt = (last + b.se() + 256) % 256
        last = nxt or last

def avc_sps(nal):
    b = Bits(rbsp(nal[1:]))
    profile = b.u(8); b.u(8); level = b.u(8); b.ue()
    if profile in (100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135):
        chroma = b.ue()
        if chroma == 3: b.u(1)
        b.ue(); b.ue(); b.u(1)
        if b.u(1):
            for i in range(8 if chroma != 3 else 12):
                if b.u(1): scaling_list(b, 16 if i < 6 else 64)
    b.ue()
    poc = b.ue()
    if poc == 0: b.ue()
    elif poc == 1:
        b.u(1); b.se(); b.se()
        for _ in range(b.ue()): b.se()
    max_refs = b.ue(); b.u(1)
    w, h = (b.ue() + 1) * 16, b.ue() + 1
    frame_mbs_only = b.u(1)
    if not frame_mbs_only: b.u(1)
    b.u(1)
    if b.u(1):
        for _ in range(4): b.ue()
    info = {"codec": "H264", "profile_idc": profile, "level_idc": level, "width": w,
            "height_map_units": h, "poc_type": poc, "max_num_ref_frames": max_refs}
    if not b.u(1):
        info["reorder"] = None
        info["note"] = "no VUI; reorder not signalled"
        return info
    if b.u(1):
        if b.u(8) == 255: b.u(32)
    if b.u(1): b.u(1)
    if b.u(1):
        b.u(4)
        if b.u(1): b.u(24)
    if b.u(1): b.ue(); b.ue()
    if b.u(1): b.u(32); b.u(32); b.u(1)
    def hrd():
        n = b.ue() + 1; b.u(8)
        for _ in range(n): b.ue(); b.ue(); b.u(1)
        b.u(20)
    nal_hrd, vcl_hrd = b.u(1), None
    if nal_hrd: hrd()
    vcl_hrd = b.u(1)
    if vcl_hrd: hrd()
    if nal_hrd or vcl_hrd: b.u(1)
    b.u(1)
    if b.u(1):
        b.u(1); b.ue(); b.ue(); b.ue(); b.ue()
        info["reorder"] = b.ue(); info["max_dec_frame_buffering"] = b.ue()
    else:
        info["reorder"] = None
        info["note"] = "no bitstream_restriction; reorder not signalled"
    return info

def from_hvcc(rec):
    p, out = 23, []
    for _ in range(rec[22]):
        t = rec[p] & 0x3f; n = (rec[p + 1] << 8) | rec[p + 2]; p += 3
        for _ in range(n):
            ln = (rec[p] << 8) | rec[p + 1]; p += 2
            if t == 33: out.append(hevc_sps(rec[p:p + ln]))
            p += ln
    return out

def from_avcc(rec):
    p, out = 6, []
    for _ in range(rec[5] & 0x1f):
        ln = (rec[p] << 8) | rec[p + 1]; p += 2
        out.append(avc_sps(rec[p:p + ln])); p += ln
    return out

def parse(rec, codec=None):
    if codec == "H264" or (codec is None and rec[0] == 1 and len(rec) > 8 and rec[8] & 0x1f == 7):
        return from_avcc(rec)
    return from_hvcc(rec)

def from_mp4(path):
    data = open(path, "rb").read()
    for tag, fn in ((b"hvcC", from_hvcc), (b"avcC", from_avcc)):
        i = data.find(tag)
        if i >= 0:
            size = int.from_bytes(data[i - 4:i], "big")
            return fn(data[i + 4:i - 4 + size])
    raise SystemExit("no hvcC/avcC in " + path)

if __name__ == "__main__":
    import json, re
    for arg in sys.argv[1:]:
        if arg.endswith(".mp4"):
            print(arg, json.dumps(from_mp4(arg)))
            continue
        # A logcat file: every "video codec config ... hex=" line.
        for line in open(arg, errors="replace"):
            m = re.search(r"video codec config codec=(H26[45]).* hex=([0-9a-f]+)", line)
            if m:
                print(line[:31].strip(), json.dumps(parse(bytes.fromhex(m.group(2)), m.group(1))))
