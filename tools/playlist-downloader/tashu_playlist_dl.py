"""Tashu - téléchargeur de playlists YouTube Music.

Interface graphique autour de yt-dlp, qui reprend exactement cette commande :

    python -m yt_dlp -f "ba/best" -x --audio-format mp3 --audio-quality 0 --embed-metadata
        --embed-thumbnail --convert-thumbnails jpg
        --ppa "ThumbnailsConvertor+ffmpeg_o:-vf crop=ih:ih"
        --parse-metadata "playlist_index:%(track_number)s"
        -o "%(playlist_index)s - %(title)s.%(ext)s" "LIEN" ; del *.webp

Prérequis : Python 3.9+, `pip install -U yt-dlp`, ffmpeg dans le PATH.
Lancement : double-clic sur lancer.bat, ou `python tashu_playlist_dl.py`.

N'utilisez cet outil que pour du contenu que vous avez le droit de télécharger.
"""

from __future__ import annotations

import os
import queue
import re
import shutil
import subprocess
import sys
import threading
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox, ttk

APP_TITLE = "Tashu - Playlist vers MP3"
CONFIG = Path.home() / ".tashu_playlist_dl.txt"  # mémorise le dernier dossier choisi

RE_ITEM = re.compile(r"\[download\] Downloading item (\d+) of (\d+)")
RE_PERCENT = re.compile(r"\[download\]\s+([\d.]+)%")
RE_DEST = re.compile(r"\[(?:ExtractAudio|download)\] Destination: (.+)")
RE_PLAYLIST = re.compile(r"\[download\] Downloading playlist: (.+)")


def find_ffmpeg() -> str | None:
    """ffmpeg du PATH, ou celui installé par winget (pas encore dans le PATH de cette session)."""
    found = shutil.which("ffmpeg")
    if found:
        return found
    winget = Path(os.environ.get("LOCALAPPDATA", "")) / "Microsoft" / "WinGet"
    candidates = [winget / "Links" / "ffmpeg.exe", Path(os.environ.get("ProgramFiles", "")) / "WinGet" / "Links" / "ffmpeg.exe"]
    candidates += sorted((winget / "Packages").glob("Gyan.FFmpeg*/*/bin/ffmpeg.exe"), reverse=True)
    return next((str(c) for c in candidates if c.exists()), None)


def yt_dlp_available() -> bool:
    try:
        import yt_dlp  # noqa: F401
        return True
    except ImportError:
        return False


def build_command(url: str, subfolder: bool, ffmpeg: str | None) -> list[str]:
    template = "%(playlist_index)s - %(title)s.%(ext)s"
    if subfolder:
        template = "%(playlist_title)s/" + template
    cmd = [
        sys.executable, "-m", "yt_dlp",
        "-f", "ba/best",
        "-x", "--audio-format", "mp3", "--audio-quality", "0",
        "--embed-metadata", "--embed-thumbnail", "--convert-thumbnails", "jpg",
        "--ppa", "ThumbnailsConvertor+ffmpeg_o:-vf crop=ih:ih",
        "--parse-metadata", "playlist_index:%(track_number)s",
        "-o", template,
        "--newline",            # une ligne par mise à jour de progression (lisible par l'interface)
        "--no-colors",
        "--ignore-errors",      # une vidéo indisponible ne bloque pas toute la playlist
    ]
    if ffmpeg:
        cmd += ["--ffmpeg-location", ffmpeg]
    cmd.append(url)
    return cmd


class App(tk.Tk):
    def __init__(self) -> None:
        super().__init__()
        self.title(APP_TITLE)
        self.geometry("720x520")
        self.minsize(560, 420)
        self.process: subprocess.Popen[str] | None = None
        self.lines: queue.Queue[str | None] = queue.Queue()
        self.out_dir = tk.StringVar(value=self._load_dir())
        self.url = tk.StringVar()
        self.subfolder = tk.BooleanVar(value=True)
        self.status = tk.StringVar(value="Collez le lien d'une playlist YouTube Music.")
        self.track = tk.StringVar(value="")
        self._build()
        self.after(100, self._poll)

    # ---------- interface ----------
    def _build(self) -> None:
        pad = {"padx": 12, "pady": 6}
        frm = ttk.Frame(self)
        frm.pack(fill="both", expand=True)
        frm.columnconfigure(1, weight=1)

        ttk.Label(frm, text="Lien de la playlist").grid(row=0, column=0, sticky="w", **pad)
        url_entry = ttk.Entry(frm, textvariable=self.url)
        url_entry.grid(row=0, column=1, columnspan=2, sticky="ew", **pad)
        url_entry.focus_set()

        ttk.Label(frm, text="Dossier de destination").grid(row=1, column=0, sticky="w", **pad)
        ttk.Entry(frm, textvariable=self.out_dir).grid(row=1, column=1, sticky="ew", **pad)
        ttk.Button(frm, text="Parcourir…", command=self._choose_dir).grid(row=1, column=2, **pad)

        ttk.Checkbutton(
            frm, text="Créer un sous-dossier au nom de la playlist", variable=self.subfolder,
        ).grid(row=2, column=1, sticky="w", **pad)

        btns = ttk.Frame(frm)
        btns.grid(row=3, column=0, columnspan=3, sticky="ew", **pad)
        self.start_btn = ttk.Button(btns, text="Télécharger", command=self._start)
        self.start_btn.pack(side="left")
        self.stop_btn = ttk.Button(btns, text="Arrêter", command=self._stop, state="disabled")
        self.stop_btn.pack(side="left", padx=8)
        ttk.Button(btns, text="Ouvrir le dossier", command=self._open_dir).pack(side="right")

        ttk.Label(frm, textvariable=self.status, font=("Segoe UI", 10, "bold")).grid(row=4, column=0, columnspan=3, sticky="w", **pad)
        self.overall = ttk.Progressbar(frm, mode="determinate")
        self.overall.grid(row=5, column=0, columnspan=3, sticky="ew", padx=12)
        ttk.Label(frm, textvariable=self.track).grid(row=6, column=0, columnspan=3, sticky="w", **pad)
        self.current = ttk.Progressbar(frm, mode="determinate", maximum=100)
        self.current.grid(row=7, column=0, columnspan=3, sticky="ew", padx=12)

        log_frame = ttk.Frame(frm)
        log_frame.grid(row=8, column=0, columnspan=3, sticky="nsew", padx=12, pady=(10, 12))
        frm.rowconfigure(8, weight=1)
        self.log = tk.Text(log_frame, height=10, wrap="none", font=("Consolas", 9), state="disabled")
        scroll = ttk.Scrollbar(log_frame, command=self.log.yview)
        self.log.configure(yscrollcommand=scroll.set)
        self.log.pack(side="left", fill="both", expand=True)
        scroll.pack(side="right", fill="y")

        self.protocol("WM_DELETE_WINDOW", self._on_close)

    def _choose_dir(self) -> None:
        d = filedialog.askdirectory(initialdir=self.out_dir.get() or str(Path.home()))
        if d:
            self.out_dir.set(d)

    def _open_dir(self) -> None:
        d = self.out_dir.get()
        if d and Path(d).is_dir():
            os.startfile(d) if sys.platform == "win32" else subprocess.Popen(["xdg-open", d])

    def _append(self, text: str) -> None:
        self.log.configure(state="normal")
        self.log.insert("end", text + "\n")
        self.log.see("end")
        self.log.configure(state="disabled")

    # ---------- téléchargement ----------
    def _start(self) -> None:
        url = self.url.get().strip()
        out = Path(self.out_dir.get().strip() or ".")
        if not url.startswith(("http://", "https://")):
            messagebox.showwarning(APP_TITLE, "Collez un lien de playlist commençant par https://")
            return
        if not yt_dlp_available():
            messagebox.showerror(APP_TITLE, "yt-dlp n'est pas installé.\n\nDans un terminal :\npython -m pip install -U yt-dlp")
            return
        ffmpeg = find_ffmpeg()
        if not ffmpeg:
            messagebox.showerror(APP_TITLE, "ffmpeg est introuvable.\n\nDans un terminal :\nwinget install Gyan.FFmpeg")
            return
        try:
            out.mkdir(parents=True, exist_ok=True)
        except OSError as e:
            messagebox.showerror(APP_TITLE, f"Dossier inutilisable :\n{e}")
            return
        self._save_dir(str(out))

        cmd = build_command(url, self.subfolder.get(), ffmpeg)
        self._append("> " + subprocess.list2cmdline(cmd))
        self.status.set("Démarrage…")
        self.track.set("")
        self.overall.configure(value=0, maximum=1)
        self.current.configure(value=0)
        self.start_btn.configure(state="disabled")
        self.stop_btn.configure(state="normal")

        flags = subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0
        self.process = subprocess.Popen(
            cmd, cwd=out, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace", bufsize=1, creationflags=flags,
        )
        threading.Thread(target=self._reader, args=(self.process, out), daemon=True).start()

    def _reader(self, proc: subprocess.Popen[str], out: Path) -> None:
        assert proc.stdout is not None
        for line in proc.stdout:
            self.lines.put(line.rstrip())
        proc.wait()
        removed = self._delete_webp(out)
        self.lines.put(f"__DONE__:{proc.returncode}:{removed}")

    @staticmethod
    def _delete_webp(out: Path) -> int:
        """Équivalent de `del *.webp` (y compris dans les sous-dossiers de playlist)."""
        count = 0
        for f in out.rglob("*.webp"):
            try:
                f.unlink()
                count += 1
            except OSError:
                pass
        return count

    def _poll(self) -> None:
        try:
            while True:
                line = self.lines.get_nowait()
                if line is None:
                    continue
                if line.startswith("__DONE__:"):
                    _, code, removed = line.split(":")
                    self._finished(int(code), int(removed))
                    continue
                self._parse(line)
        except queue.Empty:
            pass
        self.after(100, self._poll)

    def _parse(self, line: str) -> None:
        if (m := RE_PLAYLIST.search(line)):
            self.status.set(f"Playlist : {m.group(1)}")
        if (m := RE_ITEM.search(line)):
            i, n = int(m.group(1)), int(m.group(2))
            self.overall.configure(maximum=n, value=i - 1)
            self.status.set(f"Morceau {i} sur {n}")
            self.current.configure(value=0)
        if (m := RE_DEST.search(line)):
            self.track.set(Path(m.group(1)).stem)
        if (m := RE_PERCENT.search(line)):
            self.current.configure(value=float(m.group(1)))
            return  # pas de ligne de log pour chaque pourcentage
        self._append(line)

    def _finished(self, code: int, removed: int) -> None:
        self.overall.configure(value=self.overall.cget("maximum"))
        self.current.configure(value=100 if code == 0 else self.current.cget("value"))
        self.start_btn.configure(state="normal")
        self.stop_btn.configure(state="disabled")
        stopped = self.process is None
        self.process = None
        if stopped:
            self.status.set("Téléchargement arrêté.")
        elif code == 0:
            self.status.set("Terminé. Les MP3 sont dans le dossier de destination.")
        else:
            self.status.set("Terminé avec des erreurs : certains morceaux n'ont pas pu être téléchargés (voir le journal).")
        self._append(f"{removed} fichier(s) .webp supprimé(s).")

    def _stop(self) -> None:
        proc = self.process
        if proc and proc.poll() is None:
            self.process = None
            proc.terminate()
            self.status.set("Arrêt en cours…")

    def _on_close(self) -> None:
        if self.process and self.process.poll() is None:
            if not messagebox.askyesno(APP_TITLE, "Un téléchargement est en cours. L'arrêter et quitter ?"):
                return
            self._stop()
        self.destroy()

    # ---------- préférences ----------
    @staticmethod
    def _load_dir() -> str:
        try:
            return CONFIG.read_text(encoding="utf-8").strip() or str(Path.home() / "Music")
        except OSError:
            return str(Path.home() / "Music")

    @staticmethod
    def _save_dir(d: str) -> None:
        try:
            CONFIG.write_text(d, encoding="utf-8")
        except OSError:
            pass


if __name__ == "__main__":
    App().mainloop()
