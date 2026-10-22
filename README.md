# Renderify — Android HTML → MP4

**Aplikasi Android lokal untuk mengubah animasi HTML + CSS + JavaScript menjadi MP4**. Dibuat dengan Java / Android SDK tanpa server, tanpa API key, tanpa FFmpeg, dan tanpa dependensi Android eksternal. Antarmuka berbahasa Indonesia.

## Fitur versi 1.0

- ✍️ Tempel/tulis HTML langsung di editor, atau pilih file `.html` lewat file manager Android.
- 👁️ Preview WebView interaktif; bisa tekan tombol dan coba animasi.
- ▶️ Pemicu JavaScript opsional ketika render dimulai, misalnya `document.querySelector('button')?.click()` atau `start10SecPayment()`.
- 🎬 Rekam video **MP4 H.264** menggunakan `MediaProjection` + `MediaRecorder` milik Android.
- ⏱️ Durasi **2–120 detik**, pilihan **24/30 FPS**, kualitas maksimal sekitar **540p/720p/1080p**.
- 📁 Simpan ke galeri/video pada `Movies/Renderify`, tombol putar & bagikan.
- 🔒 Tidak meminta izin jaringan, penyimpanan luas, atau akses mikrofon. Konten HTML tetap di perangkat.
- 📝 Editor autosave draft; menyertakan contoh animasi pembayaran.
- 🎨 UI mode gelap responsif, logo launcher asli, tombol proporsional.
- 📦 Build APK otomatis via GitHub Actions (`workflow_dispatch` / push).

## Build APK langsung dari GitHub Actions

1. Buat **repository GitHub baru** (misalnya `Renderify-Android`).
2. Ekstrak ZIP project ini, kemudian **unggah SELURUH isinya** ke root repository, bukan mengunggah ZIP sebagai 1 file. Pastikan folder tersembunyi `.github/workflows/` ikut masuk.
3. Buka tab **Actions** → **Build Android APK** → **Run workflow** → **Run workflow**.
4. Saat workflow hijau, buka run tersebut dan unduh Artifact **Renderify-Android-debug**.
5. Ekstrak artifact ZIP, lalu instal `app-debug.apk` di HP Android 10+ (izin instal aplikasi dari sumber ini mungkin diperlukan).

Workflow menggunakan **JDK 17**, **Gradle 8.11.1**, dan **Android Gradle Plugin 8.10.1**. Project sengaja tidak mengandalkan file wrapper lokal karena GitHub Actions memasang Gradle via `gradle/actions/setup-gradle`.

**Build lokal di PC**: buka folder project di Android Studio versi yang kompatibel dengan AGP 8.10.1, atau jalankan `gradle assembleDebug` jika Gradle 8.11.1 + Android SDK 35 telah terpasang.

## Cara menggunakan APK

1. Buka Renderify. Secara default editor berisi **contoh HTML pembayaran 10 detik**.
2. **Pilih file HTML** atau hapus contoh dan **tempel HTML sendiri**.
3. Ketuk **Perbarui** untuk melihat preview. Preview bisa dicoba secara interaktif.
4. Atur durasi, kualitas 540p/720p/1080p, FPS 24/30.
5. Jika animasi perlu klik tombol, biarkan JS pemicu `document.querySelector('button')?.click()` atau isi `start10SecPayment()` sesuai fungsi HTML. Jika animasi berjalan otomatis sejak halaman dibuka, **kosongkan** kolom pemicu.
6. Ketuk **RENDER KE MP4**, izinkan perekaman layar ketika Android meminta. Pastikan pilih rekam **seluruh layar** jika versi Android menawarkan opsi.
7. Jangan keluar dari halaman fullscreen selama proses. Setelah selesai, ketuk **Putar video** atau **Bagikan MP4**.

## Cara kerjanya

```
Editor / Pilih File HTML
        │
        ▼
WebView preview (offline)
        │
        ▼
RenderActivity (fullscreen khusus animasi)
        │
        ├─ Persetujuan MediaProjection dari Android
        ▼
CaptureService (foreground)
        ├─ MediaRecorder (H.264)
        ├─ VirtualDisplay → Surface encoder
        └─ Stop otomatis berdasarkan durasi
        ▼
MediaStore  ──► Movies/Renderify/Renderify_YYYYMMDD_HHMMSS.mp4
```

Pada Android 14+, sistem mewajibkan persetujuan baru pada **setiap render**. Renderify mendaftarkan callback untuk berhenti jika rekaman dihentikan Android, serta menghentikan capture saat pengguna meninggalkan halaman untuk menghindari merekam aplikasi lain.

## Batasan penting versi 1.0

- Render bersifat **realtime screen capture**, bukan offline frame-by-frame. Kecepatan dan kestabilan bergantung pada perangkat. FPS 24/30 adalah **setting target**, bukan jaminan tiap frame memiliki interval persis.
- Video merekam **layar fullscreen perangkat**. Rasio aspeknya mengikuti HP, **bukan 9:16 mutlak**; hasil bisa sedikit berbeda dari kualitas yang dipilih jika perangkat berasio tinggi atau encoder membatasi ukuran. Sistem Android tertentu mungkin menampilkan indikator perekaman.
- Tidak ada audio pada video; tidak ada output transparan.
- HTML harus **mandiri** (CSS/JS inline dan gambar via `data:` URI). CDN atau berkas relatif (`style.css`, `gambar.png`) tidak akan dimuat. Maksimal HTML 1 MB.
- Animasi yang bergantung pada pointer/touch manual harus dibuat dapat dimulai melalui fungsi JS atau tombol pertama; JS pemicu dapat disesuaikan.
- Preview berukuran compact; fullscreen render lebih besar. Layout responsif dengan `meta viewport` dan CSS fleksibel akan menghasilkan video paling bagus.
- Jika animasi mulai pada saat halaman selesai dimuat, video mungkin berisi beberapa frame awal saat halaman sedang disiapkan. Video juga bisa punya variasi durasi kecil karena proses encoder real-time.
- Beberapa ROM AOSP atau perangkat tanpa codec H.264 yang cocok mungkin gagal merekam pada profil resolusi tinggi. Pilih **540p** atau **720p** untuk uji awal.
- APK yang dihasilkan GitHub Actions adalah **debug APK** untuk instalasi manual, bukan release signed APK untuk Play Store.

## Struktur project

```text
Renderify-Android/
├─ .github/workflows/build-apk.yml  # Otomatis build dan upload debug APK
├─ app/build.gradle                 # Android SDK 35, Java 17
├─ app/src/main/AndroidManifest.xml
├─ app/src/main/assets/payment_demo.html
├─ app/src/main/java/id/danzku/renderify/
│  ├─ MainActivity.java            # Editor, file picker, preview, settings
│  ├─ RenderActivity.java          # Fullscreen capture & hasil
│  ├─ CaptureService.java          # MediaProjection, MediaRecorder, MediaStore
│  └─ Ui.java                      # Design system / komponen UI
├─ app/src/main/res/drawable/      # Ikon aplikasi
└─ branding/                       # PNG logo untuk desain/promosi
```

## Catatan teknis & keamanan

- `MainActivity.configureWebView` menyalakan JavaScript untuk menjalankan animasi, tetapi memblokir jaringan serta akses file/content WebView; tidak ada `addJavascriptInterface`.
- Berkas HTML diambil melalui Android Storage Access Framework dan disalin sebagai data UTF-8 agar pengguna tidak perlu memberikan akses penyimpanan menyeluruh.
- File video dibuat melalui `MediaStore` dalam mode `IS_PENDING` dan dipublikasikan setelah encoder berhasil. File gagal dihapus.
- Notifikasi foreground memiliki aksi penghentian render; Android dapat menghentikan sesi dari chip perekaman layar.
- HTML/JS dari sumber tak tepercaya tetap berisiko secara umum, walaupun akses file & jaringan dari WebView dibatasi. Gunakan hanya konten yang kamu pahami.

## Lisensi / penggunaan

Project contoh ini ditujukan sebagai fondasi aplikasi personal. Kamu bebas memodifikasi tampilan, nama, contoh HTML, dan kode sesuai kebutuhan.
