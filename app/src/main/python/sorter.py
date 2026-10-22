"""Python sorting engine embedded in the Android APK.

This ports the reference script's ordering and extraction rules. The original
class_list_fast_FINAL.py is preserved separately in the project root.
NOTE: pypdf layout extraction is not byte-for-byte identical to Poppler's
`pdftotext -layout`; compare results against the reference on representative PDFs.
"""
import re, os
from pathlib import Path
from pypdf import PdfReader, PdfWriter

BAD_CODES = {95042683, 99990}

def clean_text(text):
    return re.sub(r'[\u200b\u200c\u200d\u200e\u200f\u202a-\u202e\u2060-\u2069\ufeff]', '', text or '')

def normalize_digits(s):
    return s.translate(str.maketrans('۰۱۲۳۴۵۶۷۸۹٠١٢٣٤٥٦٧٨٩','01234567890123456789'))

def extract_page_info(page_text):
    text = clean_text(normalize_digits(page_text))
    code = None
    for m in re.finditer(r'[-–—]\s*(\d{5,6})(?!\d)', text):
        value = int(m.group(1))
        if value not in BAD_CODES:
            code = value; break
    class_no = None
    for pattern in [r'([0-9۰-۹]{3})\s+کالس\s*[:：]', r'([0-9۰-۹]{3})\s+کلاس\s*[:：]']:
        m = re.search(pattern, text, re.IGNORECASE | re.DOTALL)
        if m: class_no = int(normalize_digits(m.group(1))); break
    term = None
    if re.search(r'نوبت\s*دوم', text): term = 2
    elif re.search(r'نوبت\s*اول', text): term = 1
    return code, class_no, term

def sort_pdfs(paths, output_path):
    pages=[]; original_index=0; total=0
    readers={}
    for path in paths:
        reader=PdfReader(path); readers[path]=reader; total += len(reader.pages)
        for pageno, page in enumerate(reader.pages):
            try: txt=page.extract_text(extraction_mode='layout') or ''
            except TypeError: txt=page.extract_text() or ''
            code, cls, term=extract_page_info(txt)
            pages.append({'pdf':path,'page':pageno,'code':code,'class':cls,'term':term,'index':original_index})
            original_index+=1
    pages.sort(key=lambda x:(1 if x['code'] is None else 0, x['code'] if x['code'] is not None else 999999,
        1 if x['class'] is None else 0, x['class'] if x['class'] is not None else 999,
        1 if x['term'] is None else 0, x['term'] if x['term'] is not None else 99, x['index']))
    writer=PdfWriter()
    for item in pages: writer.add_page(readers[item['pdf']].pages[item['page']])
    os.makedirs(os.path.dirname(output_path),exist_ok=True)
    tmp=output_path+'.tmp'
    with open(tmp,'wb') as f: writer.write(f)
    check=PdfReader(tmp)
    if len(check.pages)!=total:
        os.remove(tmp); raise RuntimeError(f'تعداد صفحات خروجی نادرست است: {len(check.pages)} از {total}')
    os.replace(tmp,output_path)
    missing=sum(1 for x in pages if x['code'] is None or x['class'] is None or x['term'] is None)
    return f'مرتب‌سازی تمام شد. صفحات: {total}\nصفحات با اطلاعات ناقص: {missing}'
