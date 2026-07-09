# 📺 Cloudstream Playlist & Event Repository

[![Build Status](https://img.shields.io/github/actions/workflow/status/xr3ed/M3U-Playlist-Player-Repo-for-Cloudstream/build.yml?branch=main&style=for-the-badge)](https://github.com/xr3ed/M3U-Playlist-Player-Repo-for-Cloudstream/actions)
[![License](https://img.shields.io/github/license/xr3ed/M3U-Playlist-Player-Repo-for-Cloudstream?style=for-the-badge&color=blue)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android-green?style=for-the-badge&logo=android)](https://github.com/Lagradost/CloudStream-3)

Repositori resmi untuk memasang ekstensi pemutar M3U dan live streaming olahraga di **Cloudstream** secara bersih, aman, optimal, dan tanpa iklan/popup Telegram yang mengganggu.

---

## 📢 PENGUMUMAN PENTING

> [!WARNING]
> Seluruh ekstensi dan saluran streaming di dalam repositori ini **100% GRATIS**. Jika Anda membeli atau membayar seseorang untuk mendapatkan akses ini, maka **Anda telah DITIPU!**

---

## 🧩 Ekstensi yang Tersedia

1. **`Xr3edEvent`**  
   Menyediakan akses siaran langsung pertandingan olahraga (UCL, Bundesliga, MotoGP, F1, dll.), jadwal pertandingan ter-update, saluran TV Nasional, TV Movies, dan hiburan lainnya.
   
2. **`RBTVPlus`**  
   Ekstensi pemutar streaming olahraga multi-event yang dioptimalkan untuk menyaksikan siaran langsung berbagai pertandingan olahraga populer seperti sepak bola, bulu tangkis (badminton), bola basket, tenis, dan event olahraga dunia lainnya yang disiarkan di platform olahraga **RBTV+** (Superabbit77).

3. **`M3UPlaylistPlayer`**  
   Pemutar playlist M3U bawaan untuk memutar daftar saluran kustom Anda sendiri di dalam Cloudstream.

---

## 📥 Cara Pemasangan di Cloudstream

Ikuti langkah mudah berikut untuk menambahkan repositori ke aplikasi Cloudstream Anda. Anda dapat memilih salah satu jalur repositori berikut sesuai kebutuhan:

### 1. Jalur jsDelivr CDN (Direkomendasikan ⭐)
Jalur ini sangat direkomendasikan karena **bebas dari masalah limitasi unduhan (Error 429 / Too Many Requests)**.
* **Tautan Repositori:**
  ```text
  https://raw.githubusercontent.com/xr3ed/M3U-Playlist-Player-Repo-for-Cloudstream/builds/xr3ed-jsdelivr.json
  ```

### 2. Jalur GitHub Raw
Jalur langsung ke GitHub. Gunakan jalur ini hanya jika koneksi Anda ke CDN terhambat dan IP Anda tidak dibatasi limitasi GitHub.
* **Tautan Repositori:**
  ```text
  https://raw.githubusercontent.com/xr3ed/M3U-Playlist-Player-Repo-for-Cloudstream/builds/xr3ed.json
  ```

---

### Langkah Pemasangan di Aplikasi:
1. **Salin salah satu Tautan Repositori** di atas (disarankan jalur **jsDelivr CDN**).
2. Buka aplikasi **Cloudstream**.
3. Masuk ke menu **Settings** > **Extensions**.
4. Ketuk tombol **Add Repository** di pojok kanan bawah.
5. Tempel tautan repositori yang sudah disalin, beri nama (misal: `xr3ed Repo`), lalu ketuk **Add**.
6. Cari ekstensi yang Anda butuhkan (seperti **Xr3edEvent**, **RBTVPlus**, atau **#Dracin Melolo**) pada daftar repositori baru tersebut, lalu ketuk **Install**.

---

## 🛠️ Pengembangan Lokal (Untuk Developer)

Jika Anda ingin memodifikasi atau mengompilasi ekstensi ini secara lokal:

1. Clone repositori ini:
   ```bash
   git clone https://github.com/xr3ed/M3U-Playlist-Player-Repo-for-Cloudstream.git
   ```
2. Jalankan kompilasi menggunakan Gradle wrapper:
   ```bash
   ./gradlew make
   ```
3. File ekstensi `.cs3` yang siap pasang akan dibuat di dalam direktori `build/` masing-masing sub-project.
