# Checklist verifikasi

## Struktur (bisa diverifikasi dari arsip)
- [x] Repo berisi workflow GitHub Actions `build-apk.yml`
- [x] Gradle Android Application + minSdk 29 / targetSdk 35
- [x] Java source aplikasi Android native lengkap
- [x] AndroidManifest service mediaProjection & permissions terkait
- [x] Editor teks HTML, pilih file SAF, preview WebView
- [x] Pengaturan durasi, FPS, bitrate/resolusi, JS trigger
- [x] Simpan MP4 ke MediaStore, opsi putar & bagikan
- [x] HTML contoh dan logo aplikasi
- [x] Tidak ada kebutuhan API key / backend

## Masih memerlukan verifikasi nyata
- [ ] GitHub Actions `assembleDebug` sukses pada repository pengguna
- [ ] APK terpasang di perangkat Android pengguna
- [ ] Permission capture dan render H.264 bekerja pada ROM/perangkat pengguna
- [ ] Uji video 10 detik dan kompatibilitas pemutar video

Proyek disiapkan dan diperiksa secara statis. Lingkungan pembuat arsip tidak menyediakan Android SDK/Gradle beserta koneksi download dependensi sehingga **APK tidak diklaim sudah dikompilasi**. Jalankan workflow GitHub Actions untuk uji kompilasi sebenarnya.
