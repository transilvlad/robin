# Private, unique per-installation controller and enable credentials.
# Created once by init-secrets.sh; no shared checked-in password hash.
.include(priority=10) "/run/secrets/rspamd_controller.inc"
