"""
RBTV+ Live Match Hybrid Scraper
Strategi:
1. Panggil API RBTV+ langsung (Python requests) → dapat semua match data
2. Gunakan Playwright dengan stealth → buka situs, ambil visible matchId dari DOM
3. Tampilkan hanya match yang ada di keduanya (intersection)
4. Fallback: jika Playwright gagal, gunakan filter API (ONGOING + 60 mnt)
"""
import asyncio
import json
import os
import re
import sys
import time
import hashlib
import requests
from datetime import datetime, timezone, timedelta
from playwright.async_api import async_playwright

WIB = timezone(timedelta(hours=7))
GIST_ID = os.environ.get("GIST_ID", "bea11b35c556c7acc73fcd2ef3014c7d")
GIST_TOKEN = os.environ.get("GIST_TOKEN", "")

# Domain situs RBTV+ — diambil dari GitHub Secrets agar tidak terekspos di source code
RBTV_SITE_URL = os.environ.get("RBTV_SITE_URL", "https://www.rbtvplus18.hair/id/")
RBTV_FALLBACK_URL = os.environ.get("RBTV_FALLBACK_URL", "https://www.rbtvplus18.hair/id/")

# =========================================================
# BAGIAN 1: Protobuf helpers & API calls (Pure Python)
# =========================================================
def md5(s):
    return hashlib.md5(s.encode('utf-8')).hexdigest()

def read_varint(data, idx):
    val, shift = 0, 0
    while idx < len(data):
        b = data[idx] & 0xFF; idx += 1
        val |= (b & 0x7F) << shift
        if (b & 0x80) == 0: break
        shift += 7
    return val, idx

def skip_field(data, idx, wire):
    if wire == 0: _, idx = read_varint(data, idx)
    elif wire == 1: idx += 8
    elif wire == 2:
        l, idx = read_varint(data, idx); idx += l
    elif wire == 5: idx += 4
    return idx

def read_string(data, idx):
    l, idx = read_varint(data, idx)
    return data[idx:idx+l].decode('utf-8', errors='ignore'), idx + l

SPORT_NAMES = {
    1:"Sepak Bola", 2:"Basket", 3:"Tenis", 4:"Bisbol", 6:"Kriket",
    7:"Motorsport", 8:"Rugby", 9:"Am.Football", 10:"AussieRules",
    12:"Bulutangkis", 13:"Voli", 14:"Fighting", 15:"Balap Sepeda",
    16:"Handball", 90:"Golf"
}
STATUS_NAMES = {
    0:"Coming", 1:"Live", 100:"FTB Live", 101:"Babak 1", 102:"HT",
    103:"Babak 2", 104:"Extra Time", 105:"Penalti",
    200:"BSK Live", 201:"Q1", 202:"Q2", 203:"Q3", 204:"Q4",
    300:"TNS Live", 400:"Baseball Live", 600:"Cricket Live",
    700:"Motorsport Live", 800:"Rugby Live", 900:"AmFootball Live",
    1000:"AussieRules Live", 1200:"BMT Live", 1300:"Voli Live",
    1400:"Fighting Live", 1500:"Cycling Live", 1600:"Handball Live",
    9000:"Other Live"
}
ONGOING = {1,100,101,102,103,104,105,200,201,202,203,204,211,212,213,214,
           300,400,600,700,800,900,1000,1100,1200,1300,1400,1500,1600,9000}

def parse_match_basic(mdata):
    idx = 0; match_id = match_status = match_time = sport_type = 0
    teams = []; league_name = None
    while idx < len(mdata):
        try:
            key, idx = read_varint(mdata, idx)
            tag, wire = key >> 3, key & 7
            if tag == 1 and wire == 0: match_id, idx = read_varint(mdata, idx)
            elif tag == 2 and wire == 0: sport_type, idx = read_varint(mdata, idx)
            elif tag == 3 and wire == 0: match_time, idx = read_varint(mdata, idx)
            elif tag == 4 and wire == 0: match_status, idx = read_varint(mdata, idx)
            elif tag == 10 and wire == 2:
                length, idx = read_varint(mdata, idx)
                ldata = mdata[idx:idx+length]; idx += length
                li = 0
                while li < len(ldata):
                    lk, li = read_varint(ldata, li); lt, lw = lk >> 3, lk & 7
                    if lt == 3 and lw == 2:
                        l2, li = read_varint(ldata, li); sub = ldata[li:li+l2]; li += l2
                        si = 0
                        while si < len(sub):
                            sk, si = read_varint(sub, si); st, sw = sk >> 3, sk & 7
                            if st == 2 and sw == 2: league_name, si = read_string(sub, si); break
                            else: si = skip_field(sub, si, sw)
                        break
                    else: li = skip_field(ldata, li, lw)
            elif tag == 30 and wire == 2:
                cl, idx = read_varint(mdata, idx); cdata = mdata[idx:idx+cl]; idx += cl
                ci = 0
                while ci < len(cdata):
                    ck, ci = read_varint(cdata, ci); ct, cw = ck >> 3, ck & 7
                    if ct == 10 and cw == 2:
                        tl, ci = read_varint(cdata, ci); tdata = cdata[ci:ci+tl]; ci += tl
                        ti = 0
                        while ti < len(tdata):
                            tk, ti = read_varint(tdata, ti); tt, tw = tk >> 3, tk & 7
                            if tt == 3 and tw == 2:
                                sl, ti = read_varint(tdata, ti); sub = tdata[ti:ti+sl]; ti += sl
                                si = 0
                                while si < len(sub):
                                    sk, si = read_varint(sub, si); st, sw = sk >> 3, sk & 7
                                    if st == 2 and sw == 2:
                                        name, si = read_string(sub, si)
                                        if name: teams.append(name)
                                        break
                                    else: si = skip_field(sub, si, sw)
                                break
                            else: ti = skip_field(tdata, ti, tw)
                    else: ci = skip_field(cdata, ci, cw)
            else: idx = skip_field(mdata, idx, wire)
        except: break
    return {'id': match_id, 'status': match_status, 'time': match_time, 'sport': sport_type, 'teams': teams, 'league': league_name}

def parse_api_response(data, sport_type_hint=0):
    matches = []
    idx = 0
    while idx < len(data):
        try:
            key, idx = read_varint(data, idx); tag, wire = key >> 3, key & 7
            if tag == 10 and wire == 2:
                length, idx = read_varint(data, idx); block = data[idx:idx+length]; idx += length
                bi = 0
                while bi < len(block):
                    bk, bi = read_varint(block, bi); bt, bw = bk >> 3, bk & 7
                    if bt == 1 and bw == 2:
                        ml, bi = read_varint(block, bi); mdata = block[bi:bi+ml]; bi += ml
                        m = parse_match_basic(mdata)
                        if m['id'] > 0:
                            if m['sport'] == 0: m['sport'] = sport_type_hint
                            matches.append(m)
                    else: bi = skip_field(block, bi, bw)
                break
            elif wire == 2: l, idx = read_varint(data, idx); idx += l
            else: _, idx = read_varint(data, idx)
        except: break
    return matches

def get_api_host():
    # Domain diambil dari env var (GitHub Secrets), bukan hardcoded
    domains = list(dict.fromkeys(filter(None, [RBTV_SITE_URL, RBTV_FALLBACK_URL])))
    for domain in domains:
        try:
            resp = requests.get(domain, timeout=15, allow_redirects=True,
                               headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"})
            js_urls = list(set(re.findall(r"https://statics1\.[a-zA-Z0-9.-]+\.cfd/statics/[a-f0-9]+\.js", resp.text)))
            for js_url in js_urls:
                try:
                    js = requests.get(js_url, timeout=8, headers={"User-Agent": "Mozilla/5.0"}).text
                    m = re.search(r"CF_DA_API['\"]?\s*:\s*['\"]?(https://apis-data[0-9]*\.[a-zA-Z0-9.-]+\.[a-zA-Z]+)", js)
                    if m:
                        print(f"  API host: {m.group(1)} (dari {resp.url})")
                        return m.group(1)
                except: pass
        except Exception as e:
            print(f"  Gagal {domain}: {e}")
    return "https://apis-data10.tccdc64dgee.cfd"

def fetch_all_matches_from_api(api_host):
    """Ambil semua match dari API RBTV+ langsung (Python requests)"""
    sport_types = [1, 2, 3, 4, 6, 7, 8, 10, 12, 13, 14, 15, 16, 90]
    all_matches = {}
    for sport_type in sport_types:
        try:
            bs_url = f"{api_host}/api/common/bs?code=100&code=101&stream=true&sportType={sport_type}&language=34"
            r = requests.get(bs_url, timeout=10, headers={"User-Agent": "Mozilla/5.0", "Referer": "https://www.rbtvplus18.hair/"})
            token = None
            data = r.content
            marker = bytes([8, 100, 18, 32])
            for i in range(len(data) - len(marker)):
                if data[i:i+len(marker)] == marker:
                    token = data[i+4:i+4+32].decode('utf-8'); break
            if not token: continue
            jp = f'{{"sportType":{sport_type},"language":34,"stream":true}}'
            sfver = f"sfver{md5(jp)[:6]}{token}"
            url = f"{api_host}/{sfver}/api/match/live?sportType={sport_type}&language=34&stream=true"
            r = requests.get(url, timeout=15, headers={"User-Agent": "Mozilla/5.0", "Referer": "https://www.rbtvplus18.hair/"})
            if r.status_code == 200:
                matches = parse_api_response(r.content, sport_type)
                for m in matches:
                    if m['id'] > 0 and m['id'] not in all_matches:
                        all_matches[m['id']] = m
                print(f"  [{SPORT_NAMES.get(sport_type,'?')}] {len(matches)} matches")
        except Exception as e:
            print(f"  [sport={sport_type}] Error: {e}")
    return all_matches

# =========================================================
# BAGIAN 2: Playwright DOM Scraping
# =========================================================
async def get_visible_match_ids_via_playwright():
    """
    Buka website RBTV+ dengan Playwright + stealth settings,
    ekstrak matchId yang benar-benar tampil di halaman Live.
    """
    visible_ids = set()
    STEALTH_JS = """
        Object.defineProperty(navigator, 'webdriver', {get: () => undefined});
        Object.defineProperty(navigator, 'plugins', {get: () => [1,2,3]});
        Object.defineProperty(navigator, 'languages', {get: () => ['id-ID','id','en']});
        window.chrome = {runtime: {}};
    """
    try:
        async with async_playwright() as p:
            browser = await p.chromium.launch(
                headless=True,
                args=[
                    '--no-sandbox', '--disable-dev-shm-usage',
                    '--disable-blink-features=AutomationControlled',
                    '--disable-web-security',
                    '--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36'
                ]
            )
            context = await browser.new_context(
                viewport={'width': 1920, 'height': 1080},
                user_agent='Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36',
                locale='id-ID',
                timezone_id='Asia/Jakarta',
                java_script_enabled=True,
                ignore_https_errors=True
            )
            # Inject stealth script sebelum halaman dimuat
            await context.add_init_script(STEALTH_JS)
            page = await context.new_page()

            print("Membuka RBTV+ website...")
            try:
                await page.goto("https://www.rbtvplus18.hair/id/",
                               wait_until='domcontentloaded', timeout=30000)
                print("Halaman dimuat, menunggu konten...")
                await asyncio.sleep(8)
            except Exception as e:
                print(f"Timeout saat goto: {e}")

            # Coba scroll untuk trigger lazy loading
            await page.evaluate("window.scrollTo(0, document.body.scrollHeight / 2)")
            await asyncio.sleep(3)
            await page.evaluate("window.scrollTo(0, 0)")
            await asyncio.sleep(2)

            # Ekstrak semua href yang mengandung matchId
            try:
                hrefs = await page.evaluate("""
                    () => {
                        const result = [];
                        document.querySelectorAll('a').forEach(a => {
                            const href = a.href || a.getAttribute('href') || '';
                            if (href) result.push(href);
                        });
                        return result;
                    }
                """)
                for href in hrefs:
                    for mid in re.findall(r'[/-](\d{7,})', href):
                        visible_ids.add(int(mid))
                print(f"  DOM links: {len(hrefs)} hrefs, {len(visible_ids)} matchIds")
            except Exception as e:
                print(f"  Error ekstrak hrefs: {e}")

            # Coba juga data attributes
            try:
                data_ids = await page.evaluate("""
                    () => {
                        const ids = [];
                        document.querySelectorAll('[data-id],[data-match-id],[data-matchid]').forEach(el => {
                            const id = el.dataset.id || el.dataset.matchId || el.dataset.matchid;
                            if (id) ids.push(id);
                        });
                        return ids;
                    }
                """)
                for did in data_ids:
                    m = re.search(r'(\d{7,})', str(did))
                    if m: visible_ids.add(int(m.group(1)))
            except: pass

            # Ambil text dari halaman untuk cari match IDs
            try:
                page_text = await page.inner_text('body')
                for mid in re.findall(r'\b(\d{7,8})\b', page_text):
                    visible_ids.add(int(mid))
            except: pass

            # Screenshot untuk debug
            try:
                await page.screenshot(path='/tmp/rbtv_debug.png', full_page=False)
                print("  Screenshot disimpan ke /tmp/rbtv_debug.png")
            except: pass

            await browser.close()
    except Exception as e:
        print(f"Playwright error: {e}")
    return visible_ids

# =========================================================
# BAGIAN 3: Main Logic
# =========================================================
async def main():
    print("=" * 60)
    print("RBTV+ Live Match Hybrid Scraper")
    print("=" * 60)
    now_ms = int(time.time() * 1000)

    # Step 1: Ambil semua match dari API
    print("\n[1] Fetch semua match dari API...")
    api_host = await asyncio.to_thread(get_api_host)
    all_matches = await asyncio.to_thread(fetch_all_matches_from_api, api_host)
    print(f"  Total API matches: {len(all_matches)}")

    # Step 2: Buka website & ekstrak visible matchIds
    print("\n[2] Playwright DOM extraction...")
    visible_ids = await get_visible_match_ids_via_playwright()
    print(f"  Visible matchIds dari DOM: {len(visible_ids)}")

    # Step 3: Tentukan hasil akhir
    if len(visible_ids) > 3:
        # DOM extraction berhasil - intersection API + DOM
        live_matches = [m for m in all_matches.values() if m['id'] in visible_ids]
        source = f"intersection (API+DOM, {len(visible_ids)} DOM ids)"
        print(f"\n[3] Menggunakan intersection: {len(live_matches)} live matches")
    else:
        # Fallback: API filter ONGOING + 60 menit
        live_matches = []
        for m in all_matches.values():
            if m['status'] >= 10000: continue
            if m['status'] in ONGOING: live_matches.append(m)
            elif m['time'] > 0 and now_ms >= (m['time'] + 60 * 60 * 1000): live_matches.append(m)
        source = "API filter fallback (ONGOING + 60min)"
        print(f"\n[3] Fallback API filter: {len(live_matches)} live matches")

    # Format output
    output = {
        "updated_at": datetime.now(WIB).strftime("%Y-%m-%dT%H:%M:%S+07:00"),
        "source": source,
        "total": len(live_matches),
        "matches": []
    }
    for m in sorted(live_matches, key=lambda x: x['time']):
        sport_name = SPORT_NAMES.get(m['sport'], f"Sport{m['sport']}")
        status_name = STATUS_NAMES.get(m['status'], "Live")
        name = " vs ".join(m['teams']) if m['teams'] else (m['league'] or f"Match {m['id']}")
        t_wib = datetime.fromtimestamp(m['time']/1000, WIB).strftime("%H:%M") if m['time'] else "?"
        output["matches"].append({
            "matchId": m['id'], "sport": m['sport'], "sportName": sport_name,
            "name": name, "league": m['league'] or "", "status": m['status'],
            "statusName": status_name, "matchTime": m['time'], "matchTimeWib": t_wib
        })
        print(f"  [{sport_name}] {name} | {status_name} | {t_wib}")

    json_content = json.dumps(output, ensure_ascii=False, indent=2)

    # Update Gist
    if GIST_TOKEN and GIST_ID:
        print(f"\nUpdate Gist...")
        resp = await asyncio.to_thread(
            requests.patch,
            f"https://api.github.com/gists/{GIST_ID}",
            headers={"Authorization": f"token {GIST_TOKEN}", "Accept": "application/vnd.github.v3+json"},
            json={"files": {"live_matches.json": {"content": json_content}}}
        )
        if resp.status_code == 200: print("✅ Gist berhasil diupdate!")
        else: print(f"❌ Error: {resp.status_code}"); sys.exit(1)
    else:
        print("\n[DRY RUN]:")
        print(json_content[:600])

if __name__ == "__main__":
    asyncio.run(main())
