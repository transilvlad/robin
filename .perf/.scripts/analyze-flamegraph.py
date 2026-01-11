#!/usr/bin/env python3
"""Parse async-profiler flamegraph HTML and extract hotspot statistics."""

import re
import sys
from collections import defaultdict

def parse_flamegraph_html(html_file):
    with open(html_file, 'r') as f:
        content = f.read()

    # Extract constant pool
    cpool_match = re.search(r'const cpool = \[(.*?)\];', content, re.DOTALL)
    if not cpool_match:
        print("ERROR: Could not find constant pool")
        return None

    cpool_str = cpool_match.group(1)
    cpool_raw = []
    for line in cpool_str.split('\n'):
        line = line.strip()
        if line.startswith("'") and line.endswith(("',", "'")):
            # Extract string content
            string_val = line[1:-2] if line.endswith("',") else line[1:-1]
            cpool_raw.append(string_val)

    # Decompress the constant pool (async-profiler compression)
    cpool = [cpool_raw[0]]  # First entry is not compressed
    for i in range(1, len(cpool_raw)):
        if cpool_raw[i]:
            # First char encodes prefix length from previous entry
            prefix_len = ord(cpool_raw[i][0]) - 32
            suffix = cpool_raw[i][1:]
            decompressed = cpool[i-1][:prefix_len] + suffix
            cpool.append(decompressed)
        else:
            cpool.append('')

    print(f"Extracted {len(cpool)} frames from constant pool\n")

    # Extract all function calls (f, n, u calls) with their keys
    # f(key, level, left, width, ...)
    # n(key, width)
    # u(key, width, ...)
    all_frames = []

    # Extract f() calls
    for match in re.finditer(r'\bf\((\d+),\d+,\d+(?:,(\d+))?', content):
        key = int(match.group(1))
        width = int(match.group(2)) if match.group(2) else 1
        all_frames.append((key, width))

    # Extract n() calls
    for match in re.finditer(r'\bn\((\d+)(?:,(\d+))?', content):
        key = int(match.group(1))
        width = int(match.group(2)) if match.group(2) else 1
        all_frames.append((key, width))

    # Extract u() calls
    for match in re.finditer(r'\bu\((\d+)(?:,(\d+))?', content):
        key = int(match.group(1))
        width = int(match.group(2)) if match.group(2) else 1
        all_frames.append((key, width))

    # Count samples per frame
    frame_counts = defaultdict(int)
    for key, width in all_frames:
        if key < len(cpool):
            frame_counts[cpool[key]] += width

    return cpool, frame_counts

def find_hotspots(cpool, frame_counts):
    """Find Robin-specific hotspots."""

    robin_frames = {}
    for frame_name, count in frame_counts.items():
        if 'mimecast/robin' in frame_name or 'EmailDelivery' in frame_name or 'EmailReceipt' in frame_name:
            robin_frames[frame_name] = count

    return robin_frames

def main():
    if len(sys.argv) < 2:
        print("Usage: analyze-flamegraph.py <flamegraph.html>")
        sys.exit(1)

    html_file = sys.argv[1]
    result = parse_flamegraph_html(html_file)

    if not result:
        sys.exit(1)

    cpool, frame_counts = result

    # Calculate total samples
    total_samples = sum(frame_counts.values())
    print(f"Total samples: {total_samples:,}\n")

    # Find Robin-specific frames
    robin_frames = find_hotspots(cpool, frame_counts)

    if not robin_frames:
        print("No Robin-specific frames found.")
        print("\nTop 20 frames overall:")
        sorted_frames = sorted(frame_counts.items(), key=lambda x: x[1], reverse=True)[:20]
        for frame, count in sorted_frames:
            pct = (count / total_samples * 100) if total_samples > 0 else 0
            print(f"  {count:6,} ({pct:5.2f}%)  {frame}")
        return

    # Sort by sample count
    sorted_robin = sorted(robin_frames.items(), key=lambda x: x[1], reverse=True)

    print("=" * 100)
    print("ROBIN MTA HOTSPOTS (sorted by sample count)")
    print("=" * 100)
    print()

    robin_total = sum(robin_frames.values())

    for i, (frame, count) in enumerate(sorted_robin[:30], 1):
        pct_total = (count / total_samples * 100) if total_samples > 0 else 0
        pct_robin = (count / robin_total * 100) if robin_total > 0 else 0
        print(f"{i:2}. {count:6,} samples ({pct_total:5.2f}% total, {pct_robin:5.2f}% robin)")
        print(f"    {frame}")
        print()

    print("=" * 100)
    print(f"Total Robin samples: {robin_total:,} / {total_samples:,} ({robin_total/total_samples*100:.2f}%)")
    print("=" * 100)

    # Categorize hotspots
    categories = {
        'I/O Reading (LineInputStream, readLine, readMultiline)': [],
        'SMTP Protocol (ServerData, EmailReceipt)': [],
        'Storage (LocalStorageClient, StorageProcessor)': [],
        'LMTP Delivery (DovecotStorageProcessor, saveToLmtp)': [],
        'Email Parsing (EmailParser, MimeHeader)': [],
        'Configuration (Config, Properties)': [],
        'Other': []
    }

    for frame, count in sorted_robin:
        if any(x in frame for x in ['LineInputStream', 'readLine', 'readMultiline', 'SmtpFoundation.read']):
            categories['I/O Reading (LineInputStream, readLine, readMultiline)'].append((frame, count))
        elif any(x in frame for x in ['ServerData', 'EmailReceipt.process', 'EmailReceipt.run']):
            categories['SMTP Protocol (ServerData, EmailReceipt)'].append((frame, count))
        elif any(x in frame for x in ['LocalStorageClient', 'StorageProcessor', 'AVStorage', 'SpamStorage']):
            if 'Dovecot' not in frame:
                categories['Storage (LocalStorageClient, StorageProcessor)'].append((frame, count))
            else:
                categories['LMTP Delivery (DovecotStorageProcessor, saveToLmtp)'].append((frame, count))
        elif 'Dovecot' in frame:
            categories['LMTP Delivery (DovecotStorageProcessor, saveToLmtp)'].append((frame, count))
        elif any(x in frame for x in ['EmailParser', 'MimeHeader', 'EmailBuilder']):
            categories['Email Parsing (EmailParser, MimeHeader)'].append((frame, count))
        elif any(x in frame for x in ['Config', 'Properties', 'ServerConfig']):
            categories['Configuration (Config, Properties)'].append((frame, count))
        else:
            categories['Other'].append((frame, count))

    print("\n")
    print("=" * 100)
    print("HOTSPOT CATEGORIES")
    print("=" * 100)
    print()

    for category, frames in categories.items():
        if frames:
            cat_total = sum(count for _, count in frames)
            cat_pct_total = (cat_total / total_samples * 100) if total_samples > 0 else 0
            cat_pct_robin = (cat_total / robin_total * 100) if robin_total > 0 else 0
            print(f"{category}")
            print(f"  Total: {cat_total:,} samples ({cat_pct_total:5.2f}% of all, {cat_pct_robin:5.2f}% of robin)")
            print(f"  Top frames:")
            for frame, count in sorted(frames, key=lambda x: x[1], reverse=True)[:5]:
                pct = (count / cat_total * 100) if cat_total > 0 else 0
                print(f"    {count:6,} ({pct:5.1f}%)  {frame.split('/')[-1][:70]}")
            print()

if __name__ == '__main__':
    main()
