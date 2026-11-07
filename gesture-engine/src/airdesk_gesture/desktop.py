"""User-operated PC camera panel. Importing this module never opens a camera."""
from __future__ import annotations

import json
import queue
import time
from pathlib import Path
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

from .desktop_runtime import DesktopRuntime, SetupError, find_model, read_app_token


class DesktopApp:
    def __init__(self, root: tk.Tk, project: Path):
        self.root = root
        self.project = project
        self.events: queue.Queue[tuple[str, str]] = queue.Queue()
        self.runtime = DesktopRuntime(project, lambda kind, text: self.events.put((kind, text)))
        self.photo = None
        self.was_busy = False
        self.closing_at: float | None = None
        self.last_error = ""
        self.settings_path = project / "gesture-engine" / ".venv" / "airdesk-panel.json"
        settings = {}
        try:
            settings = json.loads(self.settings_path.read_text(encoding="utf-8"))
            if not isinstance(settings, dict):
                settings = {}
        except (OSError, ValueError):
            pass
        try:
            model = str(find_model(project))
        except SetupError:
            model = ""
        remembered_model = settings.get("model")
        if isinstance(remembered_model, str) and Path(remembered_model).is_file():
            model = remembered_model
        device = settings.get("device", 0)
        if type(device) is not int or not 0 <= device <= 31:
            device = 0
        self.device = tk.StringVar(value=str(device))
        self.model = tk.StringVar(value=model)
        self.status = tk.StringVar(value="카메라 번호를 선택하고 시작을 누르세요.")
        self.connection = tk.StringVar(value="앱 연결 안 됨")
        self.tracking = tk.StringVar(value="손 인식 대기")
        self.root.title("AirDesk · PC 카메라")
        self.root.geometry("1040x790")
        self.root.minsize(820, 650)
        self.root.configure(bg="#081628")
        style = ttk.Style(root)
        style.theme_use("clam")
        style.configure("TFrame", background="#081628")
        style.configure("TLabel", background="#081628", foreground="#dce7ff", font=("맑은 고딕", 10))
        style.configure("TButton", font=("맑은 고딕", 10), padding=9)
        body = ttk.Frame(root, padding=22)
        body.pack(fill="both", expand=True)
        ttk.Label(body, text="AirDesk", font=("Segoe UI", 27, "bold"), foreground="#c7bcff").pack(anchor="w")
        ttk.Label(body, text="PC에서 카메라를 선택하고 영상을 보세요. Android 앱에서는 ‘진단 연결’을 누르세요.").pack(anchor="w", pady=(3, 15))
        controls = ttk.Frame(body)
        controls.pack(fill="x")
        ttk.Label(controls, text="카메라 번호").pack(side="left", padx=(0, 9))
        self.camera_combo = ttk.Combobox(controls, textvariable=self.device, values=[str(n) for n in range(32)], width=5, state="readonly")
        self.camera_combo.pack(side="left")
        self.start_button = ttk.Button(controls, text="시작 · 카메라 + 앱 연결", command=self.start)
        self.start_button.pack(side="left", padx=12)
        self.stop_button = ttk.Button(controls, text="중지", command=self.stop, state="disabled")
        self.stop_button.pack(side="left")
        self.model_button = ttk.Button(controls, text="모델 파일 선택", command=self.choose_model)
        self.model_button.pack(side="right")
        ttk.Label(body, text="다른 캠이 나오면 중지 → 번호 변경 → 다시 시작하세요. 보통 0 또는 1입니다.").pack(anchor="w", pady=(9, 4))
        try:
            read_app_token(project)
            token_status = "앱 연결 설정: 기존 토큰 자동 불러옴"
        except SetupError:
            token_status = "앱 연결 설정: Android 개발 토큰 설정이 필요합니다"
        ttk.Label(body, text=token_status, foreground="#9eb1d7").pack(anchor="w")
        self.preview = tk.Label(body, text="시작을 누르면 이곳에 실시간 웹캠 영상과 손 관절이 표시됩니다.",
                                bg="#162842", fg="#b9cce6", font=("맑은 고딕", 12))
        self.preview.pack(fill="both", expand=True, pady=14)
        ttk.Label(body, textvariable=self.status, wraplength=960).pack(anchor="w")
        ttk.Label(body, textvariable=self.connection, foreground="#a78bfa").pack(anchor="w", pady=(5, 0))
        ttk.Label(body, textvariable=self.tracking).pack(anchor="w", pady=(5, 0))
        ttk.Label(body, text="영상은 PC에만 표시 · 녹화 없음 · Android에는 좌표/제스처만 전송 · 실제 PC 입력 비활성",
                  foreground="#ffca7b").pack(anchor="w", pady=(12, 0))
        root.protocol("WM_DELETE_WINDOW", self.close)
        root.after(50, self.poll)

    def choose_model(self):
        path = filedialog.askopenfilename(title="MediaPipe 손 인식 모델 선택", filetypes=[("MediaPipe model", "*.task")])
        if path:
            self.model.set(path)

    def start(self):
        self.last_error = ""
        try:
            device = int(self.device.get())
            self.runtime.start(device, Path(self.model.get()))
        except (SetupError, ValueError) as exc:
            messagebox.showerror("AirDesk 설정 확인", str(exc))
            return
        try:
            self.settings_path.write_text(json.dumps({"device": device, "model": self.model.get()}), encoding="utf-8")
        except OSError:
            pass
        self.was_busy = True
        self.start_button.configure(state="disabled")
        self.camera_combo.configure(state="disabled")
        self.model_button.configure(state="disabled")
        self.stop_button.configure(state="normal")
        self.status.set("카메라와 손 인식 모델을 시작하는 중입니다…")
        self.connection.set("앱 연결 준비 중")

    def stop(self):
        self.runtime.stop()
        self.stop_button.configure(state="disabled")
        self.status.set("카메라와 연결을 종료하는 중입니다…")

    def close(self):
        self.closing_at = time.monotonic()
        self.stop()

    def poll(self):
        while True:
            try:
                kind, text = self.events.get_nowait()
            except queue.Empty:
                break
            if kind == "tracking":
                self.tracking.set(text)
                self.status.set("실시간 미리보기 중 · 카메라를 바꾸려면 중지 후 번호를 변경하세요.")
            elif kind == "error":
                self.last_error = text
                self.status.set(text)
            elif kind == "server":
                self.connection.set({
                    "listening": "연결 준비 완료 · Android 앱의 ‘진단 연결’을 누르세요.",
                    "connected": "Android 앱 연결됨 · 좌표/제스처 전송 중",
                    "disconnected": "앱 연결 종료 · 다시 ‘진단 연결’을 누르세요.",
                    "rejected": "앱 토큰 불일치 · Android Studio에서 앱을 다시 빌드·실행해 주세요.",
                }.get(text, text))
        try:
            ppm = self.runtime.frames.get_nowait()
        except queue.Empty:
            pass
        else:
            self.photo = tk.PhotoImage(data=ppm, format="PPM")
            self.preview.configure(image=self.photo, text="")
        if self.was_busy and not self.runtime.busy:
            self.was_busy = False
            self.start_button.configure(state="normal")
            self.camera_combo.configure(state="readonly")
            self.model_button.configure(state="normal")
            self.stop_button.configure(state="disabled")
            self.preview.configure(image="", text="카메라가 중지됐습니다. 번호를 선택하고 다시 시작할 수 있습니다.")
            self.photo = None
            self.status.set(self.last_error or "중지됨 · 카메라 번호와 모델 선택은 다음 실행에도 기억됩니다.")
            self.connection.set("앱 연결 안 됨")
            self.tracking.set("손 인식 대기")
        if self.closing_at is not None and (not self.runtime.busy or time.monotonic() - self.closing_at > 3):
            self.root.destroy()
            return
        self.root.after(50, self.poll)


def main():
    project = Path(__file__).resolve().parents[3]
    root = tk.Tk()
    DesktopApp(root, project)
    root.mainloop()


if __name__ == "__main__":
    main()
