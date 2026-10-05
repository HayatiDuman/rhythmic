import os
import yt_dlp
import traceback
from yt_dlp.postprocessor.ffmpeg import FFmpegPostProcessor


COMMON_HEADERS = {
    'User-Agent': (
        'Mozilla/5.0 (Windows NT 10.0; Win64; x64) '
        'AppleWebKit/537.36 (KHTML, like Gecko) '
        'Chrome/120.0.0.0 Safari/537.36'
    )
}

AUDIO_BITRATE_KBPS = 128

# ---------------------------------------------------------
# 2. İNDİRME KATMANI (DOWNLOAD LAYER)
# ---------------------------------------------------------
def videoyu_indir(url, kayit_yolu, progress_cb, cancel_cb=None):
    try:
        os.makedirs(kayit_yolu, exist_ok=True)

        def hook(d):
            # Sadece indirme akarken kullanıcı iptal ettiyse güvenli hata fırlat
            if cancel_cb is not None and cancel_cb():
                raise Exception("DOWNLOAD_CANCELLED_BY_USER")

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
            "format": "140/bestaudio/best",
            "skip_download": False,
            "quiet": True,
            "no_warnings": True,
            "nocheckcertificate": True,
            "noplaylist": True,
            'no_resume': True,
            'cachedir': False,
            "extractor_args": {
                "youtube": ["client=ANDROID,IOS"]
            }
        }

        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=True)
            return ydl.prepare_filename(info)

    except Exception as e:
        if "DOWNLOAD_CANCELLED_BY_USER" in str(e):
            return "CANCELLED"
        traceback.print_exc()
        return f"Hata: {str(e)}"


# ---------------------------------------------------------
# 3. METADATA & STREAM KATMANI (YENİ)
# ---------------------------------------------------------
def get_video_info(url):
    try:
        ydl_opts = {
            "quiet": True,
            "no_warnings": True,
            "nocheckcertificate": True,
            "extractor_args": {
                "youtube": ["client=ANDROID,IOS"]
            }
        }
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=False)

            view_count = info.get('view_count', 0)
            like_count = info.get('like_count', 0)
            upload_date = info.get('upload_date', 'Bilinmiyor')
            exact_video_id = info.get('id', '')
            #exact_video_id = 'lDpXhLzJQsA'

            if len(upload_date) == 8 and upload_date.isdigit():
                upload_date = f"{upload_date[6:8]}.{upload_date[4:6]}.{upload_date[:4]}"

            try:
                view_count_str = f"{view_count:,}".replace(",", ".")
                like_count_str = f"{like_count:,}".replace(",", ".")
            except:
                view_count_str = str(view_count)
                like_count_str = str(like_count)

            return f"{view_count_str}|||{upload_date}|||{like_count_str}|||{exact_video_id}"
    except Exception as e:
        traceback.print_exc()
        return f"Hata: {str(e)}"

def get_stream_url(url):
    try:
        # Tıpkı indirmedeki gibi en iyi ses formatını seçiyoruz
        ydl_opts = {
            "quiet": True,
            "no_warnings": True,
            "nocheckcertificate": True,
            "format": "bestaudio[ext=m4a]/140/bestaudio/best",
            "extractor_args": {
                "youtube": ["client=ANDROID,IOS"]
            }
        }
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=False)
            # Seçilen formatın doğrudan oynatılabilir (stream) adresini döndürüyoruz
            return info.get('url', "Hata: Stream adresi bulunamadı")
    except Exception as e:
        traceback.print_exc()
        return f"Hata: {str(e)}"