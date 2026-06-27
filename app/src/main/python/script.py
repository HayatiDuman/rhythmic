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
# 1. ARAMA KATMANI (SEARCH LAYER)
# ---------------------------------------------------------
_ARAMA_OTURUMLARI = {}

def youtube_ara(sorgu, yeni_arama=True):
    global _ARAMA_OTURUMLARI
    try:
        if yeni_arama:
            arama_objesi = VideosSearch(sorgu)
            _ARAMA_OTURUMLARI[sorgu] = arama_objesi
            islenen_liste = arama_objesi.result().get('result', [])
        else:
            arama_objesi = _ARAMA_OTURUMLARI.get(sorgu)
            if not arama_objesi:
                return ["Hata: Arama oturumu zaman aşımına uğradı."]
            try:
                has_more = arama_objesi.next()
            except Exception as e:
                hata_mesaji = str(e)
                if "NoneType" in hata_mesaji:
                    hata_mesaji = "YouTube token reddetti (NoneType)"
                return [f"Hata: {hata_mesaji}"]

            if not has_more:
                return []
            islenen_liste = arama_objesi.result().get('result', [])

        sonuclar = []
        for entry in islenen_liste:
            if entry.get('type') != 'video': continue
            video_id = entry.get('id')
            if not video_id: continue

            url = entry.get('link') or f"https://www.youtube.com/watch?v={video_id}"
            baslik = entry.get('title', 'Bilinmiyor')
            sure_str = entry.get('duration')
            if not sure_str: continue

            thumbnails = entry.get('thumbnails', [])
            resim = thumbnails[0]['url'] if thumbnails else f"https://i.ytimg.com/vi/{video_id}/mqdefault.jpg"
            sanatci = entry.get('channel', {}).get('name', 'YouTube')

            parts = sure_str.split(':')
            saniye = 0
            if len(parts) == 2:
                saniye = int(parts[0]) * 60 + int(parts[1])
            elif len(parts) == 3:
                saniye = int(parts[0]) * 3600 + int(parts[1]) * 60 + int(parts[2])

            audio_mb = str(round((AUDIO_BITRATE_KBPS * saniye) / 8 / 1024, 2))
            sonuclar.append(f"{baslik}|||{url}|||{resim}|||{sure_str}|||{sanatci}|||{video_id}|||{audio_mb}|||Bilinmiyor")

        return sonuclar
    except Exception as e:
        traceback.print_exc()
        return [f"Hata: {str(e)}"]

# ---------------------------------------------------------
# 2. İNDİRME KATMANI (DOWNLOAD LAYER)
# ---------------------------------------------------------
def videoyu_indir(url, kayit_yolu, progress_cb):
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
            # En kaliteli, saf ses formatını seçiyoruz
            "format": "140/bestaudio/best",
            "skip_download": False,
            "quiet": True,
            "no_warnings": True,
            "nocheckcertificate": True,
            "noplaylist": True,

            # 🔥 İŞTE SİHİRLİ DOKUNUŞ BURASI 🔥
            # YouTube web sayfasını kullanmayı reddediyor, doğrudan Android ve iOS API'lerini zorluyoruz.
            # Bu sayede "Bot musun?" doğrulama ekranı (HTML) yerine doğrudan saf API verisi gelir.
            "extractor_args": {
                "youtube": ["client=ANDROID,IOS"]
            }
        }

        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=True)
            return ydl.prepare_filename(info)

    except Exception as e:
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