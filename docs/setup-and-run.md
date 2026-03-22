# Setup and Run Instructions

## Web Frontend
Path: `website/frontend`

1. Install dependencies:
   - `npm install`
2. Create env file:
   - copy `.env.example` to `.env.local`
3. Configure API base if needed:
   - `VITE_API_BASE_URL=/api` (default via proxy)
4. Run dev server:
   - `npm run dev`

## Unified Backend (Web + Android + CBCT)
Path: `backend`

1. Create venv and activate.
2. Install dependencies:
   - `pip install -r requirements.txt`
3. Run API:
   - `uvicorn main:app --reload --port 8000`

## Android App
Path: `app`

1. Open project in Android Studio.
2. Ensure backend URL in:
   - `app/src/main/java/com/s4/belsson/data/api/ImplantApiService.kt`
3. Build and run:
   - `./gradlew :app:assembleDebug`

## Production Notes
- Use distinct `.env` values per environment (dev/stage/prod).
- Do not allow wildcard CORS in production.
- Replace in-memory/session assumptions with persistent token lifecycle rules.
- Configure HTTPS and secure cookie/token handling for web sessions.
