const https = require('https');
const http = require('http');
const vm = require('vm');
const util = require('util');

function fetch(url, timeoutMs = 8000) {
    return new Promise((resolve, reject) => {
        const parsed = new URL(url);
        const client = parsed.protocol === 'https:' ? https : http;
        const req = client.get(url, {
            headers: {
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36',
                'Accept': '*/*'
            },
            timeout: timeoutMs
        }, (res) => {
            if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
                let redirectUrl = res.headers.location;
                if (!redirectUrl.startsWith('http')) {
                    redirectUrl = new URL(redirectUrl, url).toString();
                }
                return fetch(redirectUrl, timeoutMs).then(resolve).catch(reject);
            }
            if (res.statusCode !== 200) {
                return reject(new Error(`HTTP ${res.statusCode} for ${url}`));
            }
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => resolve(data));
        });
        req.on('timeout', () => {
            req.destroy();
            reject(new Error(`Timeout fetching ${url}`));
        });
        req.on('error', reject);
    });
}

async function discoverLandingUrl() {
    const blogspotCandidates = [
        'https://kltratv13.blogspot.com/',
        'https://kltratv14.blogspot.com/',
        'https://kltratv15.blogspot.com/',
        'https://kltratv12.blogspot.com/',
        'https://kltratv11.blogspot.com/'
    ];

    for (const bUrl of blogspotCandidates) {
        try {
            const html = await fetch(bUrl, 5000);
            const m = html.match(/id=["']landingUrl["']\s+value=["'](https?:\/\/[^"']+)["']/i)
                || html.match(/value=["'](https?:\/\/[^"']+)["']\s+id=["']landingUrl["']/i);
            if (m && m[1]) {
                return m[1].replace(/\/+$/, '');
            }
        } catch (_) {}
    }
    return null;
}

async function extractFromDomain(siteUrl) {
    const origin = siteUrl.replace(/\/+$/, '');
    const hostname = origin.replace(/^https?:\/\//, '');

    const window = {
        location: {
            href: `${origin}/`,
            origin: origin,
            hostname: hostname,
            host: hostname,
            protocol: 'https:'
        },
        document: {
            referrer: '',
            currentScript: { src: `${origin}/js/allowed.js` },
            documentElement: { dataset: {} },
            addEventListener: () => {},
            getElementById: () => null,
            querySelector: () => null,
            querySelectorAll: () => [],
            createElement: () => ({ style: {}, setAttribute: () => {} })
        },
        TextEncoder: util.TextEncoder,
        TextDecoder: util.TextDecoder,
        btoa: (str) => Buffer.from(str, 'binary').toString('base64'),
        atob: (b64) => Buffer.from(b64, 'base64').toString('binary')
    };
    window.window = window;
    window.globalThis = window;
    window.self = window;

    const ctx = vm.createContext(window);
    const allowedJs = await fetch(`${origin}/js/allowed.js`, 8000);
    vm.runInContext(allowedJs, ctx);

    const configJs = await fetch(`${origin}/js/config.js`, 8000);
    vm.runInContext(configJs, ctx);

    const cfg = window.KLTRA_CONFIG;
    if (!cfg) throw new Error("window.KLTRA_CONFIG not found in context");

    return {
        landingUrl: origin,
        siteDomains: cfg.SITE_DOMAINS ? Array.from(cfg.SITE_DOMAINS) : [hostname],
        apiOrigin: cfg.API_ORIGIN || 'https://kltratv-web.pages.dev',
        xorKey: cfg._0x9b72e4a1 || '',
        catalogPaths: {
            events: (cfg.CATALOG_PATHS && cfg.CATALOG_PATHS.EVENTS) || '',
            servers: (cfg.CATALOG_PATHS && cfg.CATALOG_PATHS.SERVERS) || '',
            liveTv: (cfg.CATALOG_PATHS && cfg.CATALOG_PATHS.LIVE_TV) || '',
            hdAuto: (cfg.CATALOG_PATHS && cfg.CATALOG_PATHS.HD_AUTO) || ''
        },
        updatedAt: Date.now()
    };
}

async function main() {
    let domain = await discoverLandingUrl();
    const candidateDomains = [
        domain,
        'https://live.kltratv.site',
        'https://kltra-website-lab.pages.dev',
        'https://kltramy.pages.dev'
    ].filter(Boolean);

    let lastError = null;
    for (const d of candidateDomains) {
        try {
            const config = await extractFromDomain(d);
            if (config && config.xorKey && config.catalogPaths.events) {
                console.log(JSON.stringify(config, null, 2));
                return;
            }
        } catch (e) {
            lastError = e;
        }
    }

    console.error("Failed to extract KLTRA_CONFIG:", lastError);
    process.exit(1);
}

main();
