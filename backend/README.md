# Backend Auth + Imaging Flow

This backend now supports:

- SQLite-based signup/login (`/auth/signup`, `/auth/login`)
- Web-compatible auth (`/login`, `/register`)
- Token-protected analysis endpoints
- Dedicated panoramic endpoint for mandibular canal tracing (`/analyze-panoramic`)
- Web dashboard APIs: cases, profile, settings, team, billing, chat
- CBCT series reconstruction from:
  - single `.dcm` (legacy)
  - `.zip` archive containing many `.dcm` slices
  - folder containing many `.dcm` slices (via `load_cbct_series` helper)

## Input Routing

- `POST /analyze-jaw`
  - `.zip` -> CBCT series reconstruction
  - single `.dcm` -> existing single-file loader (legacy behavior kept)
- `POST /analyze-panoramic`
  - `.jpg`/`.jpeg`/`.png` -> panoramic projection loader
  - single `.dcm` -> existing DICOM loader

Panoramic processing functions remain unchanged (`load_image_projection`, `generate_opg`, canal tracing pipeline).

## Run

```bash
cd backend
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn main:app --reload --port 8000
```

## API Notes

1. Sign up or log in to get a bearer token.
2. Pass token as `Authorization: Bearer <token>` for:
   - `POST /analyze-jaw`
   - `POST /analyze-panoramic`
   - `POST /measure`
   - `GET/POST /cases`, `GET /cases/{id}`, `PUT /cases/{id}/status`, `POST /cases/{id}/upload`
   - `POST /analysis/run/{case_id}`, `GET /analysis/result/{case_id}`
   - `GET/PUT /user`, `GET/PUT /settings`, `GET/POST/DELETE /team`, `GET/POST /billing`
   - `POST /chat`

## Unified Client Compatibility

- Android app uses:
  - `/auth/signup`, `/auth/login`, `/analyze-jaw`, `/analyze-panoramic`, `/measure`
- Web app uses:
  - `/login`, `/register`, `/cases*`, `/analysis/run*`, `/analysis/result*`, `/user`, `/chat`, `/settings`, `/team`, `/billing`

Both clients now run against this single `backend/` service.

## Tests

```bash
cd backend
python -m unittest discover

# targeted CBCT series loader test
python -m unittest test_cbct_series_loader.py
```

## Quick Loader Harness

```bash
cd backend
python run_cbct_series_loader.py /path/to/cbct-study.zip
python run_cbct_series_loader.py /path/to/cbct-study-folder
```

