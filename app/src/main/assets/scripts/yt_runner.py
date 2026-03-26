#!/usr/bin/env python3
import sys
import os
import json
import argparse
import traceback


def setup_path():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    runtime_base = os.path.dirname(script_dir)
    site_packages = os.path.join(runtime_base, 'site-packages')

    if site_packages not in sys.path:
        sys.path.insert(0, site_packages)
    if script_dir not in sys.path:
        sys.path.insert(0, script_dir)


setup_path()


def fatal(reason: str, details: str = ""):
    payload = {
        "status": "error",
        "reason": reason,
        "details": details[:4000]
    }
    print("FATAL:" + json.dumps(payload, ensure_ascii=False), flush=True)
    sys.exit(1)


try:
    import ssl  # noqa
except Exception:
    fatal("SSL_IMPORT_ERROR", traceback.format_exc())

try:
    import hashlib  # noqa
except Exception:
    fatal("HASHLIB_IMPORT_ERROR", traceback.format_exc())

try:
    import yt_dlp
except Exception:
    fatal("YTDLP_IMPORT_ERROR", traceback.format_exc())


def progress_hook(d):
    try:
        status = d.get('status', 'unknown')

        if status == 'downloading':
            total = d.get('total_bytes') or d.get('total_bytes_estimate') or 0
            downloaded = d.get('downloaded_bytes', 0)
            speed = d.get('speed')
            eta = d.get('eta')

            percent = (downloaded / total * 100.0) if total > 0 else 0.0

            speed_str = ''
            if speed:
                if speed >= 1_048_576:
                    speed_str = f"{speed / 1_048_576:.1f} MB/s"
                elif speed >= 1024:
                    speed_str = f"{speed / 1024:.1f} KB/s"
                else:
                    speed_str = f"{speed:.0f} B/s"

            eta_str = ''
            if eta is not None:
                eta_str = f"{int(eta) // 60:02d}:{int(eta) % 60:02d}"

            progress_data = {
                'percent': round(percent, 1),
                'downloaded_bytes': downloaded,
                'total_bytes': total,
                'speed': speed_str,
                'eta': eta_str,
                'status': 'downloading',
                'phase': 'downloading'
            }
            print(f"PROGRESS:{json.dumps(progress_data)}", flush=True)

        elif status == 'finished':
            progress_data = {
                'percent': 100.0,
                'downloaded_bytes': d.get('total_bytes', 0),
                'total_bytes': d.get('total_bytes', 0),
                'speed': '',
                'eta': '',
                'status': 'finished',
                'phase': 'complete'
            }
            print(f"PROGRESS:{json.dumps(progress_data)}", flush=True)

        elif status == 'error':
            print(
                f"PROGRESS:{json.dumps({'percent': 0, 'status': 'error', 'phase': 'error'})}",
                flush=True
            )

    except Exception as e:
        print(f"ERROR:Progress hook: {e}", file=sys.stderr, flush=True)


def build_outtmpl(output_dir, filename):
    if filename:
        safe = "".join(c for c in filename if c not in r'\/:*?"<>|')
        return os.path.join(output_dir, f"{safe}.%(ext)s")
    return os.path.join(output_dir, "%(title)s.%(ext)s")


def apply_common_ydl_opts(ydl_opts):
    node_path = os.environ.get("YTDLP_NODE_PATH", "").strip()
    ffmpeg_path = os.environ.get("YTDLP_FFMPEG_PATH", "").strip()

    if node_path and os.path.exists(node_path):
        ydl_opts['js_runtimes'] = {
            'node': {
                'path': node_path
            }
        }

    if ffmpeg_path and os.path.exists(ffmpeg_path):
        ydl_opts['ffmpeg_location'] = ffmpeg_path

    return ydl_opts


def cmd_info(args):
    url = args.url

    ydl_opts = {
        'quiet': True,
        'no_warnings': True,
        'skip_download': True,
        'no_color': True,
        'writeinfojson': False,
        'writedescription': False,
        'writethumbnail': False,
    }

    apply_common_ydl_opts(ydl_opts)

    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=False)
            if info is None:
                fatal("INFO_EXTRACTION_FAILED", "extract_info returned None")

            formats = []
            for f in info.get('formats', []):
                formats.append({
                    'format_id': f.get('format_id', ''),
                    'ext': f.get('ext', ''),
                    'resolution': f.get('resolution', 'unknown'),
                    'filesize': f.get('filesize'),
                    'filesize_approx': f.get('filesize_approx'),
                    'fps': f.get('fps'),
                    'vcodec': f.get('vcodec', 'none'),
                    'acodec': f.get('acodec', 'none'),
                    'abr': f.get('abr'),
                    'tbr': f.get('tbr'),
                    'format_note': f.get('format_note', ''),
                    'height': f.get('height'),
                    'width': f.get('width'),
                })

            output = {
                'title': info.get('title', 'Unknown'),
                'duration': info.get('duration'),
                'thumbnail': info.get('thumbnail'),
                'uploader': info.get('uploader'),
                'description': (info.get('description', '') or '')[:500],
                'formats': formats,
            }

            print(json.dumps(output, ensure_ascii=False), flush=True)

    except yt_dlp.utils.DownloadError:
        fatal("YTDLP_DOWNLOAD_ERROR", traceback.format_exc())
    except Exception:
        fatal("INFO_EXCEPTION", traceback.format_exc())


def cmd_download_single(args):
    url = args.url
    fmt = args.format
    output_dir = args.output_dir
    filename = args.filename

    outtmpl = build_outtmpl(output_dir, filename)

    ydl_opts = {
        'format': fmt,
        'outtmpl': outtmpl,
        'progress_hooks': [progress_hook],
        'quiet': True,
        'no_warnings': False,
        'no_color': True,
        'overwrites': True,
        'noplaylist': True,
        'retries': 10,
        'fragment_retries': 10,
        'extractor_retries': 5,
        'file_access_retries': 3,
        'socket_timeout': 30,
        'noprogress': True,
        'progress_with_newline': True,
    }

    apply_common_ydl_opts(ydl_opts)

    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=True)
            if info:
                filepath = ydl.prepare_filename(info)

                if os.path.exists(filepath):
                    print(f"COMPLETE:{filepath}", flush=True)
                    sys.exit(0)

                files = sorted(
                    [
                        os.path.join(output_dir, f)
                        for f in os.listdir(output_dir)
                        if os.path.isfile(os.path.join(output_dir, f))
                    ],
                    key=os.path.getmtime,
                    reverse=True
                )
                if files:
                    print(f"COMPLETE:{files[0]}", flush=True)
                else:
                    fatal("OUTPUT_NOT_FOUND", "Downloaded file not found")
    except KeyboardInterrupt:
        fatal("CANCELLED", "User cancelled")
    except Exception:
        fatal("DOWNLOAD_EXCEPTION", traceback.format_exc())


def main():
    parser = argparse.ArgumentParser(description='YT Downloader')
    subparsers = parser.add_subparsers(dest='command', required=True)

    p_info = subparsers.add_parser('info')
    p_info.add_argument('url')

    p_single = subparsers.add_parser('download_single')
    p_single.add_argument('url')
    p_single.add_argument('--format', required=True)
    p_single.add_argument('--output-dir', required=True)
    p_single.add_argument('--filename')

    args = parser.parse_args()

    if args.command == 'info':
        cmd_info(args)
    elif args.command == 'download_single':
        cmd_download_single(args)


if __name__ == '__main__':
    main()