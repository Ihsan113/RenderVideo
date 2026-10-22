# Renderify 3.0 — Encoder Compatibility & 2K/4K Presets

**Perbaikan berangkat dari error nyata:** `Encoder H.264 yang sesuai tidak tersedia: tidak ada codec` pada mode Hardware/Software serta kualitas 540p, 720p, 1080p.

## Yang berubah

1. Codec H.264 diperiksa dari `MediaCodecList.ALL_CODECS`. Pemilihan hardware tidak lagi harus `isHardwareAccelerated()==true`; untuk Hardware yang penting bukan software-only.
2. Periksa mode `Surface` (GPU) **atau** `COLOR_FormatYUV420SemiPlanar` (NV12), `COLOR_FormatYUV420Planar` (I420), dan `COLOR_FormatYUV420Flexible` (`Image`/YUV_420_888). Jalur kedua memakai CPU untuk konversi warna, kemudian encoder MediaCodec perangkat.
3. Mode Auto mencoba semua kandidat valid yang sudah diprioritaskan, bukan hanya yang memiliki Surface input. Mode Software tetap software-only; mode Hardware tetap non-software-only. Keduanya tidak otomatis mengganti pilihan pengguna.
4. Menambahkan pemeriksaan kemampuan codec langsung di aplikasi (tombol **Periksa encoder HP**).
5. Ukuran gambar yang memerlukan alignment width/height kecil (misalnya 540→544) dicoba secara terbatas (perubahan maksimal 32px, aspek kurang dari 1%). Ukuran MP4 yang dipakai tertera pada layar hasil. Tidak pernah otomatis downgrade resolusi 4K menjadi 720p.
6. Resolusi 540p/720p/1080p/QHD 1440p/4K UHD 2160p; 24/30/60 FPS; rasio 9:16/16:9/1:1.
7. UI preset resolusi bisa di-scroll horizontal, tidak bertumpuk pada layar kecil.
8. Informasi RAM tidak cukup dan detail kegagalan codec lebih jelas; jalur EGL/surface dan YUV input keduanya dapat menyelesaikan MP4 melalui MediaMuxer.
9. Regression tests dipanggil GitHub Actions sebelum proses kompilasi APK.

## Batasan teknik yang penting

- **Belum bisa dijamin semua kombinasi** termasuk 4K60 berfungsi di semua HP. Tombol Periksa encoder baru membuktikan kandidat diiklankan perangkat, bukan bahwa konfigurasi/rekaman pasti berhasil.
- `PixelCopy` mengambil WebView **yang ditampilkan pada layar**, lalu membesarkan bitmap sesuai target. Output 1440p/2160p **bukan detail render native 2K/4K**. Resolusi asli membutuhkan renderer offscreen resolusi tinggi di versi berbeda.
- 60 FPS adalah FPS timeline output; bila sampling lama, frame dapat berulang. Ini **belum deterministic frame-by-frame**.
- Tanpa FFmpeg dan tanpa MediaProjection: `PixelCopy` + `MediaCodec` + `MediaMuxer` bawaan Android. Tidak bisa mengubah mode render menjadi benar-benar GPU zero-copy tanpa perubahan besar.
- Software encoder sering hanya mendukung resolusi/FPS terbatas; menggunakan CPU dapat jauh lebih lambat, terutama 4K.
- HTML dimuat lokal, tanpa akses internet. CSS/JS inline bisa; gambar/font eksternal via URL tidak otomatis tersedia.

## Setelah upload ke GitHub

1. Upload isi ZIP, bukan folder ZIP-nya, ke root branch `main`.
2. Pastikan folder `.github/workflows` dan `tests/` ikut diunggah.
3. Bila masih ada file lama `app/src/main/java/id/danzku/renderify/CaptureService.java`, hapus dari repository; build workflow juga menjalankan skrip pembersihan sementara.
4. GitHub Actions `Build Android APK` → unduh Artifact jika sukses.
5. Di HP mulai dengan 720p / 30 FPS / 9:16 / Auto → **Periksa encoder HP** → Render 2–10 detik. Lanjut 1080p lalu 1440p/2160p setelah hasil pertama berhasil.
6. Kalau gagal, kirim teks hasil pemeriksaan encoder dan layar error (juga merek/tipe Android) untuk diagnosis driver perangkat.

## Status QA

- `python3 validate_project.py`: lolos
- `python3 -m unittest discover -s tests -v`: 12 uji regresi source lolos
- Pemeriksaan parsing source Java tanpa Android SDK: tidak mendapati kesalahan sintaks yang jelas, tapi **bukan bukti kompilasi**.
- `gradle assembleDebug` dan ekspor MP4 pada HP nyata: **belum tersedia di lingkungan ini**, harus diuji lewat GitHub Actions dan perangkat.
