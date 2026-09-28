# -*- coding: utf-8 -*-
"""Decode/encode AndroidX DataStore Preferences protobuf (for emulator settings injection)."""
import sys


def read_varint(b, i):
    r = 0
    s = 0
    while True:
        x = b[i]
        i += 1
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
            yield f, b[i:i + ln]
            i += ln
        elif w == 0:
            v, i = read_varint(b, i)
            yield f, v
        else:
            raise Exception('wire %d' % w)


def enc_varint(n):
    out = bytearray()
    while True:
        x = n & 0x7F
        n >>= 7
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


def decode(path):
    data = open(path, 'rb').read()
    out = []
    for f, payload in parse(data):
        if f == 1:
            entry = {}
            for f2, p2 in parse(payload):
                if f2 == 1:
                    entry['key'] = p2.decode('utf-8')
                elif f2 == 2:
                    for f3, p3 in parse(p2):
                        if f3 == 5:
                            entry['string'] = p3.decode('utf-8')
                        elif f3 == 1:
                            entry['bool'] = p3
                        elif f3 == 3:
                            entry['int'] = p3
                        elif f3 == 4:
                            entry['long'] = p3
            out.append(entry)
    return out


def encode(items):
    """items: dict key -> str value (string pref)"""
    buf = bytearray()
    for k, v in items.items():
        entry = enc_str(1, k) + fld(2, 2, enc_str(5, v))
        buf += fld(1, 2, enc_varint(len(entry)) + entry)
    return bytes(buf)


if __name__ == '__main__':
    if sys.argv[1] == 'decode':
        for e in decode(sys.argv[2]):
            print(e)
    elif sys.argv[1] == 'encode':
        # encode key=value pairs
        items = {}
        for kv in sys.argv[3:]:
            k, v = kv.split('=', 1)
            items[k] = v
        open(sys.argv[2], 'wb').write(encode(items))
        print('written', sys.argv[2])
