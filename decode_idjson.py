import urllib.request, json, time

# From firebase config, channel_list_url points to SP.json
# Let's find the list of all country files from the same github repo
# Try common country codes
BASE = 'https://cdn.jsdelivr.net/gh/duktektv/duktektv/bittv/'

# Common country codes seen in BitTV / similar IPTV apps
COUNTRY_CODES = [
    'SP', 'ID', 'MY', 'SG', 'US', 'UK', 'IN', 'TH', 'PH', 'AU',
    'AR', 'BR', 'CN', 'JP', 'KR', 'VN', 'TR', 'AE', 'SA', 'EG',
    'FR', 'DE', 'IT', 'ES', 'PT', 'NL', 'BE', 'CH', 'AT', 'PL',
    'RU', 'UA', 'RO', 'HU', 'CZ', 'SK', 'BG', 'HR', 'RS', 'BA',
    'MK', 'AL', 'GR', 'CY', 'MT', 'LV', 'LT', 'EE', 'FI', 'SE',
    'NO', 'DK', 'IS', 'IE', 'NZ', 'CA', 'MX', 'CO', 'VE', 'CL',
    'PE', 'EC', 'BO', 'PY', 'UY', 'GT', 'HN', 'SV', 'NI', 'CR',
    'PA', 'DO', 'CU', 'HT', 'JM', 'TT', 'BB', 'GY', 'SR', 'MZ',
    'ZA', 'NG', 'GH', 'KE', 'ET', 'TZ', 'UG', 'RW', 'SN', 'MA',
    'DZ', 'TN', 'LY', 'SD', 'IR', 'IQ', 'SY', 'JO', 'LB', 'IL',
    'KW', 'QA', 'BH', 'OM', 'YE', 'PK', 'BD', 'LK', 'NP', 'MM',
    'KH', 'LA', 'BN', 'MN', 'AF', 'KZ', 'UZ', 'TM', 'TJ', 'KG',
    'AZ', 'AM', 'GE', 'MD', 'BY', 'MK', 'GEN', 'XXX', 'NEWS', 'KIDS',
    'MOVIE', 'SPORT', 'LIVE'
]

found = []
for code in COUNTRY_CODES:
    url = BASE + code + '.json'
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        resp = urllib.request.urlopen(req, timeout=5)
        data = json.loads(resp.read().decode('utf-8'))
        n = len(data.get('info', []))
        print(f'FOUND: {code} -> {data.get("country_name", "?")} ({n} channels)')
        found.append({'code': code, 'name': data.get('country_name', ''), 'count': n})
        time.sleep(0.2)
    except Exception as e:
        pass  # not found

print()
print(f'Total countries found: {len(found)}')
print(json.dumps(found, indent=2))
