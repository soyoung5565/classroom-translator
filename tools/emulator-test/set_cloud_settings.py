# -*- coding: utf-8 -*-
"""在模拟器上改写 App 的云端设置(DataStore preferences pb)。
用法: python set_cloud_settings.py <cloud_base_url> [<cloud_model>]
示例:
  python set_cloud_settings.py http://127.0.0.1:13099/v1 deepseek-v4-1-flash   # mock
  python set_cloud_settings.py https://ark.cn-beijing.volces.com/api/plan/v3 deepseek-v4-1-flash  # 真云端
"""
import sys, subprocess, pathlib, hashlib, os

ADB = 'D:/android-sdk/platform-tools/adb.exe'
DEV = '127.0.0.1:16384'
PKG = 'com.classroom.translator'
REL = 'files/datastore/settings.preferences_pb'


def read_varint(b, i):
    r = 0; s = 0
    while True:
        x = b[i]; i += 1
        r |= (x & 0x7F) << s
        if not (x & 0x80):
            return r, i
        s += 7


def parse(b):
    i = 0
    while i < len(b):
        tag, i = read_varint(b, i)
        f, w = tag >> 3, tag & 7
        if w == 2:
            ln, i = read_varint(b, i)
            yield f, b[i:i + ln]; i += ln
        elif w == 0:
            v, i = read_varint(b, i)
            yield f, v
        else:
            raise Exception('wire %d' % w)


def enc_varint(n):
    out = bytearray()
    while True:
        x = n & 0x7F; n >>= 7
        if n:
            out.append(x | 0x80)
        else:
            out.append(x)
            return bytes(out)


def fld(num, wire, payload):
    return enc_varint((num << 3) | wire) + payload


def enc_str(num, s):
    s = s.encode('utf-8')
    return fld(num, 2, enc_varint(len(s)) + s)


def enc_int(num, v):
    return fld(num, 0, enc_varint(v))


def enc_entry(key, typ, val):
    # 注意: fld() 不含长度前缀 → LEN 字段手工加 enc_varint(len(payload))
    payload = enc_str(5, val) if typ == 'string' else enc_int(3, val)
    inner = enc_str(1, key) + fld(2, 2, enc_varint(len(payload)) + payload)
    return fld(1, 2, enc_varint(len(inner)) + inner)


def decode(data):
    out = []
    for f, payload in parse(data):
        if f != 1:
            continue
        entry = {}
        for f2, p2 in parse(payload):
            if f2 == 1:
                entry['key'] = p2.decode('utf-8')
            elif f2 == 2:
                for f3, p3 in parse(p2):
                    if f3 == 5:
                        entry['type'] = 'string'; entry['val'] = p3.decode('utf-8')
                    elif f3 == 3:
                        entry['type'] = 'int'; entry['val'] = p3
                    elif f3 == 1:
                        entry['type'] = 'bool'; entry['val'] = p3
        if 'key' in entry:
            out.append(entry)
    return out


def adb(*args, binary_out=None):
    cmd = [ADB, '-s', DEV] + list(args)
    if binary_out is not None:
        with open(binary_out, 'wb') as f:
            return subprocess.run(cmd, stdout=f, check=True)
    return subprocess.run(cmd, capture_output=True, text=False, check=True).stdout


def main():
    base_url = sys.argv[1]
    model = sys.argv[2] if len(sys.argv) > 2 else None

    subprocess.run([ADB, '-s', DEV, 'shell', 'am', 'force-stop', PKG], check=True)
    raw = subprocess.run([ADB, '-s', DEV, 'exec-out', 'run-as', PKG, 'cat', REL],
                         capture_output=True, check=True).stdout
    try:
        entries = decode(raw)
    except Exception as ex:
        snap = pathlib.Path('D:/tmp/settings.preferences_pb')
        print('device pb decode failed (%s) — 回退用快照 %s' % (ex, snap))
        entries = decode(snap.read_bytes())
    print('current:', [(e['key'], e.get('val')) for e in entries])

    def setk(key, val):
        for e in entries:
            if e['key'] == key:
                e['type'] = 'string'; e['val'] = val
                return
        entries.append({'key': key, 'type': 'string', 'val': val})

    setk('cloud_base_url', base_url)
    if model:
        setk('cloud_model', model)

    out = bytearray()
    seen = set()
    for e in entries:
        if e['key'] in seen:
            continue
        seen.add(e['key'])
        out += enc_entry(e['key'], e.get('type', 'string'), e['val'])

    tmp_host = 'D:/tmp/new_settings.pb'
    pathlib.Path(tmp_host).write_bytes(bytes(out))
    subprocess.run([ADB, '-s', DEV, 'push', tmp_host, '/data/local/tmp/new_settings.bin'], check=True)
    subprocess.run([ADB, '-s', DEV, 'shell', 'run-as', PKG, 'cp', '/data/local/tmp/new_settings.bin', REL], check=True)
    subprocess.run([ADB, '-s', DEV, 'shell', 'run-as', PKG, 'ls', '-la', 'files/datastore/'], check=True)

    back = subprocess.run([ADB, '-s', DEV, 'exec-out', 'run-as', PKG, 'cat', REL],
                          capture_output=True, check=True).stdout
    same = hashlib.sha256(back).hexdigest() == hashlib.sha256(bytes(out)).hexdigest()
    print('round-trip sha match:', same)
    print('new:', [(e['key'], e.get('val')) for e in decode(back)])


if __name__ == '__main__':
    main()
