"""Write login material for seeded accounts to CSV. Standard library only.

Access tokens use the same HS256 secret and claims as JwtTokenProvider (sub = member id, role).
Refresh tokens follow the raw format the SQL hashed in 00_users.sql: perf-rt-<salt>-<id>.
The files hold live credentials for the perf database: keep them in out/ (git-ignored).
"""
import argparse
import base64
import csv
import hashlib
import hmac
import json
import os
import time


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def sign(secret: bytes, member_id: int, role: str, issued_at: int, expires_at: int) -> str:
    header = b64url(json.dumps({"alg": "HS256"}, separators=(",", ":")).encode())
    claims = {"sub": str(member_id), "role": role, "iat": issued_at, "exp": expires_at}
    payload = b64url(json.dumps(claims, separators=(",", ":")).encode())
    signing_input = f"{header}.{payload}".encode("ascii")
    signature = hmac.new(secret, signing_input, hashlib.sha256).digest()
    return f"{header}.{payload}.{b64url(signature)}"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--users", type=int, required=True)
    parser.add_argument("--organizers", type=int, required=True)
    parser.add_argument("--salt", required=True)
    parser.add_argument("--ttl-hours", type=int, required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    secret = os.environ["JWT_SECRET"].encode("utf-8")
    if len(secret) < 32:
        raise SystemExit("JWT_SECRET must be at least 32 bytes (JwtProperties)")
    now = int(time.time())
    expires_at = now + args.ttl_hours * 3600

    def row(member_id, email, role):
        return [member_id, email, role, sign(secret, member_id, role, now, expires_at),
                f"perf-rt-{args.salt}-{member_id}"]

    header = ["user_id", "email", "role", "access_token", "refresh_token"]
    with open(os.path.join(args.out, "users.csv"), "w", newline="") as f:
        writer = csv.writer(f, lineterminator="\n")
        writer.writerow(header)
        for member_id in range(1, args.users + 1):
            writer.writerow(row(member_id, f"fanq{member_id}@fanq.com", "USER"))
    with open(os.path.join(args.out, "staff.csv"), "w", newline="") as f:
        writer = csv.writer(f, lineterminator="\n")
        writer.writerow(header)
        writer.writerow(row(args.users + 1, "admin@fanq.com", "ADMIN"))
        for n in range(1, args.organizers + 1):
            writer.writerow(row(args.users + 1 + n, f"organizer{n}@fanq.com", "ORGANIZER"))
    print(f"tokens: {args.users} users + 1 admin + {args.organizers} organizers, expire at {time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime(expires_at))}")


if __name__ == "__main__":
    main()
