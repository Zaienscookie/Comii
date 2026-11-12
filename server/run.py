"""Development entrypoint: python run.py"""

from __future__ import annotations

import uvicorn

from comii.config import settings


def main() -> None:
    uvicorn.run(
        "comii.main:app",
        host=settings.host,
        port=settings.port,
        log_level=settings.log_level,
        reload=False,
    )


if __name__ == "__main__":
    main()
