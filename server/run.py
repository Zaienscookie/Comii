"""Development entrypoint: python run.py

Starts both ports (WebUI 8000 + data API 8001) exactly like the systemd unit.
"""

from __future__ import annotations

from comii.serve import main

if __name__ == "__main__":
    main()
