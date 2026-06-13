import os
import yt_dlp
import traceback


COMMON_HEADERS = {
    'User-Agent': (
        'Mozilla/5.0 (Windows NT 10.0; Win64; x64) '
        'AppleWebKit/537.36 (KHTML, like Gecko) '
        'Chrome/120.0.0.0 Safari/537.36'
    )
}

AUDIO_BITRATE_KBPS = 128
VIDEO_BITRATE_KBPS = 2500

def _format_sure(seconds):
    """Saniyeyi mm:ss veya hh:mm:ss formatına çevirir"""
    try:
        seconds = int(seconds)
        m, s = divmod(seconds, 60)
        if m >= 60:
            h, m = divmod(m, 60)
            return f"{h}:{m:02d}:{s:02d}"
        return f"{m}:{s:02d}"
    except:
        return ""


def youtube_ara(sorgu):
    """
    Format: Başlık ||| URL ||| ResimURL ||| Süre ||| Sanatçı
    """
    ydl_opts = {
        "quiet": True,
        "skip_download": True,
        "extract_flat": True,
        "noplaylist": True,
        "nocheckcertificate": True,
        "http_headers": COMMON_HEADERS,
    }

    sonuclar = []

    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(f"ytsearch60:{sorgu}", download=False)

        for entry in info.get("entries", []):

            # Playlistleri ele
            if entry.get("_type") == "playlist":
                continue

            video_id = entry.get("id")
            if not video_id:
                continue

            # URL
            url = entry.get("url") or f"https://www.youtube.com/watch?v={video_id}"
            if "list=" in url or "playlist" in url:
                continue

            audio_mb="Bilinmiyor"
            video_mb="Bilinmiyor"

            # Süre (canlı yayın vs elenir)
            duration_seconds = entry.get("duration")
            if not duration_seconds:
                continue
            else:
                audio_mb = str(round(
                    (AUDIO_BITRATE_KBPS * duration_seconds) / 8 / 1024, 2
                ))

                video_mb = str(round(
                    (VIDEO_BITRATE_KBPS * duration_seconds) / 8 / 1024, 2
                ))

            baslik = entry.get("title", "Bilinmiyor")

            resim = (
                entry.get("thumbnail")
                or f"https://i.ytimg.com/vi/{video_id}/mqdefault.jpg"
            )

            sure = entry.get("duration_string") or _format_sure(duration_seconds)

            sanatci = entry.get("uploader", "YouTube")

            sonuclar.append(
                f"{baslik}|||{url}|||{resim}|||{sure}|||{sanatci}|||{video_id}|||{audio_mb}|||{video_mb}"
            )

    except Exception as e:
        traceback.print_exc()
        return [f"Hata: {str(e)}"]

    return sonuclar

def videoyu_indir(url, kayit_yolu, format_turu, progress_cb):
    try:
        os.makedirs(kayit_yolu, exist_ok=True)

        def hook(d):
            if d['status'] == 'downloading':
                total = d.get('total_bytes') or d.get('total_bytes_estimate')
                downloaded = d.get('downloaded_bytes')

                if total and downloaded:
                    percent = int(downloaded * 100 / total)
                    progress_cb(percent)

            elif d['status'] == 'finished':
                progress_cb(100)

        ydl_opts = {
            'progress_hooks': [hook],
            "outtmpl": f"{kayit_yolu}/%(title)s.%(ext)s",

            """ # 1. RESMİ AYRI DOSYA OLARAK İNDİR (.jpg veya .webp)
            "writethumbnail": True, """

            # 2. FFMPEG GÖMME İŞLEMİNİ İPTAL ETTİK (SİLDİK)
            "postprocessors": [],

            "skip_download": False,
            "quiet": True,
            "no_warnings": True,
            "nocheckcertificate": True,
            "noplaylist": True,
            "http_headers": COMMON_HEADERS,
        }

        if format_turu == "audio":
            ydl_opts["format"] = "140/251/18/bestaudio"
        else:
            ydl_opts["format"] = "22/18/best[ext=mp4]"

        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=True)
            return ydl.prepare_filename(info)

    except Exception as e:
        traceback.print_exc()
        return f"Hata: {str(e)}"

def download_with_progress(url, progress_callback):
    def hook(d):
        if d['status'] == 'downloading':
            total = d.get('total_bytes') or d.get('total_bytes_estimate')
            downloaded = d.get('downloaded_bytes')

            if total and downloaded:
                percent = int(downloaded * 100 / total)
                progress_callback.accept(percent)

    ydl_opts = {
        'progress_hooks': [hook],
        'outtmpl': '/storage/emulated/0/Music/Rhythmic/%(title)s.%(ext)s'
    }

    with YoutubeDL(ydl_opts) as ydl:
        ydl.download([url])