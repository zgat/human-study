#!/usr/bin/env python3
"""Fetch a single official ZIP member by HTTP ranges without downloading the ZIP.

Used to unblock skin-coordinate review while the complete source archive downloads.
The downloaded member must still be checked against the finished ZIP before release.
"""
import argparse
import struct
import urllib.request
import zlib
from pathlib import Path

URL = 'https://dbarchive.biosciencedbc.jp/data/bodyparts3d/20130619/isa_BP3D_4.0_obj_99.zip'


def get_range(value):
    request = urllib.request.Request(URL, headers={'Range': f'bytes={value}'})
    with urllib.request.urlopen(request, timeout=90) as response:
        if response.status != 206:
            raise RuntimeError(f'Server ignored HTTP range: {response.status}')
        return response.read()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('member')
    args = parser.parse_args()
    tail = get_range('-524288')
    i = tail.find(b'PK\x01\x02')
    while tail[i:i+4] == b'PK\x01\x02':
        h = struct.unpack_from('<4s6H3I5H2I', tail, i)
        compressed, nlen, xlen, clen, offset = h[8], h[10], h[11], h[12], h[-1]
        name = tail[i+46:i+46+nlen].decode()
        if Path(name).name == args.member:
            local = get_range(f'{offset}-{offset+29}')
            lh = struct.unpack('<4s5H3I2H', local)
            start = offset + 30 + lh[9] + lh[10]
            data = get_range(f'{start}-{start+compressed-1}')
            content = zlib.decompress(data, -15) if lh[3] == 8 else data
            if zlib.crc32(content) != h[7]:
                raise RuntimeError('Member CRC mismatch')
            output = Path(__file__).resolve().parent / 'source' / args.member
            output.write_bytes(content)
            print(output, len(content), 'CRC valid', flush=True)
            return
        i += 46 + nlen + xlen + clen
    raise ValueError(f'Member not found: {args.member}')


if __name__ == '__main__':
    main()
