<?php
$hostname = getenv('MAIL_HOSTNAME');
if (!$hostname || !filter_var($hostname, FILTER_VALIDATE_DOMAIN, FILTER_FLAG_HOSTNAME)) {
    throw new RuntimeException('MAIL_HOSTNAME must be a valid DNS hostname');
}
$config['smtp_user'] = '%u';
$config['smtp_pass'] = '%p';
$config['imap_conn_options'] = [
    'ssl' => ['verify_peer' => true, 'verify_peer_name' => true, 'peer_name' => $hostname],
];
$config['smtp_conn_options'] = $config['imap_conn_options'];
$desKey = rtrim(file_get_contents('/run/secrets/roundcube_des_key'), "\r\n");
if (strlen($desKey) !== 24) {
    throw new RuntimeException('Roundcube encryption key must contain exactly 24 characters');
}
$config['des_key'] = $desKey;
