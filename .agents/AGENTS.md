## Aturan Khusus M3U-Playlist-Player-Repo

### 1. Penanganan User-Agent pada Worker
- Pembungkus HTTP Cloudstream (`app.get`, `app.post`) otomatis menimpa header `User-Agent` dengan UA Android bawaannya.
- Jika worker/API mengembalikan konten berbeda berdasarkan User-Agent (misal DASH desktop vs HLS seluler), gunakan `okhttp3.OkHttpClient` bersih/kustom untuk request ke worker agar header `User-Agent` desktop kita tidak ditimpa.

### 2. Bypass Pembatasan Video Cloudflare (AIV/Nitro Streams)
- Cloudflare Pages/Workers memblokir file media (.mp4, .m4s) dengan mengembalikan loop video 12,275 bytes.
- Untuk aliran Amazon Prime/Nitro (`pdx-nitro`, `lhr-nitro`), selalu bersihkan URL proxy worker (`*.workers.dev` atau `*.pages.dev`) secara dinamis dan arahkan langsung ke CDN Amazon asli (`https://otte.cache.aiv-cdn.net/`) guna menghindari pemblokiran Cloudflare.

### 3. Pembersihan ID Saluran Secara Sistematis
- ID saluran dalam playlist M3U sering kali memiliki prefiks `x` (seperti `xbein`, `xtsn`, `xvidx`, `xdazn`, `xfs`, `xssc`, `xone`, `xvpl`) atau sufiks `myx`.
- Sebelum melakukan pengecekan status saluran (isChannelAlive) atau penyelesaian stream (resolveSingleChannel), bersihkan prefiks dan sufiks ini secara simetris agar worker tidak mengembalikan `CHANNEL_NOT_FOUND` (DEAD).

### 4. Penanganan Proof-of-Work (PoW) Berbasis Bit
- Beberapa server (seperti Mapple) menggunakan tantangan PoW SHA-256 dengan tingkat kesulitan berbasis bit nol awal (`difficulty` bit), bukan karakter hex.
- Komputasi PoW menggunakan objek `BigInteger` di Android sangat lambat dan memicu thread timeout. Gunakan pemindaian byte mentah (`ByteArray`) dengan bitmask biner secara langsung agar pencarian nonce selesai di bawah 1 detik:
  ```kotlin
  val fullBytes = difficulty / 8
  val remainingBits = difficulty % 8
  val mask = (0xff shl (8 - remainingBits)) and 0xff
  // Verifikasi per byte tanpa alokasi BigInteger/String di dalam loop
  ```

### 5. Bypass Navigasi Next.js & Escape Token
- Halaman Next.js (seperti Mapple `/embed/...`) yang mengembalikan halaman 404 pada request HTTP biasa memerlukan header navigasi browser lengkap (`Sec-Fetch-Dest: document`, `Sec-Fetch-Mode: navigate`, `Upgrade-Insecure-Requests: 1`) agar tidak diblokir di sisi perutean server-side Next.js.
- Token di dalam payload Next.js sering kali dikembalikan dengan tanda kutip ganda ter-escape (`\"`). Gunakan regex yang toleran terhadap tanda kutip biasa maupun ter-escape: `Regex("""window\.__REQUEST_TOKEN__\s*=\s*\\?"([^"\\]+)\\?"""")`.

### 6. Integrasi MovieBox H5 & Mobile BFF Hybrid
- **Pencarian**: Gunakan API Mobile BFF (`https://api.inmoviebox.com/wefeed-mobile-bff/subject-api/search/v2`) karena stabil dan dapat diautentikasi menggunakan tanda tangan HMAC/Client Token standar.
- **Resolusi Tautan Video (Play/Download)**: Jangan gunakan API Mobile BFF `play-info` karena memblokir guest token (mengembalikan `406`). Gunakan API H5 Download (`https://h5-api.aoneroom.com/wefeed-h5api-bff/subject/download`) karena tidak memerlukan tanda tangan HMAC (`x-tr-signature`) atau otentikasi token, dan parameter `detailPath` bersifat opsional (dapat dibiarkan kosong `detailPath=`).

### 7. Penanganan Player Dinamis Byse/Mapple & AbyssPlayer (Hydrax)
- **Byse/Mapple PoW & Decryption**: Player seperti `emturbovid.com` (TURBOVIP) dan `gn1r5n.org` (CAST) menggunakan tantangan PoW SHA-256 bitmask dan dekripsi payload sources AES-GCM-128. Key AES dibentuk dari penggabungan byte array `key_parts` berdasarkan parameter `version` (mengambil part ke `v - 1` dan `31 - v - 1`). Tag otentikasi 16-byte berada di akhir payload.
- **AbyssPlayer (Hydrax) Decryption**: Menggunakan string payload Base64 ber-encoding ISO_8859_1. Key AES-CTR diturunkan dari `MD5Hex(user_id:slug:md5_id)`. Path URL didekripsi dengan key tersebut, lalu dienkripsi ulang menggunakan `MD5Hex(size)` sebagai key dan dikirim dalam bentuk double Base64 string (`Base64(Base64(path).replace("=",""))`).
- **Intersepsi Core Extractor Matcher**: Karena Core Cloudstream memiliki default matcher untuk domain tertentu (seperti `abyssplayer.com` atau `emturbovid.com`) yang sering gagal memuat akibat deteksi User-Agent/adblock, lakukan intersepsi secara manual dengan fungsi helper `getCustomExtractor` sebelum memanggil `loadExtractor` agar plugin extractor kustom kita selalu diprioritaskan.

### 8. Penanganan Kegagalan Playwright Bawaan
- Jika tool browser bawaan (`open_browser_url`) mengalami kegagalan (misalnya karena download driver Playwright diblokir/404), gunakan Playwright Python lokal (`C:\Python314\python.exe` dengan modul `playwright`) secara langsung di terminal melalui `run_command` untuk membuka halaman dan mengambil DOM/screenshot.

### 9. Aturan Penamaan Plugin & Sinkronisasi Manifest
- Nama tampilan plugin di daftar manajer ekstensi aplikasi Cloudstream dibentuk dari **nama direktori/folder proyek sub-modul Gradle** saat manifest `plugins.json` di-generate oleh Gradle task `makePluginsJson` di GitHub Actions. 
- Jika ingin merubah nama tampilan di daftar ekstensi (misal dari `AnimeSail` ke `AnimeSailXR`), Anda **harus mengganti nama folder proyek secara fisik** (misalnya menjadi `AnimeSailXR`), menyesuaikan settings Gradle, serta memperbarui skrip `build.yml`.
- Ingat bahwa nama daftar ekstensi ini terpisah dari nama provider internal yang didefinisikan lewat `override var name` di berkas Kotlin provider (yang digunakan di layar browsing/player). Jangan pernah merubah `override var name` kecuali diminta secara khusus oleh pengguna.

### 10. Penanganan Timeout Banyak Tab & Pembatasan Konkurensi (Semaphore)
- Jika plugin mendefinisikan banyak tab kategori (misal 30+ kategori di `mainPage`), Cloudstream akan memanggil `getMainPage` untuk semua kategori tersebut secara bersamaan saat dibuka.
- Untuk menghindari kemacetan koneksi (timeout 120.000 ms) atau pemblokiran server (Cloudflare 403), batasi konkurensi request jaringan menggunakan objek static `java.util.concurrent.Semaphore(3)` di dalam blok pembungkus fungsi HTTP request.

### 11. Penghindaran Macet Paginasi (Pagination Stall) & Chunking Lokal
- Cloudstream memiliki kendala antarmuka: Jika `getMainPage` mengembalikan daftar item kosong (`items.size == 0`) dengan `hasNext = true`, pengguna tidak bisa men-scroll lebih jauh untuk memicu pemuatan halaman berikutnya karena RecyclerView tidak bertambah tinggi.
- Untuk menghindari macet ketika halaman server kosong atau hanya berisi video duplikat lintas halaman, terapkan **loop iterasi internal pada Kotlin** untuk terus memuat halaman berikutnya sampai terkumpul minimal satu chunk item (misal 9 item) atau mencapai batas halaman akhir (maksimal 30 halaman).

### 12. Batasan Prosedur Deployment (Git Push)
- Untuk pengujian kode, selalu utamakan build lokal dan injeksi langsung ke emulator menggunakan skrip `inject_plugin.ps1`.
- **DILARANG KERAS** melakukan `git push` ke repositori remote (GitHub) sebelum mendapatkan instruksi atau izin eksplisit dari pengguna. Simpan pekerjaan dalam commit lokal terlebih dahulu saat menunggu feedback.

### 13. Prosedur Deployment Plugin Prebuild (FilmBoxOffice, MovieBoXR, dll)
- Plugin "prebuild" (yang folder source code-nya di-*ignore* dalam `.gitignore`) **TIDAK BOLEH** di-push biner `.cs3`-nya secara manual langsung ke branch `builds`.
- Alur kerja GitHub Actions pada repositori ini akan mengumpulkan dan menyalin otomatis SEMUA file `.cs3` yang ada di branch `main` (termasuk di dalam folder `prebuilts/`) dan melakukan *force-push* ke branch `builds`.
- **Langkah Pembaruan Prebuild yang Benar**:
  1. Lakukan *bump version* di `build.gradle.kts` plugin tersebut.
  2. Wajib **KOMPILASI ULANG** biner `.cs3` (misal dengan `./gradlew PluginName:make` or skrip `inject_plugin.ps1`) AGAR biner tersebut benar-benar mengandung versi terbaru.
  3. Salin file `.cs3` yang baru dikompilasi ke folder `prebuilts/` (misal `prebuilts/MovieBoXR.cs3`).
  4. Lakukan *bump version* juga pada file `prebuilts/prebuilts.json`.
  5. Commit dan lakukan `git push` ke branch `main`. Biarkan GitHub Actions yang mengurus branch `builds`.
