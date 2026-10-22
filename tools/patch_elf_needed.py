#!/usr/bin/env python3
"""
【已废弃 / 未采用】给 Android arm64 ELF 追加 DT_NEEDED 依赖（无需重编译）。

⚠️ 结论：**这条路没走通，别用。**
   真机上实测：libllamasrv.so 的 .dynstr 实际只剩 1 字节余量
   （需要 16 字节放 "libc++_shared.so"）→ 无法追加，报错：
       ".dynstr 空间不足（3027 < 3043）"
   而 .dynstr 段头声明的 STRSZ(8108) 与实际占用(3027)不符，
   这正是原来以为"有大量预留空间"的来源 —— 实际不可用。

✅ 实际采用的修法（见 commit 27a1fea）：
   1. libOpenCL.so 打包进 APK 的 jniLibs/arm64-v8a（随 nativeLibraryDir 一起）；
   2. LD_LIBRARY_PATH 只含 nativeLibraryDir，不再指向 /vendor/lib64
      （系统的 /vendor/lib64/libOpenCL.so NEEDED 私有 libc++.so，
        独立 exec 时 linker namespace 不暴露 → __hash_memory 解析失败）；
   3. 启动失败时把原因显示到界面，不再静默。

保留本脚本仅作排查记录（它能打印 ELF 的 NEEDED/段布局，
   定位"到底缺哪个符号"时有用）。

以下为当时的尝试思路与实现：
---------------------------------------------------------------------------
用途：修复 libllamasrv.so 的链接符号缺失 ——
  libOpenCL.so 依赖 liblog.so + libc++.so，但 libllamasrv.so 自己的 NEEDED 里没有，
  ld.so 拉入 liblog.so 后解析不到 libc++ 的 __hash_memory → 整个二进制无法启动。
  修法：把 libc++_shared.so / liblog.so 显式写进 NEEDED，让依赖链闭合。

原理：
  - .dynstr 段有大量预留空间（STRSZ 8108 vs 实际 3027），可安全追加字符串。
  - .dynamic 段之后到段末尾有空闲空间，可容纳新增的 DT_NEEDED 条目。
  - 需要同步更新：.dynamic section header size、PT_DYNAMIC 的 filesz/size、
    以及承载 .dynamic 的 LOAD 段的 filesz（若扩展超出原段界）。
  - DT_NEEDED 的 val 是 .dynstr 内的偏移；所有 NEEDED 排序不影响正确性。
"""
import struct
import sys


def patch(path_in, path_out, extra_libs):
    d = bytearray(open(path_in, 'rb').read())

    # ---- ELF64 header ----
    e_shoff = struct.unpack_from('<Q', d, 0x28)[0]
    e_phoff = struct.unpack_from('<Q', d, 0x20)[0]
    e_shentsize = struct.unpack_from('<H', d, 0x3a)[0]
    e_shnum = struct.unpack_from('<H', d, 0x3c)[0]
    e_shstrndx = struct.unpack_from('<H', d, 0x3e)[0]

    def sec(i):
        o = e_shoff + i * e_shentsize
        v = struct.unpack_from('<IIQQQQIIQQ', d, o)
        return {'name': v[0], 'type': v[1], 'flags': v[2], 'addr': v[3], 'off': v[4],
                'size': v[5], 'link': v[6], 'info': v[7], 'align': v[8], 'entsize': v[9], 'hdr': o}

    stroff = sec(e_shstrndx)['off']
    shstrtab = d[stroff:stroff + sec(e_shstrndx)['size']]

    def shname(n):
        e = shstrtab.index(b'\x00', n)
        return shstrtab[n:e].decode()

    dynstr = dyn = None
    for i in range(e_shnum):
        s = sec(i)
        nm = shname(s['name'])
        if nm == '.dynstr':
            dynstr = s
        elif nm == '.dynamic':
            dyn = s

    if dynstr is None or dyn is None:
        raise SystemExit('找不到 .dynstr / .dynamic')

    dstr_off = dynstr['off']
    dstr_size = dynstr['size']
    dstr = d[dstr_off:dstr_off + dstr_size]

    # ---- 现有 NEEDED ----
    existing = []
    dt_null_off = None
    for i in range(dyn['off'], dyn['off'] + dyn['size'], 16):
        tag, val = struct.unpack_from('<qQ', d, i)
        if tag == 0:
            dt_null_off = i
            break
        if tag == 1:
            e = dstr.index(b'\x00', val)
            existing.append(dstr[val:e].decode())
    if dt_null_off is None:
        raise SystemExit('.dynamic 里没有 DT_NULL')

    to_add = [lib for lib in extra_libs if lib not in existing]
    if not to_add:
        print('无需修改：已含 %s' % ', '.join(extra_libs))
        shutil_copy(path_in, path_out)
        return

    # ---- 1) 追加字符串到 .dynstr ----
    # .dynstr 后面紧跟别的 section，必须确认有空间；
    # 否则把 .dynstr 扩大会破坏后续 section —— 这里改为写入 .dynstr 预留的 STRSZ 空间（段内）。
    end = len(dstr)
    while end > 0 and dstr[end - 1] == 0:
        end -= 1
    new_offsets = {}
    for lib in to_add:
        b = lib.encode() + b'\x00'
        # 必须落在 .dynstr 段内（section size 之内）
        if end + len(b) > dstr_size:
            raise SystemExit('.dynstr 空间不足（%d < %d）' % (dstr_size, end + len(b)))
        d[dstr_off + end:dstr_off + end + len(b)] = b
        new_offsets[lib] = end
        end += len(b)

    # ---- 2) 扩展 .dynamic：把 DT_NULL 之前腾出空间，追加 NEEDED ----
    # .dynamic 段在文件中的可用空间：找到承载它的 LOAD 段的末尾
    e_phentsize = struct.unpack_from('<H', d, 0x36)[0]
    e_phnum = struct.unpack_from('<H', d, 0x38)[0]
    load_seg = None
    dyn_ph = None
    for i in range(e_phnum):
        o = e_phoff + i * e_phentsize
        typ, flags, off, vaddr, paddr, filesz, memsz, align = struct.unpack_from('<IIQQQQQQ', d, o)
        if typ == 2:  # PT_DYNAMIC
            dyn_ph = {'off': off, 'filesz': filesz, 'memsz': memsz, 'hdr': o}
        if typ == 1:  # PT_LOAD
            if off <= dyn['off'] < off + filesz:
                load_seg = {'off': off, 'filesz': filesz, 'memsz': memsz, 'hdr': o}
    if dyn_ph is None or load_seg is None:
        raise SystemExit('找不到 PT_DYNAMIC / PT_LOAD')

    seg_end = load_seg['off'] + load_seg['filesz']
    need_bytes = 16 * len(to_add)          # 每个 DT_NEEDED 16 字节
    new_dyn_size = dyn['size'] + need_bytes
    new_dyn_end = dyn['off'] + new_dyn_size
    if new_dyn_end > seg_end:
        raise SystemExit('LOAD 段空间不足')

    # 把原来的 DT_NULL 及之后的条目整体后移 need_bytes
    tail = bytes(d[dt_null_off:dyn['off'] + dyn['size']])
    d[dt_null_off + need_bytes:dt_null_off + need_bytes + len(tail)] = tail
    # 清空刚腾出的区域（原来 DT_NULL 位置）
    d[dt_null_off:dt_null_off + need_bytes] = b'\x00' * need_bytes
    # 在 DT_NULL 之前写入新的 NEEDED
    for idx, lib in enumerate(to_add):
        struct.pack_into('<qQ', d, dt_null_off + idx * 16, 1, new_offsets[lib])

    # ---- 3) 更新 section header (.dynamic size) ----
    struct.pack_into('<Q', d, dyn['hdr'] + 32, new_dyn_size)

    # ---- 4) 更新 PT_DYNAMIC filesz/memsz ----
    struct.pack_into('<Q', d, dyn_ph['hdr'] + 32, new_dyn_size)   # p_filesz
    struct.pack_into('<Q', d, dyn_ph['hdr'] + 40, new_dyn_size)   # p_memsz

    # ---- 5) 若扩展超出 LOAD 段边界，扩展 LOAD 段（一般不会）----
    if new_dyn_end > seg_end:
        grow = new_dyn_end - seg_end
        struct.pack_into('<Q', d, load_seg['hdr'] + 32, load_seg['filesz'] + grow)
        struct.pack_into('<Q', d, load_seg['hdr'] + 40, load_seg['memsz'] + grow)

    open(path_out, 'wb').write(d)
    print('已添加 NEEDED: %s' % ', '.join(to_add))
    print('原有 NEEDED: %s' % ', '.join(existing))
    print('输出: %s' % path_out)


def shutil_copy(a, b):
    import shutil
    shutil.copyfile(a, b)


if __name__ == '__main__':
    patch(sys.argv[1], sys.argv[2], sys.argv[3:])
