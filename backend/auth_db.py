from __future__ import annotations

import base64
import hashlib
import hmac
import json
import secrets
import sqlite3
import threading
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from pathlib import Path


class AuthDatabase:
    """SQLite-backed user and session store for simple API authentication."""

    def __init__(self, db_path: str | None = None):
        default_path = Path(__file__).resolve().parent / "belsson_auth.db"
        self._db_path = str(default_path if db_path is None else db_path)
        self._lock = threading.Lock()
        self._iterations = 120_000
        self.init_db()

    def init_db(self) -> None:
        with self._connect() as conn:
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS users (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    email TEXT NOT NULL UNIQUE,
                    password_hash TEXT NOT NULL,
                    password_salt TEXT NOT NULL,
                    name TEXT,
                    phone TEXT,
                    practice_name TEXT,
                    bio TEXT,
                    specialty TEXT,
                    created_at TEXT NOT NULL
                )
                """
            )
            self._ensure_user_columns(conn)
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS sessions (
                    token TEXT PRIMARY KEY,
                    user_id INTEGER NOT NULL,
                    created_at TEXT NOT NULL,
                    expires_at TEXT NOT NULL,
                    FOREIGN KEY(user_id) REFERENCES users(id)
                )
                """
            )
            conn.execute(
                "CREATE INDEX IF NOT EXISTS idx_sessions_user_id ON sessions(user_id)"
            )
            conn.execute(
                "CREATE INDEX IF NOT EXISTS idx_users_email ON users(email)"
            )
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS user_settings (
                    user_id INTEGER PRIMARY KEY,
                    theme TEXT DEFAULT 'system',
                    language TEXT DEFAULT 'en',
                    notifications TEXT DEFAULT '{}',
                    FOREIGN KEY(user_id) REFERENCES users(id)
                )
                """
            )
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS billing (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id INTEGER NOT NULL UNIQUE,
                    plan_name TEXT,
                    status TEXT DEFAULT 'active',
                    next_billing_date TEXT,
                    card_last4 TEXT,
                    billing_history TEXT DEFAULT '[]',
                    FOREIGN KEY(user_id) REFERENCES users(id)
                )
                """
            )
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS team_members (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    owner_id INTEGER NOT NULL,
                    name TEXT NOT NULL,
                    email TEXT NOT NULL,
                    role TEXT NOT NULL,
                    FOREIGN KEY(owner_id) REFERENCES users(id)
                )
                """
            )
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS cases (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id INTEGER NOT NULL,
                    case_id TEXT NOT NULL UNIQUE,
                    fname TEXT NOT NULL,
                    lname TEXT NOT NULL,
                    tooth_number TEXT,
                    complaint TEXT,
                    patient_age INTEGER,
                    case_type TEXT,
                    status TEXT DEFAULT 'Pending Analysis',
                    created_at TEXT NOT NULL,
                    FOREIGN KEY(user_id) REFERENCES users(id)
                )
                """
            )
            conn.execute(
                "CREATE INDEX IF NOT EXISTS idx_cases_user_id ON cases(user_id)"
            )
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS cbct_files (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    case_id INTEGER NOT NULL,
                    filename TEXT NOT NULL,
                    file_path TEXT NOT NULL,
                    uploaded_at TEXT NOT NULL,
                    FOREIGN KEY(case_id) REFERENCES cases(id)
                )
                """
            )
            conn.execute(
                "CREATE INDEX IF NOT EXISTS idx_cbct_files_case_id ON cbct_files(case_id)"
            )
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS analysis_results (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    case_id INTEGER NOT NULL UNIQUE,
                    arch_curve_data TEXT,
                    nerve_path_data TEXT,
                    bone_width_36 TEXT,
                    bone_height TEXT,
                    nerve_distance TEXT,
                    safe_implant_length TEXT,
                    clinical_report TEXT,
                    patient_explanation TEXT,
                    created_at TEXT NOT NULL,
                    FOREIGN KEY(case_id) REFERENCES cases(id)
                )
                """
            )
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS login_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id INTEGER NOT NULL,
                    login_time TEXT NOT NULL,
                    ip_address TEXT,
                    user_agent TEXT,
                    status TEXT DEFAULT 'Success',
                    FOREIGN KEY(user_id) REFERENCES users(id)
                )
                """
            )
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS activity_logs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id INTEGER NOT NULL,
                    action TEXT NOT NULL,
                    details TEXT,
                    timestamp TEXT NOT NULL,
                    FOREIGN KEY(user_id) REFERENCES users(id)
                )
                """
            )
            conn.commit()

    def create_user(
        self,
        email: str,
        password: str,
        *,
        name: str | None = None,
        phone: str | None = None,
        practice_name: str | None = None,
    ) -> dict:
        normalized = email.strip().lower()
        if len(password) < 6:
            raise ValueError("Password must be at least 6 characters.")
        if not normalized:
            raise ValueError("Email is required.")

        salt = secrets.token_bytes(16)
        pwd_hash = self._hash_password(password, salt)
        now = self._utc_now_iso()
        display_name = name or "Test Doctor"
        phone_value = phone or "N/A"
        practice_value = practice_name or "Private Practice"

        with self._connect() as conn:
            try:
                cursor = conn.execute(
                    """
                    INSERT INTO users(
                        email, password_hash, password_salt, name, phone, practice_name, created_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        normalized,
                        pwd_hash,
                        base64.b64encode(salt).decode("ascii"),
                        display_name,
                        phone_value,
                        practice_value,
                        now,
                    ),
                )
                user_id = int(cursor.lastrowid)
                conn.execute(
                    """
                    INSERT INTO user_settings(user_id, theme, language, notifications)
                    VALUES (?, 'system', 'en', '{}')
                    ON CONFLICT(user_id) DO NOTHING
                    """,
                    (user_id,),
                )
                conn.commit()
            except sqlite3.IntegrityError as exc:
                raise ValueError("An account with this email already exists.") from exc

        return {
            "id": user_id,
            "email": normalized,
            "name": display_name,
            "phone": phone_value,
            "practice_name": practice_value,
        }

    def authenticate(self, email: str, password: str) -> dict | None:
        normalized = email.strip().lower()
        user = self.get_user_by_email(normalized)
        if user is None:
            return None

        salt = base64.b64decode(user["password_salt"].encode("ascii"))
        expected = user["password_hash"]
        supplied = self._hash_password(password, salt)
        if not hmac.compare_digest(expected, supplied):
            return None

        return {
            "id": user["id"],
            "email": user["email"],
            "name": user.get("name") or "Test Doctor",
            "phone": user.get("phone") or "N/A",
            "practice_name": user.get("practice_name") or "Private Practice",
        }

    def create_session(self, user_id: int, ttl_hours: int = 24) -> str:
        token = secrets.token_urlsafe(32)
        now = datetime.now(timezone.utc)
        expires_at = now + timedelta(hours=ttl_hours)

        with self._connect() as conn:
            conn.execute(
                """
                INSERT INTO sessions(token, user_id, created_at, expires_at)
                VALUES (?, ?, ?, ?)
                """,
                (token, user_id, now.isoformat(), expires_at.isoformat()),
            )
            conn.commit()

        return token

    def get_user_by_token(self, token: str) -> dict | None:
        if not token:
            return None

        with self._connect() as conn:
            row = conn.execute(
                """
                SELECT u.id, u.email, s.expires_at, u.name, u.phone, u.practice_name
                FROM sessions s
                JOIN users u ON u.id = s.user_id
                WHERE s.token = ?
                """,
                (token,),
            ).fetchone()

            if row is None:
                return None

            expires_at = datetime.fromisoformat(row[2])
            if expires_at <= datetime.now(timezone.utc):
                conn.execute("DELETE FROM sessions WHERE token = ?", (token,))
                conn.commit()
                return None

            return {
                "id": int(row[0]),
                "email": row[1],
                "name": row[3] if len(row) > 3 else None,
                "phone": row[4] if len(row) > 4 else None,
                "practice_name": row[5] if len(row) > 5 else None,
            }

    def get_user_by_email(self, email: str) -> dict | None:
        with self._connect() as conn:
            row = conn.execute(
                """
                  SELECT id, email, password_hash, password_salt, created_at,
                      name, phone, practice_name, bio, specialty
                FROM users
                WHERE email = ?
                """,
                (email,),
            ).fetchone()

        if row is None:
            return None

        return {
            "id": int(row[0]),
            "email": row[1],
            "password_hash": row[2],
            "password_salt": row[3],
            "created_at": row[4],
            "name": row[5],
            "phone": row[6],
            "practice_name": row[7],
            "bio": row[8],
            "specialty": row[9],
        }

    def get_user_by_id(self, user_id: int) -> dict | None:
        with self._connect() as conn:
            row = conn.execute(
                """
                SELECT id, email, name, phone, practice_name, bio, specialty, created_at
                FROM users
                WHERE id = ?
                """,
                (user_id,),
            ).fetchone()
        if row is None:
            return None
        return {
            "id": int(row[0]),
            "email": row[1],
            "name": row[2],
            "phone": row[3],
            "practice_name": row[4],
            "bio": row[5],
            "specialty": row[6],
            "created_at": row[7],
        }

    def update_user_profile(self, user_id: int, profile: dict) -> dict:
        with self._connect() as conn:
            conn.execute(
                """
                UPDATE users
                SET name = ?, email = ?, phone = ?, practice_name = ?, bio = ?, specialty = ?
                WHERE id = ?
                """,
                (
                    profile.get("name"),
                    profile.get("email", "").strip().lower(),
                    profile.get("phone"),
                    profile.get("practice_name"),
                    profile.get("bio"),
                    profile.get("specialty"),
                    user_id,
                ),
            )
            conn.commit()
        updated = self.get_user_by_id(user_id)
        if updated is None:
            raise ValueError("User not found")
        return updated

    def get_settings(self, user_id: int) -> dict:
        with self._connect() as conn:
            row = conn.execute(
                """
                SELECT theme, language, notifications
                FROM user_settings
                WHERE user_id = ?
                """,
                (user_id,),
            ).fetchone()
            if row is None:
                conn.execute(
                    """
                    INSERT INTO user_settings(user_id, theme, language, notifications)
                    VALUES (?, 'system', 'en', '{}')
                    """,
                    (user_id,),
                )
                conn.commit()
                return {"theme": "system", "language": "en", "notifications": {}}
        return {
            "theme": row[0] or "system",
            "language": row[1] or "en",
            "notifications": self._json_load(row[2], default={}),
        }

    def update_settings(self, user_id: int, data: dict) -> dict:
        existing = self.get_settings(user_id)
        theme = data.get("theme", existing["theme"])
        language = data.get("language", existing["language"])
        notifications = data.get("notifications", existing["notifications"])
        with self._connect() as conn:
            conn.execute(
                """
                INSERT INTO user_settings(user_id, theme, language, notifications)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(user_id)
                DO UPDATE SET theme = excluded.theme,
                              language = excluded.language,
                              notifications = excluded.notifications
                """,
                (user_id, theme, language, json.dumps(notifications)),
            )
            conn.commit()
        return {"theme": theme, "language": language, "notifications": notifications}

    def list_team_members(self, user_id: int) -> list[dict]:
        with self._connect() as conn:
            rows = conn.execute(
                """
                SELECT id, owner_id, name, email, role
                FROM team_members
                WHERE owner_id = ?
                ORDER BY id DESC
                """,
                (user_id,),
            ).fetchall()
        return [
            {"id": int(r[0]), "owner_id": int(r[1]), "name": r[2], "email": r[3], "role": r[4]}
            for r in rows
        ]

    def add_team_member(self, user_id: int, member: dict) -> dict:
        with self._connect() as conn:
            cur = conn.execute(
                """
                INSERT INTO team_members(owner_id, name, email, role)
                VALUES (?, ?, ?, ?)
                """,
                (user_id, member.get("name"), member.get("email"), member.get("role")),
            )
            conn.commit()
            member_id = int(cur.lastrowid)
        return {"id": member_id, "owner_id": user_id, **member}

    def remove_team_member(self, user_id: int, member_id: int) -> bool:
        with self._connect() as conn:
            cur = conn.execute(
                "DELETE FROM team_members WHERE id = ? AND owner_id = ?",
                (member_id, user_id),
            )
            conn.commit()
            return cur.rowcount > 0

    def get_billing(self, user_id: int) -> dict:
        with self._connect() as conn:
            row = conn.execute(
                """
                SELECT plan_name, status, next_billing_date, card_last4, billing_history
                FROM billing
                WHERE user_id = ?
                """,
                (user_id,),
            ).fetchone()
        if row is None:
            return {
                "plan_name": "Free Trial",
                "status": "active",
                "next_billing_date": "2026-03-01",
                "card_last4": "N/A",
                "billing_history": [],
            }
        return {
            "plan_name": row[0],
            "status": row[1],
            "next_billing_date": row[2],
            "card_last4": row[3],
            "billing_history": self._json_load(row[4], default=[]),
        }

    def update_billing(self, user_id: int, plan_name: str, card_last4: str) -> dict:
        existing = self.get_billing(user_id)
        history = existing.get("billing_history", [])
        history.insert(
            0,
            {
                "date": datetime.now().strftime("%b %d, %Y"),
                "desc": plan_name,
                "amount": "$499.00" if "enterprise" in plan_name.lower() else "$199.00",
                "status": "Paid",
            },
        )
        with self._connect() as conn:
            conn.execute(
                """
                INSERT INTO billing(user_id, plan_name, status, next_billing_date, card_last4, billing_history)
                VALUES (?, ?, 'active', ?, ?, ?)
                ON CONFLICT(user_id)
                DO UPDATE SET plan_name = excluded.plan_name,
                              card_last4 = excluded.card_last4,
                              billing_history = excluded.billing_history
                """,
                (
                    user_id,
                    plan_name,
                    existing.get("next_billing_date") or "2026-03-01",
                    card_last4,
                    json.dumps(history),
                ),
            )
            conn.commit()
        return self.get_billing(user_id)

    def list_cases(self, user_id: int) -> list[dict]:
        with self._connect() as conn:
            rows = conn.execute(
                """
                SELECT id, case_id, fname, lname, patient_age, tooth_number, complaint,
                       case_type, status, created_at
                FROM cases
                WHERE user_id = ?
                ORDER BY created_at DESC
                """,
                (user_id,),
            ).fetchall()
        return [self._case_row_to_dict(r) for r in rows]

    def create_case(self, user_id: int, payload: dict) -> dict:
        cid = payload.get("case_id")
        if not cid:
            with self._connect() as conn:
                count_row = conn.execute("SELECT COUNT(*) FROM cases").fetchone()
                cid = f"P-{1000 + int(count_row[0]) + 1}"

        created_at = self._utc_now_iso()
        with self._connect() as conn:
            cur = conn.execute(
                """
                INSERT INTO cases(
                    user_id, case_id, fname, lname, tooth_number, complaint, patient_age,
                    case_type, status, created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    user_id,
                    cid,
                    payload.get("fname"),
                    payload.get("lname"),
                    payload.get("tooth_number"),
                    payload.get("complaint"),
                    payload.get("patient_age"),
                    payload.get("case_type"),
                    payload.get("status") or "Pending Analysis",
                    created_at,
                ),
            )
            case_pk = int(cur.lastrowid)
            conn.commit()
            row = conn.execute(
                """
                SELECT id, case_id, fname, lname, patient_age, tooth_number, complaint,
                       case_type, status, created_at
                FROM cases
                WHERE id = ?
                """,
                (case_pk,),
            ).fetchone()
        return self._case_row_to_dict(row)

    def get_case(self, user_id: int, case_id: str) -> dict | None:
        with self._connect() as conn:
            row = conn.execute(
                """
                SELECT id, case_id, fname, lname, patient_age, tooth_number, complaint,
                       case_type, status, created_at
                FROM cases
                WHERE user_id = ? AND case_id = ?
                """,
                (user_id, case_id),
            ).fetchone()
            if row is None and str(case_id).isdigit():
                row = conn.execute(
                    """
                    SELECT id, case_id, fname, lname, patient_age, tooth_number, complaint,
                           case_type, status, created_at
                    FROM cases
                    WHERE user_id = ? AND id = ?
                    """,
                    (user_id, int(case_id)),
                ).fetchone()
        if row is None:
            return None
        return self._case_row_to_dict(row)

    def update_case_status(self, user_id: int, case_id: str, status_text: str) -> bool:
        with self._connect() as conn:
            cur = conn.execute(
                "UPDATE cases SET status = ? WHERE user_id = ? AND case_id = ?",
                (status_text, user_id, case_id),
            )
            if cur.rowcount == 0 and str(case_id).isdigit():
                cur = conn.execute(
                    "UPDATE cases SET status = ? WHERE user_id = ? AND id = ?",
                    (status_text, user_id, int(case_id)),
                )
            conn.commit()
            return cur.rowcount > 0

    def add_case_file(self, case_pk: int, filename: str, file_path: str) -> dict:
        uploaded_at = self._utc_now_iso()
        with self._connect() as conn:
            cur = conn.execute(
                """
                INSERT INTO cbct_files(case_id, filename, file_path, uploaded_at)
                VALUES (?, ?, ?, ?)
                """,
                (case_pk, filename, file_path, uploaded_at),
            )
            conn.commit()
            file_id = int(cur.lastrowid)
        return {
            "id": file_id,
            "case_id": case_pk,
            "filename": filename,
            "file_path": file_path,
            "uploaded_at": uploaded_at,
        }

    def save_case_analysis(self, case_pk: int, analysis: dict) -> dict:
        created_at = self._utc_now_iso()
        with self._connect() as conn:
            conn.execute(
                "DELETE FROM analysis_results WHERE case_id = ?",
                (case_pk,),
            )
            cur = conn.execute(
                """
                INSERT INTO analysis_results(
                    case_id, arch_curve_data, nerve_path_data, bone_width_36, bone_height,
                    nerve_distance, safe_implant_length, clinical_report,
                    patient_explanation, created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    case_pk,
                    json.dumps(analysis.get("arch_curve_data", [])),
                    json.dumps(analysis.get("nerve_path_data", [])),
                    str(analysis.get("bone_width_36", "")),
                    str(analysis.get("bone_height", "")),
                    str(analysis.get("nerve_distance", "")),
                    str(analysis.get("safe_implant_length", "")),
                    analysis.get("clinical_report"),
                    analysis.get("patient_explanation"),
                    created_at,
                ),
            )
            analysis_id = int(cur.lastrowid)
            conn.commit()
        return {
            "id": analysis_id,
            "case_id": case_pk,
            "created_at": created_at,
            **analysis,
        }

    def get_analysis_by_case(self, case_pk: int) -> dict | None:
        with self._connect() as conn:
            row = conn.execute(
                """
                SELECT id, case_id, arch_curve_data, nerve_path_data, bone_width_36,
                       bone_height, nerve_distance, safe_implant_length,
                       clinical_report, patient_explanation, created_at
                FROM analysis_results
                WHERE case_id = ?
                """,
                (case_pk,),
            ).fetchone()
        if row is None:
            return None
        return {
            "id": int(row[0]),
            "case_id": int(row[1]),
            "arch_curve_data": self._json_load(row[2], default=[]),
            "nerve_path_data": self._json_load(row[3], default=[]),
            "bone_width_36": row[4],
            "bone_height": row[5],
            "nerve_distance": row[6],
            "safe_implant_length": row[7],
            "clinical_report": row[8],
            "patient_explanation": row[9],
            "created_at": row[10],
        }

    def log_login(self, user_id: int, ip_address: str | None, user_agent: str | None, status_text: str = "Success") -> None:
        with self._connect() as conn:
            conn.execute(
                """
                INSERT INTO login_history(user_id, login_time, ip_address, user_agent, status)
                VALUES (?, ?, ?, ?, ?)
                """,
                (user_id, self._utc_now_iso(), ip_address, user_agent, status_text),
            )
            conn.commit()

    def log_activity(self, user_id: int, action: str, details: dict | None = None) -> None:
        with self._connect() as conn:
            conn.execute(
                """
                INSERT INTO activity_logs(user_id, action, details, timestamp)
                VALUES (?, ?, ?, ?)
                """,
                (user_id, action, json.dumps(details or {}), self._utc_now_iso()),
            )
            conn.commit()

    @contextmanager
    def _connect(self):
        # Serialize sqlite access in threaded server mode.
        self._lock.acquire()
        conn = sqlite3.connect(self._db_path)
        conn.row_factory = sqlite3.Row
        try:
            yield conn
        finally:
            conn.close()
            self._lock.release()

    def _hash_password(self, password: str, salt: bytes) -> str:
        digest = hashlib.pbkdf2_hmac(
            "sha256",
            password.encode("utf-8"),
            salt,
            self._iterations,
        )
        return base64.b64encode(digest).decode("ascii")

    @staticmethod
    def _utc_now_iso() -> str:
        return datetime.now(timezone.utc).isoformat()

    @staticmethod
    def _json_load(text: str | None, default):
        if not text:
            return default
        try:
            return json.loads(text)
        except Exception:
            return default

    @staticmethod
    def _case_row_to_dict(row) -> dict:
        return {
            "id": int(row[0]),
            "case_id": row[1],
            "fname": row[2],
            "lname": row[3],
            "patient_age": row[4],
            "tooth_number": row[5],
            "complaint": row[6],
            "case_type": row[7],
            "status": row[8],
            "created_at": row[9],
        }

    @staticmethod
    def _ensure_user_columns(conn: sqlite3.Connection) -> None:
        existing = {
            row[1]
            for row in conn.execute("PRAGMA table_info(users)").fetchall()
        }
        additions = {
            "name": "TEXT",
            "phone": "TEXT",
            "practice_name": "TEXT",
            "bio": "TEXT",
            "specialty": "TEXT",
        }
        for col, typ in additions.items():
            if col not in existing:
                conn.execute(f"ALTER TABLE users ADD COLUMN {col} {typ}")

