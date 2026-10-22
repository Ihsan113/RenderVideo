# Migrasi Renderify v1 ke v2 (untuk repository yang sudah ada)

Jika menjalankan `validate_project.py` dan muncul:

```
AssertionError: CaptureService.java
```

**Penyebab:** `CaptureService.java` milik v1 masih tersimpan di GitHub. Mengunggah
ZIP v2 melalui website GitHub **tidak otomatis menghapus file lama** yang
sudah tidak ada di ZIP.

## Solusi paling rapi: hapus file sekali saja di GitHub

1. Buka repository, masuk ke
   `app/src/main/java/id/danzku/renderify/CaptureService.java`.
2. Tekan menu `...` atau ikon hapus (Delete file); commit penghapusan ke `main`.
3. Unggah ulang **isi** ZIP v2.1 ke akar repo, pastikan folder tersembunyi
   `.github/workflows` dan folder baru `scripts/` ikut diunggah.
4. Buka **Actions → Build Android APK → Run workflow** jika diperlukan.

## Solusi otomatis: workflow v2.1

Workflow sekarang memiliki step `Remove obsolete v1 screen recorder` yang
menjalankan `python3 scripts/clean_legacy_sources.py` **sebelum validasi**.
Step ini menghapus file lama dari workspace GitHub Actions untuk build saja.
Ini memungkinkan APK dikompilasi walaupun file usang masih ada di repository.
Tetap disarankan menghapus file tersebut dari GitHub secara permanen.

Script tidak menghapus file lain. Renderify v2 menggunakan `PixelCopy`, OpenGL,
`MediaCodec`, dan `MediaMuxer`; **tidak memakai MediaProjection atau FFmpeg**.

**Penting:** Jangan menghapus `FrameRenderEngine.java` atau
`GpuFrameEncoder.java`; itu mesin render versi baru.
