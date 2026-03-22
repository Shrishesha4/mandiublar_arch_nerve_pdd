# Architecture and Integration Alignment

## Current Topology
- Mobile app (`app/`): Kotlin + Compose, network analysis flow.
- Website frontend (`website/frontend/`): React + Vite.
- Unified backend (`backend/`): web CRUD/auth/settings + CBCT/panoramic analysis endpoints.

## Refactor Implemented

### Web (Frontend)
Introduced a clean API abstraction layer:
- `website/frontend/src/config/env.js`
- `website/frontend/src/services/httpClient.js`
- `website/frontend/src/services/authStore.js`
- `website/frontend/src/services/authService.js`
- `website/frontend/src/services/analysisService.js`
- `website/frontend/src/services/profileService.js`
- `website/frontend/src/services/chatService.js`

Migrated scattered API calls to service layer in:
- Login, Upload, Analysis, Reports, Profile, ChatWidget, RequireAuth, Sidebar.

Benefits:
- Environment-based API base URL
- Shared auth header behavior
- Unified JSON parsing and API error handling
- Cleaner component/page concerns

### Android
Implemented production foundations:
- Splash screen wiring in MainActivity + theme.
- Room local schema and repository:
  - `PatientEntity` (demographics)
  - `MedicalReportEntity` (report metadata)
  - indexed Room tables, DAOs, repository.
- Offline-first storage hook in `PlanningViewModel`:
  - stores patient and generated report path locally.
- PDF module expanded:
  - individual report generation
  - combined report generation entrypoints.

## Data Model Alignment

### Backend Case/User fields (web backend)
- User profile: `name`, `email`, `phone`, `practice_name`, `bio`, `specialty`
- Case: `case_id`, `fname`, `lname`, `patient_age`, `tooth_number`, `complaint`, `case_type`, `status`

### Mobile local fields
- PatientEntity:
  - `remote_patient_id`, `first_name`, `last_name`, `dob`, `gender`, `phone`, `email`
- MedicalReportEntity:
  - `session_id`, `workflow`, `scan_region`, `bone_width_mm`, `bone_height_mm`, `safe_height_mm`, `nerve_detected`, `recommendation`, `pdf_path`

Alignment strategy:
- `session_id` is treated as a stable remote correlation key.
- Keep `remote_patient_id` nullable to support local-only/offline patient creation.

## API Layer Standardization Recommendation
- Converge to one backend gateway with explicit versioning (`/api/v1`).
- Keep domain routers:
  - `/auth`
  - `/patients`
  - `/reports`
  - `/analysis`
  - `/settings`
- Standardize auth response shape and error schema across web/mobile consumers.

## Caching and Offline Strategy
- Mobile:
  - Room as source of truth for local patient/report index.
  - WorkManager background sync queue for pending uploads/results.
- Web:
  - Cache recent lists in memory + optional IndexedDB (for unstable connections).
  - Retry and backoff in API client for idempotent GETs.

## Performance Optimizations
- Analysis endpoints should be async job-based for large inputs (queue + status polling).
- Avoid blocking I/O in request handlers for uploads and heavy preprocessing.
- Move file metadata and report summaries into paginated endpoints.

## Optional Enhancements
- Role-based access model (`doctor`, `assistant`, `admin`) in auth claims.
- Centralized structured logging and trace IDs across frontend/backend/mobile.
- Analytics hooks for upload lifecycle and analysis latency.
