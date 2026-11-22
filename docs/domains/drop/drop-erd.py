"""Draw docs/domains/drop/drop-erd.svg, the drop module ERD.

The tables below are copied from the schema after every migration (SHOW CREATE TABLE on MySQL 8.4, 2026-11-22,
up to V20261122_1401). When a drop migration changes a column, change TABLES and run:

    python3 docs/domains/drop/drop-erd.py

No third-party packages. The output is plain SVG so diffs stay reviewable.
"""
from pathlib import Path
from xml.sax.saxutils import escape

OUT = Path(__file__).with_name("drop-erd.svg")

# (name, type, keys, ref). keys: PK FK UK LOGIC (reference without a physical FK) OLD (frozen, to be dropped).
TABLES = {
    "drops": ("#2B6CB0", [
        ("id", "bigint", "PK", ""),
        ("concert_id", "bigint", "FK", "concerts"),
        ("title", "varchar", "", ""),
        ("description", "text", "", ""),
        ("image_url", "varchar", "", ""),
        ("opens_at", "datetime", "", ""),
        ("closes_at", "datetime", "", ""),
        ("status", "varchar", "", ""),
        ("created_at", "datetime", "", ""),
    ]),
    "drop_variants": ("#16999A", [
        ("id", "bigint", "PK", ""),
        ("drop_id", "bigint", "FK", "drops"),
        ("sku_code", "varchar", "", ""),
        ("option_name", "varchar", "", ""),
        ("price_amount", "bigint", "", ""),
        ("is_active", "boolean", "", ""),
    ]),
    "drop_inventories": ("#3B7D3B", [
        ("variant_id", "bigint", "PK FK", "drop_variants"),
        ("total_quantity", "int", "", ""),
        ("bucket_count", "tinyint", "", ""),
        ("available_quantity", "int", "OLD", ""),
        ("reserved_quantity", "int", "OLD", ""),
        ("sold_quantity", "int", "OLD", ""),
        ("version", "bigint", "OLD", ""),
    ]),
    "drop_inventory_buckets": ("#5E8C2F", [
        ("variant_id", "bigint", "PK FK", "drop_inventories"),
        ("bucket_no", "tinyint", "PK", ""),
        ("total_quantity", "int", "", ""),
        ("available_quantity", "int", "", ""),
        ("reserved_quantity", "int", "", ""),
        ("sold_quantity", "int", "", ""),
        ("version", "bigint", "", ""),
    ]),
    "drop_reservations": ("#4C4BA0", [
        ("id", "bigint", "PK", ""),
        ("drop_id", "bigint", "FK", "drops"),
        ("variant_id", "bigint", "FK", "drop_variants"),
        ("bucket_no", "tinyint", "LOGIC", "버킷"),
        ("user_id", "bigint", "FK", "users"),
        ("order_id", "bigint", "UK FK", "orders"),
        ("request_key", "varchar", "", ""),
        ("status", "varchar", "", ""),
        ("expires_at", "datetime", "", ""),
        ("current_payment_id", "bigint?", "LOGIC", "payments"),
        ("created_at", "datetime", "", ""),
        ("updated_at", "datetime", "", ""),
    ]),
    "drop_queue_entries": ("#B5476B", [
        ("id", "bigint", "PK", ""),
        ("drop_id", "bigint", "FK", "drops"),
        ("variant_id", "bigint", "FK", "drop_variants"),
        ("user_id", "bigint", "FK", "users"),
        ("status", "varchar", "", ""),
        ("entered_at", "datetime", "", ""),
        ("last_seen_at", "datetime", "", ""),
        ("admitted_at", "datetime?", "", ""),
        ("admitted_until", "datetime?", "", ""),
        ("consumed_at", "datetime?", "", ""),
        ("active_user_id", "bigint?", "", ""),
    ]),
    "drop_purchase_slots": ("#C4861A", [
        ("drop_id", "bigint", "PK FK", "drops"),
        ("user_id", "bigint", "PK FK", "users"),
        ("active_reservation_id", "bigint?", "FK", "drop_reservations"),
        ("updated_at", "datetime", "", ""),
    ]),
    "drop_queue_gates": ("#6B7280", [
        ("variant_id", "bigint", "PK FK", "drop_variants"),
    ]),
}

COL_X = [40, 900, 1760]
WIDTH = 700
GAP = 160
ROW_Y = [200, 754, 1428]
HEADER, SUBHEADER, ROW = 64, 40, 40
POS = {
    "drops": (0, 0), "drop_variants": (1, 0), "drop_inventories": (2, 0),
    "drop_queue_entries": (0, 1), "drop_reservations": (1, 1), "drop_inventory_buckets": (2, 1),
    "drop_purchase_slots": (0, 2), "drop_queue_gates": (1, 2),
}
BADGE = {  # text, fill, stroke, color
    "PK": ("PK", "#DCEFFB", "#9CCBEA", "#1F5F8B"),
    "FK": ("FK", "#EEE5FA", "#C9B3EC", "#5B3A96"),
    "UK": ("UK", "#E2F4E4", "#A9D8AF", "#2E7D3A"),
    "LOGIC": ("논리", "#FFF1DC", "#F0C98A", "#8A5A12"),
    "OLD": ("폐기 예정", "#EEEEEE", "#C8C8C8", "#666666"),
}
SANS = "'Noto Sans KR', 'Apple SD Gothic Neo', 'Malgun Gothic', sans-serif"
MONO = "'JetBrains Mono', 'D2Coding', Menlo, Consolas, monospace"
INK = "#1B2540"

out = []


def text(x, y, s, size=22, weight="normal", fill=INK, family=MONO, anchor="start", extra=""):
    out.append(f'<text x="{x}" y="{y}" font-family="{family}" font-size="{size}" font-weight="{weight}" '
               f'fill="{fill}" text-anchor="{anchor}" {extra}>{escape(s)}</text>')


def badge(x, y, key):
    label, fill, stroke, color = BADGE[key]
    w = 22 + 13 * len(label) if label.isascii() else 24 + 19 * len(label.replace(" ", "")) + 6 * label.count(" ")
    out.append(f'<rect x="{x}" y="{y - 21}" width="{w}" height="30" rx="7" fill="{fill}" stroke="{stroke}" stroke-width="1.5"/>')
    text(x + w / 2, y, label, 16, "bold", color, SANS if not label.isascii() else MONO, "middle")
    return w + 8


def box(name):
    color, cols = TABLES[name]
    ci, ri = POS[name]
    x, y = COL_X[ci], ROW_Y[ri]
    h = HEADER + SUBHEADER + ROW * len(cols)
    out.append(f'<rect x="{x + 3}" y="{y + 4}" width="{WIDTH}" height="{h}" rx="12" fill="#000" opacity="0.06"/>')
    out.append(f'<rect x="{x}" y="{y}" width="{WIDTH}" height="{h}" rx="12" fill="#FFFFFF" stroke="#C9CFDB" stroke-width="2"/>')
    out.append(f'<path d="M{x} {y + HEADER} V{y + 12} Q{x} {y} {x + 12} {y} H{x + WIDTH - 12} Q{x + WIDTH} {y} {x + WIDTH} {y + 12} V{y + HEADER} Z" fill="{color}"/>')
    text(x + 28, y + 43, name.upper(), 30, "bold", "#FFFFFF", SANS)
    out.append(f'<rect x="{x + 1}" y="{y + HEADER}" width="{WIDTH - 2}" height="{SUBHEADER}" fill="#E8EBF2"/>')
    for label, dx in (("FIELD", 28), ("TYPE", 310), ("KEY", 430)):
        text(x + dx, y + HEADER + 27, label, 18, "bold", "#2A3550", SANS)
    rows = {}
    for i, (field, typ, keys, ref) in enumerate(cols):
        ry = y + HEADER + SUBHEADER + ROW * i
        rows[field] = ry + ROW / 2
        if i:
            out.append(f'<line x1="{x + 1}" y1="{ry}" x2="{x + WIDTH - 1}" y2="{ry}" stroke="#E3E6EC" stroke-width="1.5"/>')
        old = "OLD" in keys.split()
        shade = "#8A8F99" if old else INK
        text(x + 28, ry + 28, field, 21, fill=shade, extra='text-decoration="line-through"' if old else "")
        text(x + 310, ry + 28, typ, 21, fill="#3C465E" if not old else shade)
        bx = x + 430
        for key in keys.split():
            bx += badge(bx, ry + 28, key)
        if ref:
            text(bx + 2, ry + 27, "→ " + ref, 14, fill="#5B6478")
    return x, y, h, rows


def edge(points, label=None, at=None, start=None, end=None, dashed=False):
    d = "M" + " L".join(f"{px} {py}" for px, py in points)
    dash = ' stroke-dasharray="10 8"' if dashed else ""
    out.append(f'<path d="{d}" fill="none" stroke="{INK}" stroke-width="3"{dash} marker-end="url(#arrow)"/>')
    if label:
        lx, ly = at
        w = 24 + 15 * len(label)
        out.append(f'<rect x="{lx - w / 2}" y="{ly - 20}" width="{w}" height="40" rx="12" fill="#EEF1F6" stroke="{INK}" stroke-width="2.5"/>')
        text(lx, ly + 9, label, 24, "bold", INK, SANS, "middle")
    for spec in (start, end):
        if spec:
            (cx, cy), card = spec
            text(cx, cy, card, 24, "bold", INK, SANS, "middle")


def main():
    W, H = 2500, 1940
    out.append(f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">')
    out.append(f'<defs><marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="5" markerHeight="5" orient="auto-start-reverse">'
               f'<path d="M0 0 L10 5 L0 10 Z" fill="{INK}"/></marker></defs>')
    out.append(f'<rect width="{W}" height="{H}" fill="#FFFFFF"/>')
    text(W / 2, 92, "DROP COMMERCE", 76, "bold", "#14213D", SANS, "middle")
    text(W / 2, 150, "Entity Relationship Diagram (V20261122_1401 기준)", 34, "normal", INK, SANS, "middle")

    b = {name: box(name) for name in TABLES}

    def right(n): return b[n][0] + WIDTH
    def left(n): return b[n][0]
    def top(n): return b[n][1]
    def bottom(n): return b[n][1] + b[n][2]
    def row(n, f): return b[n][3][f]

    def mid(a, c): return (right(a) + left(c)) / 2

    # drops 1 - N drop_variants
    y = row("drop_variants", "drop_id")
    edge([(right("drops"), y), (left("drop_variants") - 4, y)], "has", (mid("drops", "drop_variants"), y),
         ((right("drops") + 16, y + 36), "1"), ((left("drop_variants") - 20, y + 36), "N"))
    # drop_variants 1 - 1 drop_inventories
    y = row("drop_inventories", "variant_id")
    edge([(right("drop_variants"), y), (left("drop_inventories") - 4, y)], "owns", (mid("drop_variants", "drop_inventories"), y),
         ((right("drop_variants") + 16, y + 36), "1"), ((left("drop_inventories") - 20, y + 36), "1"))
    # drop_inventories 1 - K buckets
    x = left("drop_inventories") + 350
    edge([(x, bottom("drop_inventories")), (x, top("drop_inventory_buckets") - 4)], "splits into",
         (x, (bottom("drop_inventories") + top("drop_inventory_buckets")) / 2),
         ((x + 22, bottom("drop_inventories") + 30), "1"), ((x + 40, top("drop_inventory_buckets") - 12), "1..16"))
    # drop_variants 1 - N drop_reservations
    x = left("drop_variants") + 540
    edge([(x, bottom("drop_variants")), (x, top("drop_reservations") - 4)], "reserved for",
         (x, (bottom("drop_variants") + top("drop_reservations")) / 2),
         ((x + 22, bottom("drop_variants") + 30), "1"), ((x + 34, top("drop_reservations") - 12), "0..N"))
    # drop_reservations.bucket_no ..> bucket (logical, no FK)
    y = row("drop_reservations", "bucket_no")
    edge([(right("drop_reservations"), y), (left("drop_inventory_buckets") - 4, y)], "held in",
         (mid("drop_reservations", "drop_inventory_buckets"), y),
         ((right("drop_reservations") + 34, y + 40), "0..N"), ((left("drop_inventory_buckets") - 20, y + 36), "1"),
         dashed=True)
    # drop_variants 1 - N drop_queue_entries (SKU queue)
    gap_y = (bottom("drops") + top("drop_queue_entries")) / 2 + 10
    x0 = left("drop_variants") + 140
    edge([(x0, bottom("drop_variants")), (x0, gap_y), (420, gap_y), (420, top("drop_queue_entries") - 4)],
         "waits for", (640, gap_y), ((x0 + 22, bottom("drop_variants") + 30), "1"), ((456, top("drop_queue_entries") - 12), "0..N"))
    # drop_purchase_slots.active_reservation_id 0..1 -> drop_reservations
    y0 = row("drop_purchase_slots", "active_reservation_id")
    y1 = row("drop_reservations", "id")
    gx = mid("drop_purchase_slots", "drop_reservations")
    edge([(right("drop_purchase_slots"), y0), (gx, y0), (gx, y1), (left("drop_reservations") - 4, y1)], "active",
         (gx, (y0 + y1) / 2 + 120), ((right("drop_purchase_slots") + 30, y0 + 36), "0..1"),
         ((left("drop_reservations") - 20, y1 + 36), "1"))

    # notes
    nx, ny, nw = COL_X[2], ROW_Y[2], WIDTH
    out.append(f'<rect x="{nx}" y="{ny}" width="{nw}" height="420" rx="12" fill="#F8F9FC" stroke="#B9C0CE" stroke-width="2" stroke-dasharray="10 7"/>')
    notes = [
        ("재고 (ADR-0005)", True),
        ("SKU 재고 = 버킷 합", False),
        ("버킷마다 total = available + reserved + sold, 모두 0 이상", False),
        ("bucket_count는 1~16. 생성 때 min(K, 총재고)", False),
        ("drop_reservations.bucket_no = 점유한 버킷", False),
        ("  물리 FK 없음 (교착 방지). 하네스 SQL로 검사", False),
        ("drop_inventories의 수량 컬럼은 멈춰 있고 읽지 않는다", False),
        ("그 밖의 제약", True),
        ("drops: opens_at < closes_at", False),
        ("UNIQUE drop_variants (drop_id, sku_code)", False),
        ("UNIQUE drop_reservations (drop_id, user_id, request_key)", False),
        ("UNIQUE drop_queue_entries (drop_id, active_user_id)", False),
        ("active_user_id = WAITING, ADMITTED일 때의 user_id (생성 컬럼)", False),
        ("타입 끝의 ?는 NULL 허용. →는 참조 대상 테이블", False),
    ]
    ly = ny + 40
    for line, head in notes:
        if head and ly > ny + 40:
            ly += 8
        text(nx + 28, ly, line, 21 if head else 17, "bold" if head else "normal", INK, SANS)
        ly += 30 if head else 25

    # legend
    lx, ly = 40, H - 60
    for key, desc in (("PK", "Primary key"), ("FK", "Foreign key"), ("UK", "Unique key"),
                      ("LOGIC", "물리 FK 없는 참조"), ("OLD", "폐기 예정, 후속 축소 migration에서 삭제")):
        lx += badge(lx, ly, key) + 6
        text(lx, ly, desc, 22, "normal", INK, SANS)
        lx += 24 + 13 * len(desc) if desc.isascii() else 24 + 21 * len(desc)
    out.append("</svg>")
    OUT.write_text("\n".join(out) + "\n", encoding="utf-8")
    print(f"wrote {OUT}")


if __name__ == "__main__":
    main()
