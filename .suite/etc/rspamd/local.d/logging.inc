# Rspamd logging configuration
# Enable file logging to /var/log/rspamd.log

type = "file";
filename = "/var/log/rspamd.log";
level = "info";
log_re_cache = true;
log_usec = false;
