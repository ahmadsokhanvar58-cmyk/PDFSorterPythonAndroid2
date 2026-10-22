#!/usr/bin/env python3

import re
import sys
import time
import subprocess
from pathlib import Path
from pypdf import PdfReader, PdfWriter

SRC = Path("/storage/emulated/0/Download/Dd")
OUT_MAIN = Path("/storage/emulated/0/Download/نمرات_مرتب_شده.pdf")
APP_OUT = Path.home() / "pdf-sorter" / "output" / "نمرات_مرتب_شده.pdf"

BAD_CODES = {95042683, 99990}


def clean_text(text):
    return re.sub(
        r'[\u200b\u200c\u200d\u200e\u200f\u202a-\u202e\u2060-\u2069\ufeff]',
        '',
        text or ''
    )


def get_pdf_files():
    if not SRC.exists():
        return []

    return sorted(
        [
            p for p in SRC.iterdir()
            if p.is_file() and p.suffix.lower() == ".pdf"
        ],
        key=lambda p: p.name
    )


def get_page_count(pdf):
    try:
        result = subprocess.run(
            ["pdfinfo", str(pdf)],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            encoding="utf-8",
            errors="ignore"
        )

        m = re.search(r"^Pages:\s*(\d+)", result.stdout, re.MULTILINE)
        if m:
            return int(m.group(1))
    except Exception:
        pass

    try:
        return len(PdfReader(str(pdf)).pages)
    except Exception:
        return 0


def normalize_digits(s):
    table = str.maketrans(
        "۰۱۲۳۴۵۶۷۸۹٠١٢٣٤٥٦٧٨٩",
        "01234567890123456789"
    )
    return s.translate(table)


def extract_page_info(page_text):
    text = clean_text(normalize_digits(page_text))

    # --------------------------------------------------------
    # کد درس:
    # نمونه:
    # -10011 تعليمات ديني
    # --------------------------------------------------------
    code = None

    for m in re.finditer(r'[-–—]\s*(\d{5,6})(?!\d)', text):
        value = int(m.group(1))
        if value not in BAD_CODES:
            code = value
            break

    # --------------------------------------------------------
    # کلاس:
    # نمونه:
    # دهم برق صنعتی 101
    # --------------------------------------------------------
    class_no = None

    patterns_class = [
        r'([0-9۰-۹]{3})\s+کالس\s*[:：]',
        r'([0-9۰-۹]{3})\s+کلاس\s*[:：]',
    ]
    for pattern in patterns_class:
        m = re.search(pattern, text, re.IGNORECASE | re.DOTALL)
        if m:
            class_no = int(m.group(1))
            break

    # --------------------------------------------------------
    # نوبت:
    # نوبت اول / نوبت دوم
    # --------------------------------------------------------
    term = None

    if re.search(r'نوبت\s*دوم', text):
        term = 2
    elif re.search(r'نوبت\s*اول', text):
        term = 1

    return code, class_no, term


def extract_all(pdf, page_count):
    result = subprocess.run(
        ["pdftotext", "-layout", str(pdf), "-"],
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True,
        encoding="utf-8",
        errors="ignore"
    )

    raw_pages = (result.stdout or "").split("\f")

    if len(raw_pages) > page_count:
        raw_pages = raw_pages[:page_count]

    if len(raw_pages) < page_count:
        raw_pages.extend([""] * (page_count - len(raw_pages)))

    return [
        extract_page_info(page_text)
        for page_text in raw_pages
    ]


start_time = time.time()

print("=" * 80)
print("⚡ مرتب‌ساز سریع PDF — کد درس + کلاس + نوبت")
print("=" * 80)
print(f"📂 ورودی: {SRC}")
print()

pdfs = get_pdf_files()

if not pdfs:
    print("❌ هیچ فایل PDF در Dd پیدا نشد.")
    sys.exit(1)

print(f"📂 تعداد فایل‌ها: {len(pdfs)}")

pages = []
original_index = 0
total_input_pages = 0

for file_index, pdf in enumerate(pdfs, 1):

    page_count = get_page_count(pdf)

    print(
        f"[{file_index}/{len(pdfs)}] "
        f"{pdf.name} → {page_count} صفحه"
    )

    if page_count <= 0:
        continue

    total_input_pages += page_count

    try:
        infos = extract_all(pdf, page_count)
    except Exception as e:
        print(f"   ⚠️ خطا: {e}")
        infos = [(None, None, None)] * page_count

    for page_number in range(page_count):

        code, class_no, term = infos[page_number]

        pages.append({
            "pdf": pdf,
            "page": page_number,
            "code": code,
            "class": class_no,
            "term": term,
            "index": original_index,
        })

        original_index += 1

    ok = sum(
        1 for code, cls, term in infos
        if code is not None and cls is not None and term is not None
    )

    print(
        f"   ✅ کامل: {ok} | "
        f"کل: {page_count}"
    )


print()
print("=" * 80)
print("📊 کنترل ورودی")
print("=" * 80)

print(f"تعداد فایل‌ها:     {len(pdfs)}")
print(f"تعداد صفحات ورودی: {total_input_pages}")
print(f"صفحات ثبت‌شده:     {len(pages)}")

if total_input_pages != len(pages):
    print("❌ تعداد صفحات برابر نیست.")
    sys.exit(1)


# ------------------------------------------------------------
# مرتب‌سازی نهایی:
# کد درس → کلاس → نوبت → ترتیب اصلی
# ------------------------------------------------------------

pages.sort(
    key=lambda x: (
        1 if x["code"] is None else 0,
        x["code"] if x["code"] is not None else 999999,

        1 if x["class"] is None else 0,
        x["class"] if x["class"] is not None else 999,

        1 if x["term"] is None else 0,
        x["term"] if x["term"] is not None else 99,

        x["index"],
    )
)


print()
print("=" * 80)
print("🔄 ترتیب نمونه")
print("=" * 80)

for i, x in enumerate(pages[:20], 1):
    print(
        f"{i:03d} | "
        f"درس {x['code']} | "
        f"کلاس {x['class']} | "
        f"نوبت {x['term']} | "
        f"{x['pdf'].name} | صفحه {x['page'] + 1}"
    )


print()
print("📝 ساخت PDF خروجی...")

OUT_MAIN.parent.mkdir(parents=True, exist_ok=True)
APP_OUT.parent.mkdir(parents=True, exist_ok=True)

writer = PdfWriter()
readers = {}

for number, item in enumerate(pages, 1):

    pdf = item["pdf"]

    if pdf not in readers:
        readers[pdf] = PdfReader(str(pdf))

    writer.add_page(
        readers[pdf].pages[item["page"]]
    )

    if number % 25 == 0 or number == len(pages):
        print(f"   {number}/{len(pages)}")


with open(OUT_MAIN, "wb") as f:
    writer.write(f)

# نسخه داخل برنامه
try:
    import shutil
    shutil.copy2(OUT_MAIN, APP_OUT)
except Exception:
    pass


# ------------------------------------------------------------
# کنترل نهایی
# ------------------------------------------------------------

try:
    final_pages = len(PdfReader(str(OUT_MAIN)).pages)
except Exception:
    final_pages = -1

print()
print("=" * 80)

if final_pages != total_input_pages:
    print("❌ کنترل نهایی ناموفق")
    print(f"ورودی: {total_input_pages}")
    print(f"خروجی: {final_pages}")
    sys.exit(1)

print("🎉 عملیات با موفقیت تمام شد")
print("=" * 80)
print(f"📄 صفحات ورودی: {total_input_pages}")
print(f"📄 صفحات خروجی: {final_pages}")
print(f"⏱ زمان اجرا: {time.time() - start_time:.1f} ثانیه")
print()
print("📥 خروجی:")
print(OUT_MAIN)
print()
print("✅ هیچ فایل ورودی در Dd حذف یا جابه‌جا نشد.")
