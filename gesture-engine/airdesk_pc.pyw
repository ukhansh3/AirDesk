"""Double-click bootstrap: use this checkout's installed gesture-engine package."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "src"))

try:
    from airdesk_gesture.desktop import main
    main()
except Exception:
    import tkinter as tk
    from tkinter import messagebox
    root = tk.Tk()
    root.withdraw()
    messagebox.showerror("AirDesk 실행 오류", "PC 실행 화면을 열지 못했습니다. gesture-engine의 Python 가상환경과 Tkinter 설치를 확인해 주세요.")
    root.destroy()
